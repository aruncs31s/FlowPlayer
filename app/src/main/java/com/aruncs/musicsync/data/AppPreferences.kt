package com.aruncs.musicsync.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.aruncs.musicsync.model.SyncDevice
import org.json.JSONArray
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
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            } catch (ignored: Throwable) {}
            return dir
        }

    fun getSavedDevices(): List<SyncDevice> {
        val jsonStr = prefs.getString("saved_devices_json", null)
        val list = mutableListOf<SyncDevice>()
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(SyncDevice.fromJSONObject(array.getJSONObject(i)))
                }
            } catch (e: Exception) {}
        }
        if (list.isEmpty() && desktopIp.isNotBlank() && desktopIp != "192.168.1.100") {
            list.add(
                SyncDevice(
                    id = "$desktopIp:$desktopPort",
                    name = "Desktop Server",
                    ip = desktopIp,
                    port = desktopPort,
                    role = "desktop",
                    isOnline = false,
                    isManual = true
                )
            )
            saveDevices(list)
        }
        return list
    }

    fun saveDevices(devices: List<SyncDevice>) {
        val array = JSONArray()
        for (d in devices) {
            array.put(d.toJSONObject())
        }
        prefs.edit().putString("saved_devices_json", array.toString()).apply()
    }

    fun addOrUpdateDevice(device: SyncDevice) {
        val current = getSavedDevices().toMutableList()
        val idx = current.indexOfFirst { it.id == device.id || (it.ip.equals(device.ip, true) && it.port == device.port) }
        if (idx >= 0) {
            val existing = current[idx]
            if (device.isManual && device.name.isNotBlank()) {
                existing.name = device.name
            } else if (existing.name.isBlank()) {
                existing.name = device.name
            }
            existing.ip = device.ip
            existing.port = device.port
            existing.role = device.role
            existing.lastSeen = device.lastSeen
            current[idx] = existing
        } else {
            current.add(device)
        }
        saveDevices(current)
    }

    fun updateDeviceName(id: String, newName: String) {
        val current = getSavedDevices().toMutableList()
        val dev = current.firstOrNull { it.id == id }
        if (dev != null) {
            dev.name = newName.trim()
            saveDevices(current)
        }
    }

    fun removeDevice(id: String) {
        val current = getSavedDevices().filterNot { it.id == id }
        saveDevices(current)
    }
}
