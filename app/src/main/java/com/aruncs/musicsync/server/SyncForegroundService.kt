package com.aruncs.musicsync.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aruncs.musicsync.MainActivity
import com.aruncs.musicsync.R
import com.aruncs.musicsync.data.AppPreferences

class SyncForegroundService : Service() {

    private var server: OverIpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = "com.aruncs.musicsync.ACTION_START"
        const val ACTION_STOP = "com.aruncs.musicsync.ACTION_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "channel_music_sync_server"

        var isRunning = false
            private set

        var onLogReceived: ((String) -> Unit)? = null
        var onStateChanged: ((Boolean, String?) -> Unit)? = null

        fun log(msg: String) {
            onLogReceived?.invoke(msg)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopServer()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning) return

        val prefs = AppPreferences(this)
        val port = prefs.serverPort
        val ip = NetworkUtils.getWifiIpAddress(this) ?: "0.0.0.0"

        try {
            acquireLocks()

            server = OverIpServer(this, port) { logMsg ->
                log(logMsg)
            }
            server?.start()

            isRunning = true
            prefs.isServerRunning = true

            val notif = buildNotification("Running at http://$ip:$port")
            startForeground(NOTIFICATION_ID, notif)

            log("[SERVER] Over-IP Server started on http://$ip:$port")
            onStateChanged?.invoke(true, "http://$ip:$port")
        } catch (e: Exception) {
            e.printStackTrace()
            log("[ERROR] Failed to start Over-IP server: ${e.message}")
            isRunning = false
            prefs.isServerRunning = false
            onStateChanged?.invoke(false, null)
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            server = null
            releaseLocks()
            log("[SERVER] Over-IP Server stopped.")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isRunning = false
            AppPreferences(this).isServerRunning = false
            onStateChanged?.invoke(false, null)
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidMusicSync:ServerWakeLock")?.apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AndroidMusicSync:WifiLock")?.apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val stopIntent = Intent(this, SyncForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
