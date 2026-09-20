package com.aruncs.musicsync.server

import android.content.Context
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.MediaScannerHelper
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.data.AudioTranscoderHelper
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.data.CrashLogger
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.aruncs.musicsync.session.SessionState

class OverIpServer(
    private val context: Context,
    port: Int = 5000,
    private val logCallback: ((String) -> Unit)? = null
) : NanoHTTPD(port) {

    companion object {
        var onSessionStateRequest: (() -> SessionState)? = null
        var onSessionPlay: (() -> Unit)? = null
        var onSessionPause: (() -> Unit)? = null
        var onSessionNext: (() -> Unit)? = null
        var onSessionPrev: (() -> Unit)? = null
        var onSessionSeek: ((positionMs: Int) -> Unit)? = null
        var onSessionQueueInject: ((filepath: String, title: String, artist: String, album: String, streamUrl: String?) -> Unit)? = null
        var onSessionQueueSync: ((tracks: List<Song>) -> Unit)? = null
        var onSessionTransfer: ((filepath: String, title: String, artist: String, album: String, positionMs: Int, streamUrl: String?) -> Unit)? = null
    }

    private val prefs = AppPreferences(context)
    private val playlistManager = PlaylistManager(context)

    private fun log(msg: String) {
        try {
            logCallback?.invoke(msg)
        } catch (ignored: Throwable) {}
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        // Handle CORS Preflight
        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(res)
            return res
        }

        return try {
            val response = when (uri) {
                "/api/ping" -> handlePing()
                "/api/songs" -> handleSongs(session)
                "/api/playlists" -> handlePlaylists()
                "/api/playlist/tracks" -> handlePlaylistTracks(session)
                "/api/song/stream" -> handleStreamSong(session)
                "/api/song/upload" -> handleUploadSong(session)
                "/api/song/delete" -> handleDeleteSong(session)
                "/api/session/state" -> handleSessionState()
                "/api/session/play" -> handleSessionPlay()
                "/api/session/pause" -> handleSessionPause()
                "/api/session/next" -> handleSessionNext()
                "/api/session/prev" -> handleSessionPrev()
                "/api/session/seek" -> handleSessionSeek(session)
                "/api/session/queue_inject" -> handleSessionQueueInject(session)
                "/api/session/queue_sync" -> handleSessionQueueSync(session)
                "/api/session/transfer" -> handleSessionTransfer(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"Not Found\"}")
            }
            addCorsHeaders(response)
            response
        } catch (e: Throwable) {
            log("[ERROR] Server error handling $uri: ${e.message}")
            CrashLogger.logPlayerError("OverIpServer", "Server error handling $uri: ${e.message}", e)
            val errorRes = newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                "{\"error\": \"${e.message}\"}"
            )
            addCorsHeaders(errorRes)
            errorRes
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range, Authorization")
        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length")
    }

    private fun handlePing(): Response {
        val count = MediaStoreHelper.getDeviceSongCount(context)
        val musicDir = prefs.musicStorageDirectory
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val json = JSONObject().apply {
            put("status", "ok")
            put("hostname", NetworkUtils.getDeviceModel())
            put("song_count", count)
            put("folders", JSONArray().put(musicDir.absolutePath))
            put("server_time", sdf.format(Date()))
        }

        log("[SERVER] Handled /api/ping from peer — $count song(s) reported")
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleSongs(session: IHTTPSession): Response {
        val forceRefresh = session.parameters["refresh"]?.firstOrNull()?.toBoolean() ?: false
        val songs = MediaStoreHelper.getAllDeviceSongs(context, forceRefresh = forceRefresh)
        val jsonArray = JSONArray()
        for (s in songs) {
            jsonArray.put(s.toJSONObject())
        }

        log("[SERVER] Handled /api/songs — dispatched ${songs.size} track(s)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun handlePlaylists(): Response {
        val playlists = playlistManager.getLocalPlaylists()
        val jsonArray = JSONArray()
        for (p in playlists) {
            jsonArray.put(p.toJSONObject())
        }
        log("[SERVER] Handled /api/playlists — dispatched ${playlists.size} playlist(s)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun handlePlaylistTracks(session: IHTTPSession): Response {
        val params = session.parameters
        val idParam = params["id"]?.firstOrNull()?.toLongOrNull()
        val nameParam = params["name"]?.firstOrNull()

        val playlists = playlistManager.getLocalPlaylists()
        val playlist = if (idParam != null) {
            playlists.firstOrNull { it.id == idParam }
        } else if (!nameParam.isNullOrBlank()) {
            playlists.firstOrNull { it.name.equals(nameParam, ignoreCase = true) }
        } else null

        if (playlist == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"Playlist not found\"}")
        }

        val allSongs = MediaStoreHelper.getAllDeviceSongs(context)
        val songs = playlistManager.getPlaylistSongs(playlist, allSongs)
        val jsonArray = JSONArray()
        for (s in songs) {
            jsonArray.put(s.toJSONObject())
        }
        log("[SERVER] Handled /api/playlist/tracks for '${playlist.name}' — dispatched ${songs.size} track(s)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun handleStreamSong(session: IHTTPSession): Response {
        val params = session.parameters
        val rawFilepath = params["filepath"]?.firstOrNull() ?: params["file"]?.firstOrNull()
        val rawFilename = params["filename"]?.firstOrNull()

        if (rawFilepath.isNullOrBlank() && rawFilename.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Missing filepath or filename parameter\"}")
        }

        val originalFile = resolveSongFile(rawFilepath, rawFilename)
        if (originalFile == null || !originalFile.exists() || !originalFile.isFile) {
            log("[SERVER] Stream 404: File not found: filepath='$rawFilepath', filename='$rawFilename'")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"File not found on device\"}")
        }

        val targetBitrate = params["target_bitrate"]?.firstOrNull()?.toIntOrNull()
            ?: params["bitrate"]?.firstOrNull()?.toIntOrNull()

        val file = try {
            if (targetBitrate != null && AudioTranscoderHelper.needsDownconversion(originalFile, targetBitrate)) {
                log("[SERVER] Downconverting ${originalFile.name} to ${targetBitrate}k AAC on-the-fly...")
                AudioTranscoderHelper.transcodeAudio(originalFile, targetBitrate, context.cacheDir)
            } else {
                originalFile
            }
        } catch (t: Throwable) {
            log("[SERVER] Downconversion error for ${originalFile.name}: ${t.message}. Serving original.")
            originalFile
        }

        val fileLen = file.length()
        val mimeType = getMimeType(file.name)
        val rangeHeader = session.headers["range"]

        return try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeVal = rangeHeader.substring(6)
                var start = 0L
                var end = fileLen - 1

                val dashIndex = rangeVal.indexOf('-')
                if (dashIndex != -1) {
                    val startStr = rangeVal.substring(0, dashIndex).trim()
                    val endStr = rangeVal.substring(dashIndex + 1).trim()
                    if (startStr.isNotEmpty()) start = startStr.toLongOrNull() ?: 0L
                    if (endStr.isNotEmpty()) end = endStr.toLongOrNull() ?: (fileLen - 1)
                }

                if (start > end || start >= fileLen) {
                    val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    res.addHeader("Content-Range", "bytes */$fileLen")
                    return res
                }

                val contentLength = end - start + 1
                val fis = FileInputStream(file)
                if (start > 0) {
                    fis.skip(start)
                }

                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, contentLength)
                res.addHeader("Content-Range", "bytes $start-$end/$fileLen")
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", contentLength.toString())
                res
            } else {
                val fis = FileInputStream(file)
                val res = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileLen)
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Length", fileLen.toString())
                res
            }
        } catch (e: Throwable) {
            log("[SERVER] Error streaming file ${file.name}: ${e.message}")
            CrashLogger.logPlayerError("OverIpServer", "Error streaming file ${file.name}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"Streaming error: ${e.message}\"}")
        }
    }

    /**
     * Resolves audio file location using a 6-tier fallback system:
     * 1. Direct path check
     * 2. URL-decoded variations (percent decoding, '+' replaced with ' ')
     * 3. musicStorageDirectory/<filename>
     * 4. musicStorageDirectory/ADB/<filename>
     * 5. Recursive search in musicStorageDirectory
     * 6. MediaStore query by filename, title, or path
     */
    private fun resolveSongFile(rawFilepath: String?, rawFilename: String?): File? {
        if (rawFilepath.isNullOrBlank() && rawFilename.isNullOrBlank()) return null

        val candidatePaths = LinkedHashSet<String>()

        // 1 & 2. Direct path and URL-decoded variations
        if (!rawFilepath.isNullOrBlank()) {
            candidatePaths.add(rawFilepath)
            try {
                val dec = URLDecoder.decode(rawFilepath, "UTF-8")
                candidatePaths.add(dec)
                candidatePaths.add(rawFilepath.replace('+', ' '))
                candidatePaths.add(dec.replace('+', ' '))
            } catch (ignored: Throwable) {}
        }

        for (p in candidatePaths) {
            try {
                val f = File(p)
                if (f.exists() && f.isFile) return f
            } catch (ignored: Throwable) {}
        }

        // Collect all potential filenames
        val candidateNames = LinkedHashSet<String>()
        if (!rawFilename.isNullOrBlank()) {
            candidateNames.add(rawFilename)
            try {
                val dec = URLDecoder.decode(rawFilename, "UTF-8")
                candidateNames.add(dec)
                candidateNames.add(rawFilename.replace('+', ' '))
                candidateNames.add(dec.replace('+', ' '))
            } catch (ignored: Throwable) {}
        }
        for (p in candidatePaths) {
            try {
                val name = File(p).name
                if (name.isNotBlank()) {
                    candidateNames.add(name)
                }
            } catch (ignored: Throwable) {}
        }

        val musicDir = prefs.musicStorageDirectory

        // 3. Look in musicStorageDirectory
        for (name in candidateNames) {
            try {
                val f = File(musicDir, name)
                if (f.exists() && f.isFile) return f
            } catch (ignored: Throwable) {}
        }

        // 4. Look in ADB subfolder
        val adbDir = File(musicDir, "ADB")
        for (name in candidateNames) {
            try {
                val f = File(adbDir, name)
                if (f.exists() && f.isFile) return f
            } catch (ignored: Throwable) {}
        }

        // 5. Search subdirectories of musicStorageDirectory recursively
        for (name in candidateNames) {
            try {
                val found = searchDirectoryRecursive(musicDir, name, maxDepth = 3)
                if (found != null && found.exists() && found.isFile) return found
            } catch (ignored: Throwable) {}
        }

        // 6. Match against MediaStore database
        try {
            val allSongs = MediaStoreHelper.getAllDeviceSongs(context)
            for (name in candidateNames) {
                val cleanName = name.lowercase(Locale.US)
                val cleanWithoutExt = File(name).nameWithoutExtension.lowercase(Locale.US)
                val matched = allSongs.firstOrNull { s ->
                    s.filename.equals(name, ignoreCase = true) ||
                    s.filename.lowercase(Locale.US) == cleanName ||
                    s.title.lowercase(Locale.US) == cleanWithoutExt ||
                    File(s.filepath).name.equals(name, ignoreCase = true)
                }
                if (matched != null) {
                    val f = File(matched.filepath)
                    if (f.exists() && f.isFile) return f
                }
            }
        } catch (ignored: Throwable) {}

        return null
    }

    private fun searchDirectoryRecursive(dir: File, targetFilename: String, maxDepth: Int): File? {
        if (!dir.exists() || !dir.isDirectory || maxDepth <= 0) return null
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isFile && f.name.equals(targetFilename, ignoreCase = true)) {
                return f
            } else if (f.isDirectory && !f.name.startsWith(".")) {
                val sub = searchDirectoryRecursive(f, targetFilename, maxDepth - 1)
                if (sub != null) return sub
            }
        }
        return null
    }

    private fun handleUploadSong(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)

        val musicDir = prefs.musicStorageDirectory
        var uploadedFileName: String? = null
        var destFile: File? = null

        val params = session.parameters
        val filenameParam = params["filename"]?.firstOrNull()

        for ((key, tempPath) in files) {
            if (key == "file" || key.startsWith("file")) {
                val origName = filenameParam ?: File(tempPath).name.let {
                    if (it.endsWith(".tmp")) "synced_track_${System.currentTimeMillis()}.mp3" else it
                }
                destFile = File(musicDir, origName)
                val tmpFile = File(tempPath)
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
                uploadedFileName = origName
                break
            }
        }

        if (destFile != null && destFile.exists()) {
            MediaScannerHelper.scanFile(context, destFile.absolutePath)
            MediaStoreHelper.invalidateCache()
            log("[SERVER] Upload complete: Received '${destFile.name}' (${destFile.length()} bytes)")
            val json = JSONObject().apply {
                put("status", "success")
                put("message", "Saved ${destFile.name}")
                put("dest_path", destFile.absolutePath)
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        } else {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"No valid file uploaded\"}")
        }
    }

    private fun handleDeleteSong(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: ""

        val jsonObj = try {
            JSONObject(postData)
        } catch (e: Exception) {
            JSONObject()
        }

        val filepath = jsonObj.optString("filepath")
        val filename = jsonObj.optString("filename")
        if (filepath.isEmpty() && filename.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Missing filepath parameter\"}")
        }

        val file = resolveSongFile(filepath.ifEmpty { null }, filename.ifEmpty { null })
        if (file != null && file.exists()) {
            try {
                val path = file.absolutePath
                val name = file.name
                file.delete()
                MediaScannerHelper.scanFile(context, path)
                MediaStoreHelper.invalidateCache()
                log("[SERVER] Deleted file on device: $path")
                val json = JSONObject().apply {
                    put("status", "success")
                    put("message", "Deleted $name")
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            } catch (t: Throwable) {
                log("[SERVER] Error deleting file: ${t.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"Failed to delete: ${t.message}\"}")
            }
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"File not found\"}")
        }
    }

    private fun getMimeType(filename: String): String {
        return when (File(filename).extension.lowercase(Locale.US)) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/*"
        }
    }
    private fun handleSessionState(): Response {
        val state = onSessionStateRequest?.invoke()
        return if (state != null) {
            newFixedLengthResponse(Response.Status.OK, "application/json", state.toJSON().toString())
        } else {
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"is_playing\":false,\"current_title\":\"\",\"current_artist\":\"\",\"position_ms\":0,\"duration_ms\":0,\"queue_size\":0}")
        }
    }

    private fun handleSessionPlay(): Response {
        onSessionPlay?.invoke()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionPause(): Response {
        onSessionPause?.invoke()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionNext(): Response {
        onSessionNext?.invoke()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionPrev(): Response {
        onSessionPrev?.invoke()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionSeek(session: IHTTPSession): Response {
        val posMs = session.parameters["position_ms"]?.firstOrNull()?.toIntOrNull() ?: 0
        onSessionSeek?.invoke(posMs)
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionQueueInject(session: IHTTPSession): Response {
        val body = try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            JSONObject(map["postData"] ?: "{}")
        } catch (e: Exception) { JSONObject() }
        val filepath = body.optString("filepath", "")
        val title = body.optString("title", filepath)
        val artist = body.optString("artist", "")
        val album = body.optString("album", "")
        val streamUrl = body.optString("stream_url", "").ifBlank { null }
        if (filepath.isNotBlank()) {
            onSessionQueueInject?.invoke(filepath, title, artist, album, streamUrl)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionTransfer(session: IHTTPSession): Response {
        val body = try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            JSONObject(map["postData"] ?: "{}")
        } catch (e: Exception) { JSONObject() }
        val filepath = body.optString("filepath", "")
        val title = body.optString("title", filepath)
        val artist = body.optString("artist", "")
        val album = body.optString("album", "")
        val positionMs = body.optInt("position_ms", 0)
        val streamUrl = body.optString("stream_url", "").ifBlank { null }
        if (filepath.isNotBlank()) {
            onSessionTransfer?.invoke(filepath, title, artist, album, positionMs, streamUrl)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun handleSessionQueueSync(session: IHTTPSession): Response {
        val body = try {
            val map = mutableMapOf<String, String>()
            session.parseBody(map)
            JSONArray(map["postData"] ?: "[]")
        } catch (e: Exception) { JSONArray() }

        val songs = mutableListOf<Song>()
        for (i in 0 until body.length()) {
            val obj = body.getJSONObject(i)
            val filepath = obj.optString("filepath", "")
            val title = obj.optString("title", filepath)
            val artist = obj.optString("artist", "")
            val album = obj.optString("album", "")
            if (filepath.isNotBlank()) {
                songs.add(
                    Song(
                        id = System.currentTimeMillis() + i,
                        title = title,
                        artist = artist,
                        album = album,
                        filepath = filepath,
                        filename = File(filepath).name,
                        size = 0L,
                        sizeFormatted = "",
                        mtime = 0.0,
                        mtimeStr = "",
                        durationSec = 0.0,
                        durationFormatted = "",
                        bitrateKbps = "",
                        searchableText = "$title $artist"
                    )
                )
            }
        }
        if (songs.isNotEmpty()) {
            onSessionQueueSync?.invoke(songs)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true,\"count\":${songs.size}}")
    }
}
