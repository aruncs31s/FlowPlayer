package com.aruncs.musicsync.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aruncs.musicsync.R
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.CrashLogger
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.data.PlaylistManager
import java.util.Locale

object QueryRunnerDialogHelper {

    fun show(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_query_runner, null)
        val etInput = dialogView.findViewById<EditText>(R.id.et_query_input)
        val btnExecute = dialogView.findViewById<Button>(R.id.btn_query_execute)
        val tvOutput = dialogView.findViewById<TextView>(R.id.tv_query_output)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_query_close)

        val prefs = AppPreferences(context)
        val playlistManager = PlaylistManager(context)

        fun runQuery(cmd: String) {
            val query = cmd.trim().lowercase(Locale.US)
            val sb = StringBuilder()
            sb.append("> $cmd\n\n")

            try {
                when {
                    query == "playlists" || query.startsWith("select playlists") -> {
                        val lists = playlistManager.getLocalPlaylists()
                        sb.append("Found ${lists.size} local playlist(s):\n")
                        for (pl in lists) {
                            sb.append("• [ID: ${pl.id}] '${pl.name}' — ${pl.trackCount} tracks\n")
                            for (path in pl.trackFilepaths.take(5)) {
                                sb.append("    - $path\n")
                            }
                            if (pl.trackFilepaths.size > 5) {
                                sb.append("    - ... and ${pl.trackFilepaths.size - 5} more\n")
                            }
                        }
                    }
                    query == "liked" || query.startsWith("select liked") -> {
                        val liked = playlistManager.getLikedFilepaths()
                        sb.append("Found ${liked.size} liked song path(s):\n")
                        for (path in liked.take(20)) {
                            sb.append("• $path\n")
                        }
                        if (liked.size > 20) {
                            sb.append("... and ${liked.size - 20} more\n")
                        }
                    }
                    query == "prefs" || query.startsWith("select prefs") -> {
                        sb.append("App Preferences:\n")
                        sb.append("• Desktop IP: ${prefs.desktopIp}\n")
                        sb.append("• Desktop Port: ${prefs.desktopPort}\n")
                        sb.append("• Server Port: ${prefs.serverPort}\n")
                        sb.append("• Download Quality: ${prefs.downloadQuality}\n")
                        sb.append("• Last Sync Timestamp: ${prefs.lastSyncTimestamp}\n")
                        sb.append("• Music Storage Dir: ${prefs.musicStorageDirectory.absolutePath}\n")
                    }
                    query == "devices" || query.startsWith("select devices") -> {
                        val devs = prefs.getSavedDevices()
                        sb.append("Saved Devices (${devs.size}):\n")
                        for (d in devs) {
                            sb.append("• ${d.name} (${d.ip}:${d.port}) [Role: ${d.role}, Online: ${d.isOnline}]\n")
                        }
                    }
                    query == "songs" || query.startsWith("select songs") -> {
                        val songs = MediaStoreHelper.getAllDeviceSongs(context)
                        sb.append("Local MediaStore Songs (${songs.size}):\n")
                        for (s in songs.take(20)) {
                            sb.append("• ${s.title} - ${s.artist} (${s.filename})\n")
                        }
                        if (songs.size > 20) {
                            sb.append("... and ${songs.size - 20} more\n")
                        }
                    }
                    query == "crash" || query.startsWith("select crash") -> {
                        val logs = CrashLogger.getCrashLogs(context)
                        sb.append(logs)
                    }
                    query == "help" || query == "?" -> {
                        sb.append("Available commands:\n")
                        sb.append("• playlists - View all local playlists\n")
                        sb.append("• liked - View liked/favorite song paths\n")
                        sb.append("• prefs - View SharedPreferences & settings\n")
                        sb.append("• devices - View saved peer devices\n")
                        sb.append("• songs - View indexed MediaStore songs\n")
                        sb.append("• crash - View crash & error logs\n")
                        sb.append("• clear - Clear output\n")
                    }
                    query == "clear" -> {
                        tvOutput.text = "Output cleared."
                        return
                    }
                    else -> {
                        sb.append("Unknown command: '$cmd'\nType 'help' for available commands.")
                    }
                }
            } catch (e: Exception) {
                sb.append("Error executing query: ${e.message}\n")
            }

            tvOutput.text = sb.toString()
        }

        btnExecute.setOnClickListener {
            val cmd = etInput.text.toString()
            if (cmd.isNotBlank()) {
                runQuery(cmd)
            }
        }

        dialogView.findViewById<TextView>(R.id.chip_q_playlists).setOnClickListener { runQuery("playlists") }
        dialogView.findViewById<TextView>(R.id.chip_q_liked).setOnClickListener { runQuery("liked") }
        dialogView.findViewById<TextView>(R.id.chip_q_prefs).setOnClickListener { runQuery("prefs") }
        dialogView.findViewById<TextView>(R.id.chip_q_devices).setOnClickListener { runQuery("devices") }
        dialogView.findViewById<TextView>(R.id.chip_q_songs).setOnClickListener { runQuery("songs") }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
