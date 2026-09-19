package com.aruncs.musicsync.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("music_sync_prefs", Context.MODE_PRIVATE)

    var desktopIp: String
        get() = prefs.getString("desktop_ip", "192.168.1.100") ?: "192.168.1.100"
        set(value) = prefs.edit().putString("desktop_ip", value.trim()).apply()

    var desktopPort: Int
        get() = prefs.getInt("desktop_port", 5000)
        set(value) = prefs.edit().putInt("desktop_port", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 5000)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var isServerRunning: Boolean
        get() = prefs.getBoolean("is_server_running", false)
        set(value) = prefs.edit().putBoolean("is_server_running", value).apply()

    var lastSyncTimestamp: Long
        get() = prefs.getLong("last_sync_ts", 0L)
        set(value) = prefs.edit().putLong("last_sync_ts", value).apply()

    var downloadQuality: String
        get() = prefs.getString("download_quality", "original") ?: "original"
        set(value) = prefs.edit().putString("download_quality", value).apply()

    val musicStorageDirectory: File
        get() {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
}
