package com.aruncs.musicsync.ui.dialog

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.server.DiscoveredPeer
import com.aruncs.musicsync.server.PeerDiscoveryManager
import com.aruncs.musicsync.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

object SessionManagerDialogHelper {

    fun show(
        activity: Activity,
        scope: CoroutineScope,
        apiClient: DesktopApiClient,
        audioPlayer: AudioPlayer,
        targetIp: String,
        targetPort: Int,
        onLog: (String) -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_session_manager, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvEmpty = dialogView.findViewById<TextView>(R.id.tv_sessions_empty)
        val layoutList = dialogView.findViewById<LinearLayout>(R.id.layout_sessions_list)
        val btnRefresh = dialogView.findViewById<ImageButton>(R.id.btn_sessions_refresh)
        val mainHandler = Handler(Looper.getMainLooper())

        fun fmtMs(ms: Int): String {
            val s = ms / 1000
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        fun loadSessions() {
            tvEmpty?.text = "Scanning for active sessions..."
            tvEmpty?.visibility = View.VISIBLE
            layoutList?.visibility = View.GONE
            layoutList?.removeAllViews()

            scope.launch {
                val peers = withContext(Dispatchers.IO) {
                    try {
                        PeerDiscoveryManager.discoverAllPeers(
                            context = activity,
                            timeoutMs = 2500,
                            targetIp = targetIp,
                            targetPort = targetPort
                        )
                    } catch (e: Exception) {
                        emptyList()
                    }
                }.toMutableList()

                if (targetIp.isNotBlank() && targetIp != "0.0.0.0" && peers.none { it.ip == targetIp }) {
                    peers.add(DiscoveredPeer(targetIp, targetPort, "Desktop", "desktop"))
                }

                if (!dialog.isShowing) return@launch

                if (peers.isEmpty()) {
                    tvEmpty?.text = "No active sessions found on this network.\nMake sure Wi-Fi is connected and peers have the server running."
                    return@launch
                }

                val sessions: List<Pair<DiscoveredPeer, SessionState>> =
                    peers.mapNotNull { peer ->
                        withContext(Dispatchers.IO) {
                            try {
                                val state = apiClient.fetchSessionState(peer.ip, peer.port)
                                if (state != null) Pair(peer, state) else null
                            } catch (e: Exception) { null }
                        }
                    }

                if (!dialog.isShowing) return@launch

                if (sessions.isEmpty()) {
                    tvEmpty?.text = "Devices found but session API unavailable.\nUpdate the app on peer devices."
                    return@launch
                }

                tvEmpty?.visibility = View.GONE
                layoutList?.visibility = View.VISIBLE

                for ((peer, state) in sessions) {
                    val row = activity.layoutInflater.inflate(R.layout.item_session_device, layoutList, false)

                    row.findViewById<ImageView>(R.id.iv_session_device_icon)?.setImageResource(
                        if (peer.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer
                    )
                    row.findViewById<TextView>(R.id.tv_session_device_name)?.text =
                        state.deviceName.ifBlank { peer.displayName }
                    row.findViewById<TextView>(R.id.tv_session_device_ip)?.text =
                        "${peer.ip}:${peer.port}"

                    val badge = row.findViewById<TextView>(R.id.badge_session_status)
                    if (state.isPlaying) {
                        badge?.text = "PLAYING"
                        badge?.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
                        badge?.setBackgroundResource(R.drawable.bg_badge_green)
                    } else {
                        badge?.text = "PAUSED"
                        badge?.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))
                        badge?.setBackgroundResource(R.drawable.bg_badge_yellow)
                    }

                    row.findViewById<TextView>(R.id.tv_session_now_playing)?.text =
                        state.currentTitle.ifBlank { "Nothing playing" }
                    row.findViewById<TextView>(R.id.tv_session_artist)?.text = state.currentArtist

                    val prog = if (state.durationMs > 0)
                        ((state.positionMs * 1000L) / state.durationMs).toInt() else 0
                    row.findViewById<ProgressBar>(R.id.pb_session_progress)?.progress = prog
                    row.findViewById<TextView>(R.id.tv_session_pos)?.text = fmtMs(state.positionMs)
                    row.findViewById<TextView>(R.id.tv_session_dur)?.text = fmtMs(state.durationMs)

                    val btnPP = row.findViewById<ImageButton>(R.id.btn_session_play_pause)
                    btnPP?.setImageResource(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    btnPP?.setOnClickListener {
                        scope.launch {
                            if (state.isPlaying) apiClient.sessionPause(peer.ip, peer.port)
                            else apiClient.sessionPlay(peer.ip, peer.port)
                        }
                    }
                    row.findViewById<ImageButton>(R.id.btn_session_prev)?.setOnClickListener {
                        scope.launch { apiClient.sessionPrev(peer.ip, peer.port) }
                    }
                    row.findViewById<ImageButton>(R.id.btn_session_next)?.setOnClickListener {
                        scope.launch { apiClient.sessionNext(peer.ip, peer.port) }
                    }

                    row.findViewById<Button>(R.id.btn_session_play_here)?.setOnClickListener {
                        if (state.currentFilepath.isBlank()) return@setOnClickListener
                        scope.launch {
                            // Pause remote
                            apiClient.sessionPause(peer.ip, peer.port)
                            // Play locally by streaming from peer
                            withContext(Dispatchers.Main) {
                                val filename = File(state.currentFilepath).name
                                val encodedPath = URLEncoder.encode(state.currentFilepath, "UTF-8")
                                val streamUrl = "http://${peer.ip}:${peer.port}/api/song/stream?filepath=$encodedPath"
                                val song = Song(
                                    id = System.currentTimeMillis(),
                                    title = state.currentTitle.ifBlank { filename },
                                    artist = state.currentArtist,
                                    album = "",
                                    filepath = state.currentFilepath,
                                    filename = filename,
                                    size = 0L,
                                    sizeFormatted = "",
                                    mtime = 0.0,
                                    mtimeStr = "",
                                    durationSec = state.durationMs / 1000.0,
                                    durationFormatted = fmtMs(state.durationMs),
                                    bitrateKbps = "",
                                    searchableText = "${state.currentTitle} ${state.currentArtist}"
                                )
                                audioPlayer.play(song, streamUrl)
                                if (state.positionMs > 2000 && state.durationMs > 0) {
                                    mainHandler.postDelayed({
                                        audioPlayer.seekTo(state.positionMs.toFloat() / state.durationMs.toFloat())
                                    }, 800)
                                }
                                onLog("[SESSION] Transferred '${state.currentTitle}' from ${peer.displayName}")
                                Toast.makeText(activity, "Playing here: ${state.currentTitle}", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                        }
                    }

                    layoutList?.addView(row)
                }
            }
        }

        btnRefresh?.setOnClickListener { loadSessions() }
        loadSessions()
        dialog.show()
    }
}
