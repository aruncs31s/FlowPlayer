package com.aruncs.musicsync.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.aruncs.musicsync.model.Song
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "MusicSyncCrash"
    private const val CRASH_FILE = "crash_log.txt"
    private const val PLAYER_ERRORS_FILE = "player_errors.log"
    private const val PREFS_NAME = "music_sync_crash_prefs"
    private const val KEY_HAD_CRASH = "had_crash"
    private const val KEY_LAST_CRASH_INFO = "last_crash_info"

    var currentPlayingSong: Song? = null
    var onPlayerErrorLogged: ((String) -> Unit)? = null
    var appContext: Context? = null

    private var isInitialized = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInitialized) return
        isInitialized = true

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(context, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val isPlayerCrash = stackTrace.contains("AudioPlayer") ||
                stackTrace.contains("MediaPlayer") ||
                stackTrace.contains("AudioTrack") ||
                stackTrace.contains("com.aruncs.musicsync.player")

        val crashReport = buildString {
            append("====================================================\n")
            append("TIME: $timeStamp\n")
            append("TYPE: ${if (isPlayerCrash) "PLAYER CRASH" else "APPLICATION CRASH"}\n")
            append("THREAD: ${thread.name} (id: ${thread.id})\n")
            append("DEVICE: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
            currentPlayingSong?.let { s ->
                append("ACTIVE TRACK: ${s.title.ifBlank { s.filename }} by ${s.artist} [${s.filepath}]\n")
            }
            append("EXCEPTION: ${throwable.javaClass.name}: ${throwable.message}\n")
            append("STACK TRACE:\n")
            append(stackTrace)
            append("====================================================\n\n")
        }

        Log.e(TAG, crashReport)

        // Write to persistent crash file
        try {
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) logDir.mkdirs()
            val crashFile = File(logDir, CRASH_FILE)
            // Cap crash log file size to 200KB to avoid runaway file growth
            if (crashFile.length() > 200 * 1024) {
                crashFile.delete()
            }
            crashFile.appendText(crashReport)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log to disk: ${e.message}")
        }

        // Set flag in SharedPreferences so UI can notify upon restart
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_HAD_CRASH, true)
                .putString(KEY_LAST_CRASH_INFO, "$timeStamp: ${throwable.javaClass.simpleName}: ${throwable.message ?: "Unknown error"}")
                .apply()
        } catch (ignored: Exception) {}
    }

    fun logPlayerError(tag: String, message: String, throwable: Throwable? = null) {
        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val logMsg = buildString {
            append("[$timeStamp] [$tag] $message")
            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                append("\nException: ${throwable.javaClass.name}: ${throwable.message}\n$sw")
            }
        }

        Log.e(tag, logMsg, throwable)
        onPlayerErrorLogged?.invoke("[$tag-ERR] $message")

        // Write to persistent player error log
        try {
            val ctx = appContext
            if (ctx != null) {
                val logDir = File(ctx.filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val file = File(logDir, PLAYER_ERRORS_FILE)
                if (file.length() > 200 * 1024) {
                    file.delete()
                }
                file.appendText(logMsg + "\n")
            }
        } catch (ignored: Exception) {}
    }

    fun checkAndClearPreviousCrash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HAD_CRASH, false)) {
            val lastInfo = prefs.getString(KEY_LAST_CRASH_INFO, "Previous crash detected")
            prefs.edit().putBoolean(KEY_HAD_CRASH, false).apply()
            return lastInfo
        }
        return null
    }

    fun getCrashLogs(context: Context): String {
        return try {
            val crashFile = File(File(context.filesDir, "logs"), CRASH_FILE)
            val playerErrorsFile = File(File(context.filesDir, "logs"), PLAYER_ERRORS_FILE)
            buildString {
                if (crashFile.exists() && crashFile.length() > 0) {
                    append("--- SYSTEM & CRASH DUMPS ---\n")
                    append(crashFile.readText())
                    append("\n")
                }
                if (playerErrorsFile.exists() && playerErrorsFile.length() > 0) {
                    append("--- PLAYER ERROR LOGS ---\n")
                    append(playerErrorsFile.readText())
                    append("\n")
                }
                if (isEmpty()) {
                    append("No crash or player error logs recorded. All systems healthy!")
                }
            }
        } catch (e: Exception) {
            "Error reading crash logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val logDir = File(context.filesDir, "logs")
            File(logDir, CRASH_FILE).delete()
            File(logDir, PLAYER_ERRORS_FILE).delete()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (ignored: Exception) {}
    }
}
