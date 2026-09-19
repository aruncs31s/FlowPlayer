package com.aruncs.musicsync.server

import android.content.Context
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.MediaScannerHelper
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.model.Song
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverIpServer(
    private val context: Context,
    port: Int = 5000,
    private val logCallback: ((String) -> Unit)? = null
) : NanoHTTPD(port) {

    private val prefs = AppPreferences(context)

    private fun log(msg: String) {
        logCallback?.invoke(msg)
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
                "/api/song/stream" -> handleStreamSong(session)
                "/api/song/upload" -> handleUploadSong(session)
                "/api/song/delete" -> handleDeleteSong(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"Not Found\"}")
            }
            addCorsHeaders(response)
            response
        } catch (e: Exception) {
            log("[ERROR] Server error handling $uri: ${e.message}")
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
        val songs = MediaStoreHelper.getAllDeviceSongs(context)
        val musicDir = prefs.musicStorageDirectory
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val json = JSONObject().apply {
            put("status", "ok")
            put("hostname", NetworkUtils.getDeviceModel())
            put("song_count", songs.size)
            put("folders", JSONArray().put(musicDir.absolutePath))
            put("server_time", sdf.format(Date()))
        }

        log("[SERVER] Handled /api/ping from peer — ${songs.size} song(s) reported")
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleSongs(session: IHTTPSession): Response {
        val songs = MediaStoreHelper.getAllDeviceSongs(context)
        val jsonArray = JSONArray()
        for (s in songs) {
            jsonArray.put(s.toJSONObject())
        }

        log("[SERVER] Handled /api/songs — dispatched ${songs.size} track(s)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun handleStreamSong(session: IHTTPSession): Response {
        val params = session.parameters
        val filepath = params["filepath"]?.firstOrNull() ?: params["file"]?.firstOrNull()

        if (filepath.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Missing filepath parameter\"}")
        }

        val file = File(filepath)
        if (!file.exists() || !file.isFile) {
            log("[SERVER] Stream 404: File not found: $filepath")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\": \"File not found\"}")
        }

        val fileLen = file.length()
        val mimeType = getMimeType(file.name)
        val rangeHeader = session.headers["range"]

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
            fis.skip(start)

            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, contentLength)
            res.addHeader("Content-Range", "bytes $start-$end/$fileLen")
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", contentLength.toString())
            return res
        } else {
            val fis = FileInputStream(file)
            val res = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileLen)
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", fileLen.toString())
            return res
        }
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
        if (filepath.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Missing filepath parameter\"}")
        }

        val file = File(filepath)
        if (file.exists()) {
            file.delete()
            MediaScannerHelper.scanFile(context, filepath)
            log("[SERVER] Deleted file on device: $filepath")
            val json = JSONObject().apply {
                put("status", "success")
                put("message", "Deleted ${file.name}")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
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
}
