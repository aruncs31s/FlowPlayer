package com.aruncs.musicsync.client

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aruncs.musicsync.model.Playlist
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DesktopApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(ip: String, port: Int): String {
        val clean = ip.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "http://$clean:$port"
    }

    suspend fun ping(ip: String, port: Int): JSONObject = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/ping"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response from server")
            JSONObject(body)
        }
    }

    suspend fun fetchSongs(ip: String, port: Int): List<Song> = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/songs"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body?.string() ?: throw Exception("Empty songs payload")
            val array = JSONArray(body)
            val list = mutableListOf<Song>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Song.fromJSONObject(obj))
            }
            list
        }
    }

    suspend fun fetchPlaylistTracks(
        ip: String,
        port: Int,
        playlistId: Long? = null,
        playlistName: String? = null
    ): List<Song> = withContext(Dispatchers.IO) {
        val queryParam = if (playlistId != null) {
            "?id=$playlistId"
        } else if (!playlistName.isNullOrBlank()) {
            "?name=${URLEncoder.encode(playlistName, "UTF-8")}"
        } else ""

        val url = "${baseUrl(ip, port)}/api/playlist/tracks$queryParam"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body?.string() ?: throw Exception("Empty tracks payload")
            val array = JSONArray(body)
            val list = mutableListOf<Song>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Song.fromJSONObject(obj))
            }
            list
        }
    }

    suspend fun downloadSong(
        ip: String,
        port: Int,
        remoteFilepath: String,
        destFile: File,
        bitrate: String? = null,
        context: Context? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val encodedPath = URLEncoder.encode(remoteFilepath, "UTF-8")
        val filename = destFile.name.ifBlank { File(remoteFilepath).name }
        val encodedName = URLEncoder.encode(filename, "UTF-8")
        val numBr = bitrate?.filter { it.isDigit() }
        val qualityQuery = if (!numBr.isNullOrBlank()) "&target_bitrate=$numBr&bitrate=$numBr" else ""
        val url = "${baseUrl(ip, port)}/api/song/stream?filepath=$encodedPath&filename=$encodedName$qualityQuery"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} downloading track: ${response.message}")
            }

            val body = response.body ?: throw Exception("Empty body downloading track")
            val totalBytes = body.contentLength()

            val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            // On Android 10+ (API 29+), if app does not have full storage management,
            // write directly into public Music collection via MediaStore API
            if (!hasAllFilesAccess && context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return@withContext downloadViaMediaStore(context, destFile, body, totalBytes, onProgress)
            }

            try {
                val parentDir = destFile.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }

                val tmpFile = File(destFile.parentFile, "${destFile.name}.downloading")
                if (tmpFile.exists()) tmpFile.delete()

                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            onProgress?.invoke(downloadedBytes, totalBytes)
                        }
                        output.flush()
                    }
                }

                if (destFile.exists()) destFile.delete()
                tmpFile.renameTo(destFile)
                true
            } catch (e: Exception) {
                if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    downloadViaMediaStore(context, destFile, body, totalBytes, onProgress)
                } else {
                    throw e
                }
            }
        }
    }

    private fun downloadViaMediaStore(
        context: Context,
        destFile: File,
        body: okhttp3.ResponseBody,
        totalBytes: Long,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
    ): Boolean {
        val resolver = context.contentResolver
        val filename = destFile.name

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val projection = arrayOf(MediaStore.Audio.Media._ID)
                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(filename)
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        resolver.delete(uri, null, null)
                    }
                }
            } catch (ignored: Exception) {}
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
            put(MediaStore.Audio.Media.TITLE, filename.substringBeforeLast('.'))
            put(MediaStore.Audio.Media.MIME_TYPE, getAudioMimeType(filename))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Could not create MediaStore entry for $filename")

        try {
            var downloadedBytes = 0L
            resolver.openOutputStream(uri)?.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            } ?: throw Exception("Could not open output stream for MediaStore URI: $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return true
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {}
            throw e
        }
    }

    private fun getAudioMimeType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase(java.util.Locale.US)) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            else -> "audio/*"
        }
    }

    suspend fun deleteSong(
        ip: String,
        port: Int,
        remoteFilepath: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/song/delete"
        val jsonPayload = JSONObject().apply {
            put("filepath", remoteFilepath)
            put("device_id", "local")
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} deleting track: ${response.message}")
            }
            val resStr = response.body?.string() ?: "{}"
            JSONObject(resStr)
        }
    }

    suspend fun uploadSong(
        ip: String,
        port: Int,
        localFile: File,
        onProgress: ((progress: Float) -> Unit)? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/song/upload"
        val mediaType = "audio/*".toMediaTypeOrNull()
        val fileBody = localFile.asRequestBody(mediaType)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", localFile.name, fileBody)
            .addFormDataPart("filename", localFile.name)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} uploading track: ${response.message}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response uploading track")
            JSONObject(body)
        }
    }

    suspend fun fetchPlaylists(ip: String, port: Int): List<com.aruncs.musicsync.model.Playlist> = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/playlists"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body?.string() ?: "[]"
            val array = JSONArray(body)
            val list = mutableListOf<com.aruncs.musicsync.model.Playlist>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(com.aruncs.musicsync.model.Playlist.fromJSONObject(obj, isRemote = true))
            }
            list
        }
    }

    suspend fun createPlaylist(ip: String, port: Int, name: String): com.aruncs.musicsync.model.Playlist = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/playlists/create"
        val jsonPayload = JSONObject().apply {
            put("name", name.trim())
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} creating playlist: ${response.message}")
            }
            val resStr = response.body?.string() ?: "{}"
            val resJson = JSONObject(resStr)
            val plObj = resJson.optJSONObject("playlist") ?: resJson
            com.aruncs.musicsync.model.Playlist.fromJSONObject(plObj, isRemote = true)
        }
    }

    suspend fun deletePlaylist(ip: String, port: Int, playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/playlists/delete"
        val jsonPayload = JSONObject().apply {
            put("playlist_id", playlistId)
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun fetchPlaylistTracks(ip: String, port: Int, playlistId: Long): List<Song> = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/playlists/$playlistId/tracks"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body?.string() ?: "[]"
            val array = JSONArray(body)
            val list = mutableListOf<Song>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Song.fromJSONObject(obj))
            }
            list
        }
    }

    suspend fun addTrackToPlaylist(ip: String, port: Int, playlistId: Long, filepath: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/playlists/$playlistId/add-track"
        val jsonPayload = JSONObject().apply {
            put("filepath", filepath)
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun removeTrackFromPlaylist(ip: String, port: Int, playlistId: Long, filepath: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${baseUrl(ip, port)}/api/playlists/$playlistId/remove-track"
        val jsonPayload = JSONObject().apply {
            put("filepath", filepath)
        }.toString()

        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun fetchSessionState(ip: String, port: Int): SessionState? = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl(ip, port)}/api/session/state"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                SessionState.fromJSON(JSONObject(body))
            }
        } catch (e: Exception) { null }
    }

    private suspend fun sessionPost(ip: String, port: Int, path: String, json: JSONObject? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = (json?.toString() ?: "{}").toRequestBody("application/json".toMediaTypeOrNull())
            val url = "${baseUrl(ip, port)}$path"
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) { false }
    }

    suspend fun sessionPlay(ip: String, port: Int) = sessionPost(ip, port, "/api/session/play")
    suspend fun sessionPause(ip: String, port: Int) = sessionPost(ip, port, "/api/session/pause")
    suspend fun sessionNext(ip: String, port: Int) = sessionPost(ip, port, "/api/session/next")
    suspend fun sessionPrev(ip: String, port: Int) = sessionPost(ip, port, "/api/session/prev")

    suspend fun sessionSeek(ip: String, port: Int, positionMs: Int) = sessionPost(
        ip, port, "/api/session/seek?position_ms=$positionMs"
    )

    suspend fun sessionQueueInject(
        ip: String, port: Int,
        filepath: String, title: String, artist: String, album: String = "",
        streamUrl: String? = null
    ) = sessionPost(ip, port, "/api/session/queue_inject", JSONObject().apply {
        put("filepath", filepath)
        put("title", title)
        put("artist", artist)
        put("album", album)
        if (!streamUrl.isNullOrBlank()) put("stream_url", streamUrl)
    })

    suspend fun sessionTransfer(
        ip: String, port: Int,
        filepath: String, title: String, artist: String, album: String = "",
        positionMs: Int = 0, streamUrl: String? = null
    ) = sessionPost(ip, port, "/api/session/transfer", JSONObject().apply {
        put("filepath", filepath)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("position_ms", positionMs)
        if (!streamUrl.isNullOrBlank()) put("stream_url", streamUrl)
    })

    suspend fun sessionQueueSync(
        ip: String, port: Int,
        tracks: List<Song>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            for (song in tracks) {
                jsonArray.put(JSONObject().apply {
                    put("filepath", song.filepath)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                })
            }
            val body = jsonArray.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val url = "${baseUrl(ip, port)}/api/session/queue_sync"
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
