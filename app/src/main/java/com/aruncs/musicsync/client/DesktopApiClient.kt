package com.aruncs.musicsync.client

import com.aruncs.musicsync.model.Song
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

    suspend fun downloadSong(
        ip: String,
        port: Int,
        remoteFilepath: String,
        destFile: File,
        bitrate: String? = null,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(remoteFilepath, "UTF-8")
        val qualityQuery = if (!bitrate.isNullOrBlank() && bitrate.lowercase() != "original") "&bitrate=$bitrate" else ""
        val url = "${baseUrl(ip, port)}/api/song/stream?filepath=$encoded$qualityQuery"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} downloading track: ${response.message}")
            }

            val body = response.body ?: throw Exception("Empty body downloading track")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val tmpFile = File(destFile.parentFile, "${destFile.name}.downloading")
            if (tmpFile.exists()) tmpFile.delete()

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
}
