package com.aruncs.musicsync.ui.dialog

import android.app.Activity
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.data.PowerampImportReport
import com.aruncs.musicsync.model.Playlist
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.server.DiscoveredPeer
import com.aruncs.musicsync.server.PeerDiscoveryManager
import com.aruncs.musicsync.ui.adapter.AbsentSongsAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlaylistDialogsHelper {

    fun promptCreatePlaylistDialog(
        activity: Activity,
        scope: CoroutineScope,
        apiClient: DesktopApiClient,
        playlistManager: PlaylistManager,
        isRemote: Boolean,
        targetPeerIp: String,
        targetPeerPort: Int,
        onCreated: (Playlist) -> Unit
    ) {
        val input = EditText(activity).apply {
            hint = "Playlist Name (e.g. Chill, Workout)"
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_muted))
        }
        val container = FrameLayout(activity).apply {
            setPadding(48, 16, 48, 16)
            addView(input)
        }

        AlertDialog.Builder(activity)
            .setTitle(if (isRemote) "New Desktop Playlist" else "New Local Playlist")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (isRemote) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val pl = apiClient.createPlaylist(targetPeerIp, targetPeerPort, name)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(activity, "Created playlist: $name", Toast.LENGTH_SHORT).show()
                                    onCreated(pl)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        val pl = playlistManager.createPlaylist(name)
                        Toast.makeText(activity, "Created playlist: $name", Toast.LENGTH_SHORT).show()
                        onCreated(pl)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showAddToPlaylistDialog(
        activity: Activity,
        scope: CoroutineScope,
        song: Song,
        isRemote: Boolean,
        targetPeerIp: String,
        targetPeerPort: Int,
        apiClient: DesktopApiClient,
        playlistManager: PlaylistManager,
        onPlaylistCreated: ((Playlist) -> Unit)? = null
    ) {
        if (isRemote) {
            scope.launch(Dispatchers.IO) {
                val playlists = try {
                    apiClient.fetchPlaylists(targetPeerIp, targetPeerPort)
                } catch (e: Exception) {
                    emptyList()
                }

                withContext(Dispatchers.Main) {
                    val names = mutableListOf("+ Create New Playlist")
                    names.addAll(playlists.map { "${it.name} (${it.trackCount} tracks)" })

                    AlertDialog.Builder(activity)
                        .setTitle("Add to Desktop Playlist")
                        .setItems(names.toTypedArray()) { _, which ->
                            if (which == 0) {
                                promptCreatePlaylistDialog(
                                    activity = activity,
                                    scope = scope,
                                    apiClient = apiClient,
                                    playlistManager = playlistManager,
                                    isRemote = true,
                                    targetPeerIp = targetPeerIp,
                                    targetPeerPort = targetPeerPort
                                ) { newPl ->
                                    scope.launch(Dispatchers.IO) {
                                        apiClient.addTrackToPlaylist(targetPeerIp, targetPeerPort, newPl.id, song.filepath)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(activity, "Added to ${newPl.name}", Toast.LENGTH_SHORT).show()
                                            onPlaylistCreated?.invoke(newPl)
                                        }
                                    }
                                }
                            } else {
                                val selectedPl = playlists[which - 1]
                                scope.launch(Dispatchers.IO) {
                                    val ok = apiClient.addTrackToPlaylist(targetPeerIp, targetPeerPort, selectedPl.id, song.filepath)
                                    withContext(Dispatchers.Main) {
                                        if (ok) {
                                            Toast.makeText(activity, "Added to ${selectedPl.name}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(activity, "Failed to add track", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        } else {
            val localLists = playlistManager.getLocalPlaylists()
            val names = mutableListOf("+ Create New Playlist")
            names.addAll(localLists.map { "${it.name} (${it.trackCount} tracks)" })

            AlertDialog.Builder(activity)
                .setTitle("Add to Local Playlist")
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        promptCreatePlaylistDialog(
                            activity = activity,
                            scope = scope,
                            apiClient = apiClient,
                            playlistManager = playlistManager,
                            isRemote = false,
                            targetPeerIp = targetPeerIp,
                            targetPeerPort = targetPeerPort
                        ) { newPl ->
                            playlistManager.addTrack(newPl.id, song.filepath)
                            Toast.makeText(activity, "Added to ${newPl.name}", Toast.LENGTH_SHORT).show()
                            onPlaylistCreated?.invoke(newPl)
                        }
                    } else {
                        val selectedPl = localLists[which - 1]
                        val added = playlistManager.addTrack(selectedPl.id, song.filepath)
                        if (added) {
                            Toast.makeText(activity, "Added to ${selectedPl.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(activity, "Already in playlist", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun showAbsentSongsDialog(
        activity: Activity,
        report: PowerampImportReport,
        localSongs: List<Song>,
        playlistManager: PlaylistManager,
        onRefreshPlaylists: () -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_absent_songs, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.tv_stat_playlists)?.text = report.totalPlaylists.toString()
        dialogView.findViewById<TextView>(R.id.tv_stat_matched)?.text = report.matchedCount.toString()
        dialogView.findViewById<TextView>(R.id.tv_stat_absent)?.text = report.absentCount.toString()
        dialogView.findViewById<TextView>(R.id.tv_stat_liked)?.text = report.likedCount.toString()

        val tvSectionTitle = dialogView.findViewById<TextView>(R.id.tv_absent_section_title)
        tvSectionTitle?.text = "Absent Songs (${report.absentCount})"

        val rvAbsent = dialogView.findViewById<RecyclerView>(R.id.rv_absent_songs)

        val adapter = AbsentSongsAdapter(
            localSongs = localSongs,
            onUseMatch = { absentSong, matchedSong ->
                playlistManager.syncRemotePlaylist(
                    name = absentSong.playlistName,
                    trackFilepaths = listOf(matchedSong.filepath)
                )
                Toast.makeText(
                    activity,
                    "Added \"${matchedSong.title.ifBlank { matchedSong.filename }}\" → ${absentSong.playlistName}",
                    Toast.LENGTH_SHORT
                ).show()
                onRefreshPlaylists()
            }
        )
        rvAbsent?.layoutManager = LinearLayoutManager(activity)
        rvAbsent?.adapter = adapter
        adapter.submitList(report.absentSongs)

        val etSearch = dialogView.findViewById<EditText>(R.id.et_absent_search)
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
                tvSectionTitle?.text = "Absent Songs (${adapter.getDisplayedCount()})"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialogView.findViewById<View>(R.id.btn_dialog_absent_close)?.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<View>(R.id.btn_share_absent_report)?.setOnClickListener {
            val sb = StringBuilder()
            sb.append("================================================================================\n")
            sb.append(" POWERAMP PLAYLIST IMPORT - ABSENT SONGS REPORT\n")
            sb.append(" Total Absent Songs: ${report.absentCount}\n")
            sb.append(" Matched Songs: ${report.matchedCount} / ${report.totalTracks}\n")
            sb.append("================================================================================\n\n")

            val grouped = report.absentSongs.groupBy { it.playlistName }
            grouped.forEach { (plName, items) ->
                sb.append("Playlist: $plName (${items.size} missing)\n")
                sb.append("------------------------------------------------------------\n")
                items.forEach { item ->
                    val title = if (item.readableName.isNotBlank()) item.readableName else item.filename
                    sb.append("  • $title\n")
                    if (item.originalPath.isNotBlank() && item.originalPath != title) {
                        sb.append("    Path: ${item.originalPath}\n")
                    }
                }
                sb.append("\n")
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Poweramp Absent Songs Report")
                putExtra(Intent.EXTRA_TEXT, sb.toString())
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Share Absent Songs Report"))
        }

        dialog.show()
    }

    fun showPeerSelectionDialog(
        activity: Activity,
        scope: CoroutineScope,
        currentIp: String,
        currentPort: Int,
        onLog: (String) -> Unit,
        onPeerSelected: (ip: String, port: Int, name: String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_peer_selection, null)
        val pbScanning = dialogView.findViewById<ProgressBar>(R.id.pb_peer_scanning)
        val btnRescan = dialogView.findViewById<TextView>(R.id.btn_peer_rescan)
        val layoutDiscovered = dialogView.findViewById<LinearLayout>(R.id.layout_discovered_peers)
        val tvNoPeers = dialogView.findViewById<TextView>(R.id.tv_no_peers_found)
        val etManualIp = dialogView.findViewById<EditText>(R.id.et_peer_manual_ip)
        val etManualPort = dialogView.findViewById<EditText>(R.id.et_peer_manual_port)

        etManualIp.setText(currentIp)
        etManualPort.setText(currentPort.toString())

        var dialog: AlertDialog? = null

        fun addPeerItem(peer: DiscoveredPeer) {
            val itemView = LayoutInflater.from(activity).inflate(android.R.layout.simple_list_item_2, layoutDiscovered, false)
            val text1 = itemView.findViewById<TextView>(android.R.id.text1)
            val text2 = itemView.findViewById<TextView>(android.R.id.text2)
            text1.text = peer.displayName
            text1.setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            text1.setTypeface(null, android.graphics.Typeface.BOLD)
            text2.text = "${peer.ip}:${peer.port}"
            text2.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))

            itemView.setPadding(24, 16, 24, 16)
            itemView.setBackgroundResource(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            itemView.layoutParams = lp

            itemView.setOnClickListener {
                onPeerSelected(peer.ip, peer.port, peer.hostname)
                dialog?.dismiss()
                Toast.makeText(activity, "Connected to ${peer.hostname}", Toast.LENGTH_SHORT).show()
            }

            tvNoPeers.visibility = View.GONE
            layoutDiscovered.addView(itemView)
        }

        fun scanForPeers() {
            pbScanning.visibility = View.VISIBLE
            layoutDiscovered.removeAllViews()
            tvNoPeers.text = "Scanning Wi-Fi / Hotspot for devices..."
            tvNoPeers.visibility = View.VISIBLE
            layoutDiscovered.addView(tvNoPeers)

            scope.launch(Dispatchers.IO) {
                val peers = PeerDiscoveryManager.discoverAllPeers(
                    context = activity,
                    timeoutMs = 2500,
                    onPeerFound = { peer ->
                        activity.runOnUiThread {
                            addPeerItem(peer)
                        }
                    },
                    onLog = { msg ->
                        activity.runOnUiThread { onLog(msg) }
                    }
                )

                withContext(Dispatchers.Main) {
                    pbScanning.visibility = View.GONE
                    if (peers.isEmpty()) {
                        tvNoPeers.text = "No devices detected automatically.\nEnsure both devices are on the same Wi-Fi or Hotspot,\nand the Music Sync server is started on the other device."
                        tvNoPeers.visibility = View.VISIBLE
                    }
                }
            }
        }

        btnRescan.setOnClickListener {
            scanForPeers()
        }

        dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setPositiveButton("Connect Manual IP") { _, _ ->
                val ip = etManualIp.text.toString().trim()
                val port = etManualPort.text.toString().toIntOrNull() ?: 5000
                if (ip.isNotEmpty()) {
                    onPeerSelected(ip, port, "Manual Peer")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        scanForPeers()
    }
}
