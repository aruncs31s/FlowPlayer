package com.aruncs.musicsync.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.ui.adapter.QueueAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object QueueDialogHelper {

    fun show(
        context: Context,
        audioPlayer: AudioPlayer,
        scope: CoroutineScope,
        apiClient: DesktopApiClient,
        getTargetPeerIp: () -> String,
        getTargetPeerPort: () -> Int,
        getTargetPeerName: () -> String?,
        onQueueCleared: (() -> Unit)? = null
    ) {
        val q = audioPlayer.queue
        if (q.isEmpty()) {
            Toast.makeText(context, "Playback queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_queue, null)
        val tvCount = dialogView.findViewById<TextView>(R.id.tv_dialog_queue_count)
        val btnClear = dialogView.findViewById<ImageButton>(R.id.btn_dialog_queue_clear)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_dialog_queue_close)
        val btnSyncPeer = dialogView.findViewById<Button>(R.id.btn_dialog_queue_sync_peer)
        val rvQueue = dialogView.findViewById<RecyclerView>(R.id.rv_dialog_queue)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        tvCount.text = "${q.size} ${if (q.size == 1) "track" else "tracks"}"
        rvQueue.layoutManager = LinearLayoutManager(context)

        val queueAdapter = QueueAdapter(
            onItemClick = { position ->
                audioPlayer.playTrackAtIndex(position)
                dialog.dismiss()
            }
        )
        rvQueue.adapter = queueAdapter
        queueAdapter.submitQueue(q, audioPlayer.currentIndex)

        btnClear.setOnClickListener {
            audioPlayer.clearQueue()
            onQueueCleared?.invoke()
            dialog.dismiss()
            Toast.makeText(context, "Queue cleared", Toast.LENGTH_SHORT).show()
        }

        btnSyncPeer?.setOnClickListener {
            val ip = getTargetPeerIp()
            val port = getTargetPeerPort()
            val name = getTargetPeerName() ?: "$ip:$port"

            if (ip.isBlank() || ip == "0.0.0.0") {
                Toast.makeText(context, "No peer device configured. Select a peer first on Sync tab.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val songs = q.map { it.song }
            Toast.makeText(context, "Syncing queue (${songs.size} tracks) to $name...", Toast.LENGTH_SHORT).show()

            scope.launch {
                val success = apiClient.sessionQueueSync(ip, port, songs)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(context, "Successfully synced queue to $name!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "Failed to sync queue to $name", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
