package com.aruncs.musicsync.session

import org.json.JSONObject

data class SessionState(
    val deviceId: String,
    val deviceName: String,
    val deviceRole: String,   // "android" | "desktop"
    val isPlaying: Boolean,
    val currentTitle: String,
    val currentArtist: String,
    val currentFilepath: String,
    val positionMs: Int,
    val durationMs: Int,
    val queueSize: Int,
    val repeatMode: String,   // "off"|"all"|"one"
    val isShuffled: Boolean
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("device_role", deviceRole)
        put("is_playing", isPlaying)
        put("current_title", currentTitle)
        put("current_artist", currentArtist)
        put("current_filepath", currentFilepath)
        put("position_ms", positionMs)
        put("duration_ms", durationMs)
        put("queue_size", queueSize)
        put("repeat_mode", repeatMode)
        put("is_shuffled", isShuffled)
    }

    companion object {
        fun fromJSON(json: JSONObject) = SessionState(
            deviceId = json.optString("device_id", ""),
            deviceName = json.optString("device_name", ""),
            deviceRole = json.optString("device_role", "android"),
            isPlaying = json.optBoolean("is_playing", false),
            currentTitle = json.optString("current_title", ""),
            currentArtist = json.optString("current_artist", ""),
            currentFilepath = json.optString("current_filepath", ""),
            positionMs = json.optInt("position_ms", 0),
            durationMs = json.optInt("duration_ms", 0),
            queueSize = json.optInt("queue_size", 0),
            repeatMode = json.optString("repeat_mode", "off"),
            isShuffled = json.optBoolean("is_shuffled", false)
        )
    }
}
