package com.aruncs.musicsync.model

import org.json.JSONObject

data class SyncDevice(
    val id: String,
    var name: String,
    var ip: String,
    var port: Int = 5000,
    var role: String = "desktop",
    var isOnline: Boolean = false,
    var isManual: Boolean = false,
    var lastSeen: Long = System.currentTimeMillis()
) {
    val isAndroid: Boolean
        get() = role.equals("android", ignoreCase = true)

    val address: String
        get() = "$ip:$port"

    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("ip", ip)
            put("port", port)
            put("role", role)
            put("isManual", isManual)
            put("lastSeen", lastSeen)
        }
    }

    companion object {
        fun fromJSONObject(json: JSONObject): SyncDevice {
            val ip = json.optString("ip", "")
            val port = json.optInt("port", 5000)
            val id = json.optString("id", "$ip:$port")
            val name = json.optString("name", if (json.optString("role") == "android") "Android Device" else "Desktop")
            val role = json.optString("role", "desktop")
            val isManual = json.optBoolean("isManual", false)
            val lastSeen = json.optLong("lastSeen", System.currentTimeMillis())
            return SyncDevice(
                id = id,
                name = name,
                ip = ip,
                port = port,
                role = role,
                isOnline = false,
                isManual = isManual,
                lastSeen = lastSeen
            )
        }
    }
}
