package com.aruncs.musicsync.model

import org.json.JSONArray
import org.json.JSONObject

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val isRemote: Boolean = false,
    val trackFilepaths: List<String> = emptyList()
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("track_count", trackCount)
            put("is_remote", isRemote)
            val arr = JSONArray()
            trackFilepaths.forEach { arr.put(it) }
            put("track_filepaths", arr)
        }
    }

    companion object {
        fun fromJSONObject(obj: JSONObject, isRemote: Boolean = false): Playlist {
            val id = obj.optLong("id", System.currentTimeMillis())
            val name = obj.optString("name", "Untitled")
            val count = obj.optInt("track_count", 0)
            val arr = obj.optJSONArray("track_filepaths")
            val tracks = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    tracks.add(arr.getString(i))
                }
            }
            return Playlist(
                id = id,
                name = name,
                trackCount = if (count > 0) count else tracks.size,
                isRemote = isRemote,
                trackFilepaths = tracks
            )
        }
    }
}
