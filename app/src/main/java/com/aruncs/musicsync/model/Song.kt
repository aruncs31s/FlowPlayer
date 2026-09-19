package com.aruncs.musicsync.model

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val filepath: String,
    val filename: String,
    val size: Long,
    val sizeFormatted: String,
    val mtime: Double,
    val mtimeStr: String,
    val durationSec: Double,
    val durationFormatted: String,
    val bitrateKbps: String,
    val searchableText: String
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("_id", id)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("_data", filepath)
            put("filepath", filepath)
            put("filename", filename)
            put("size", size)
            put("size_formatted", sizeFormatted)
            put("mtime", mtime)
            put("mtime_str", mtimeStr)
            put("duration_sec", durationSec)
            put("duration_formatted", durationFormatted)
            put("bitrate_kbps", bitrateKbps)
            put("searchable_text", searchableText)
        }
    }

    companion object {
        fun fromFile(id: Long, file: File, title: String?, artist: String?, album: String?, durationMs: Long): Song {
            val filename = file.name
            val t = if (!title.isNullOrBlank()) title else file.nameWithoutExtension
            val a = if (!artist.isNullOrBlank()) artist else "Unknown"
            val alb = if (!album.isNullOrBlank()) album else "Unknown"

            val sizeBytes = file.length()
            val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
            val sizeFormatted = String.format(Locale.US, "%.1f MB", sizeMb)

            val mtimeSec = file.lastModified().toDouble() / 1000.0
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val mtimeStr = sdf.format(Date(file.lastModified()))

            val durSec = (durationMs / 1000).toDouble()
            val mins = durationMs / 1000 / 60
            val secs = (durationMs / 1000) % 60
            val durFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)

            val searchable = "$t $a $alb $filename".lowercase(Locale.US)

            return Song(
                id = id,
                title = t,
                artist = a,
                album = alb,
                filepath = file.absolutePath,
                filename = filename,
                size = sizeBytes,
                sizeFormatted = sizeFormatted,
                mtime = mtimeSec,
                mtimeStr = mtimeStr,
                durationSec = durSec,
                durationFormatted = durFormatted,
                bitrateKbps = "Unknown",
                searchableText = searchable
            )
        }

        fun fromJSONObject(obj: JSONObject): Song {
            val id = obj.optLong("_id", 0L)
            val title = obj.optString("title", "Unknown")
            val artist = obj.optString("artist", "Unknown")
            val album = obj.optString("album", "Unknown")
            val filepath = obj.optString("filepath", obj.optString("_data", ""))
            val filename = obj.optString("filename", if (filepath.isNotEmpty()) File(filepath).name else "track.mp3")
            val size = obj.optLong("size", 0L)
            val sizeFormatted = obj.optString("size_formatted", "")
            val mtime = obj.optDouble("mtime", 0.0)
            val mtimeStr = obj.optString("mtime_str", "")
            val durationSec = obj.optDouble("duration_sec", 0.0)
            val durationFormatted = obj.optString("duration_formatted", "00:00")
            val bitrate = obj.optString("bitrate_kbps", "Unknown")
            val searchable = obj.optString("searchable_text", "$title $artist $album $filename".lowercase(Locale.US))

            return Song(
                id = id,
                title = title,
                artist = artist,
                album = album,
                filepath = filepath,
                filename = filename,
                size = size,
                sizeFormatted = sizeFormatted,
                mtime = mtime,
                mtimeStr = mtimeStr,
                durationSec = durationSec,
                durationFormatted = durationFormatted,
                bitrateKbps = bitrate,
                searchableText = searchable
            )
        }
    }
}
