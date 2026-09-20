package com.aruncs.musicsync.ui.controller

import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.client.SyncManager
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.data.PowerampImporter
import com.aruncs.musicsync.model.Playlist
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.PlayableItem
import com.aruncs.musicsync.ui.adapter.PlaylistsAdapter
import com.aruncs.musicsync.ui.adapter.SongsAdapter
import com.aruncs.musicsync.ui.dialog.PlaylistDialogsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PlaylistsTabController(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val playlistManager: PlaylistManager,
    private val apiClient: DesktopApiClient,
    private val syncManager: SyncManager,
    private val audioPlayer: AudioPlayer,
    private val getTargetPeerIp: () -> String,
    private val getTargetPeerPort: () -> Int,
    private val getActivePeerName: () -> String?,
    private val setActivePeer: (ip: String, port: Int, name: String) -> Unit,
    private val createPlayableItem: (Song, Boolean) -> PlayableItem,
    private val getLocalSongs: () -> List<Song>,
    private val onRefreshLocalLibrary: () -> Unit,
    private val onShowAudioInfo: (Song) -> Unit,
    private val onShowSongOptions: (Song) -> Unit,
    private val onLog: (String) -> Unit,
    private val onLaunchPowerampPicker: () -> Unit
) {
    companion object {
        const val MODE_LOCAL = 0
        const val MODE_REMOTE = 1
    }

    var playlistMode: Int = MODE_LOCAL
        private set

    var localPlaylists: List<Playlist> = emptyList()
        private set
    var remotePlaylists: List<Playlist> = emptyList()
        private set
    var selectedPlaylist: Playlist? = null
        private set
    var playlistTracks: List<Song> = emptyList()
        private set

    lateinit var playlistsAdapter: PlaylistsAdapter
        private set
    lateinit var playlistDetailAdapter: SongsAdapter
        private set

    // Views
    private var badgePlaylistsCount: TextView? = null
    private var btnPlaylistImportPoweramp: TextView? = null
    private var btnPlaylistCreate: TextView? = null
    private var btnPlaylistsRefresh: ImageButton? = null
    private var btnPlaylistTabLocal: TextView? = null
    private var btnPlaylistTabRemote: TextView? = null
    private var layoutPeerSelectorBar: LinearLayout? = null
    private var tvActivePeerInfo: TextView? = null
    private var btnChangePeer: TextView? = null
    private var layoutPlaylistsMain: LinearLayout? = null
    private var tvPlaylistsEmpty: TextView? = null
    private var swipeRefreshPlaylists: SwipeRefreshLayout? = null
    private var rvPlaylists: RecyclerView? = null
    private var layoutPlaylistDetail: LinearLayout? = null
    private var btnDetailBack: TextView? = null
    private var tvDetailPlaylistName: TextView? = null
    private var btnDetailPlayAll: TextView? = null
    private var btnDetailSyncPlaylist: TextView? = null
    private var tvDetailEmpty: TextView? = null
    private var rvPlaylistTracks: RecyclerView? = null

    val isDetailOpen: Boolean
        get() = layoutPlaylistDetail?.visibility == View.VISIBLE

    fun init(playlistsView: View) {
        badgePlaylistsCount = playlistsView.findViewById(R.id.badge_playlists_count)
        btnPlaylistImportPoweramp = playlistsView.findViewById(R.id.btn_playlist_import_poweramp)
        btnPlaylistCreate = playlistsView.findViewById(R.id.btn_playlist_create)
        btnPlaylistsRefresh = playlistsView.findViewById(R.id.btn_playlists_refresh)
        btnPlaylistTabLocal = playlistsView.findViewById(R.id.btn_playlist_tab_local)
        btnPlaylistTabRemote = playlistsView.findViewById(R.id.btn_playlist_tab_remote)
        layoutPeerSelectorBar = playlistsView.findViewById(R.id.layout_peer_selector_bar)
        tvActivePeerInfo = playlistsView.findViewById(R.id.tv_active_peer_info)
        btnChangePeer = playlistsView.findViewById(R.id.btn_change_peer)
        layoutPlaylistsMain = playlistsView.findViewById(R.id.layout_playlists_main)
        tvPlaylistsEmpty = playlistsView.findViewById(R.id.tv_playlists_empty)
        swipeRefreshPlaylists = playlistsView.findViewById(R.id.swipe_refresh_playlists)
        rvPlaylists = playlistsView.findViewById(R.id.rv_playlists)
        layoutPlaylistDetail = playlistsView.findViewById(R.id.layout_playlist_detail)
        btnDetailBack = playlistsView.findViewById(R.id.btn_detail_back)
        tvDetailPlaylistName = playlistsView.findViewById(R.id.tv_detail_playlist_name)
        btnDetailSyncPlaylist = playlistsView.findViewById(R.id.btn_detail_sync_playlist)
        btnDetailPlayAll = playlistsView.findViewById(R.id.btn_detail_play_all)
        tvDetailEmpty = playlistsView.findViewById(R.id.tv_detail_empty)
        rvPlaylistTracks = playlistsView.findViewById(R.id.rv_playlist_tracks)

        playlistsAdapter = PlaylistsAdapter(
            onPlaylistClick = { playlist -> openPlaylistDetail(playlist) },
            onPlayClick = { playlist -> playPlaylist(playlist) },
            onDeleteClick = { playlist -> confirmDeletePlaylist(playlist) },
            onSyncClick = { playlist -> syncRemotePlaylistWithQuality(playlist) }
        )
        rvPlaylists?.layoutManager = LinearLayoutManager(activity)
        rvPlaylists?.adapter = playlistsAdapter

        playlistDetailAdapter = SongsAdapter(
            onPlayClick = { song ->
                val tracks = playlistTracks
                val startIndex = tracks.indexOfFirst { it.filepath == song.filepath }.coerceAtLeast(0)
                val isRemote = selectedPlaylist?.isRemote == true
                val items = tracks.map { s -> createPlayableItem(s, isRemote) }
                audioPlayer.setQueue(items, startIndex)
            },
            onDeleteClick = { song ->
                selectedPlaylist?.let { pl -> confirmRemoveTrackFromPlaylist(pl, song) }
            },
            onAddToQueueClick = { song ->
                val isRemote = selectedPlaylist?.isRemote == true
                val item = createPlayableItem(song, isRemote)
                audioPlayer.addToQueue(item.song, item.streamUrl)
                Toast.makeText(activity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onLikeClick = { song ->
                toggleSongLiked(song)
            },
            onMoreOptionsClick = { song ->
                onShowSongOptions(song)
            }
        )
        rvPlaylistTracks?.layoutManager = LinearLayoutManager(activity)
        rvPlaylistTracks?.adapter = playlistDetailAdapter

        btnPlaylistTabLocal?.setOnClickListener { switchPlaylistMode(MODE_LOCAL) }
        btnPlaylistTabRemote?.setOnClickListener { switchPlaylistMode(MODE_REMOTE) }
        btnPlaylistImportPoweramp?.setOnClickListener { onLaunchPowerampPicker() }
        btnPlaylistCreate?.setOnClickListener {
            PlaylistDialogsHelper.promptCreatePlaylistDialog(
                activity = activity,
                scope = scope,
                apiClient = apiClient,
                playlistManager = playlistManager,
                isRemote = playlistMode == MODE_REMOTE,
                targetPeerIp = getTargetPeerIp(),
                targetPeerPort = getTargetPeerPort(),
                onCreated = { loadPlaylists() }
            )
        }
        btnPlaylistsRefresh?.setOnClickListener { loadPlaylists() }
        swipeRefreshPlaylists?.setOnRefreshListener { loadPlaylists() }
        btnChangePeer?.setOnClickListener { showPeerSelectionDialog() }
        btnDetailBack?.setOnClickListener { closePlaylistDetail() }
        btnDetailPlayAll?.setOnClickListener { selectedPlaylist?.let { pl -> playPlaylist(pl) } }
        btnDetailSyncPlaylist?.setOnClickListener { selectedPlaylist?.let { pl -> syncRemotePlaylistWithQuality(pl) } }
    }

    fun updatePeerBarText() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val name = getActivePeerName() ?: "Peer"
        tvActivePeerInfo?.text = "$name: $ip:$port"
    }

    fun switchPlaylistMode(mode: Int) {
        playlistMode = mode
        val muted = ContextCompat.getColor(activity, R.color.text_muted)
        val black = ContextCompat.getColor(activity, R.color.black)

        closePlaylistDetail()

        if (mode == MODE_LOCAL) {
            btnPlaylistTabLocal?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnPlaylistTabLocal?.setTextColor(black)
            btnPlaylistTabRemote?.background = null
            btnPlaylistTabRemote?.setTextColor(muted)
            layoutPeerSelectorBar?.visibility = View.GONE
            loadLocalPlaylists()
        } else {
            btnPlaylistTabRemote?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnPlaylistTabRemote?.setTextColor(black)
            btnPlaylistTabLocal?.background = null
            btnPlaylistTabLocal?.setTextColor(muted)
            layoutPeerSelectorBar?.visibility = View.VISIBLE
            updatePeerBarText()
            loadRemotePlaylists()
        }
    }

    fun loadPlaylists() {
        if (playlistMode == MODE_LOCAL) {
            loadLocalPlaylists()
        } else {
            loadRemotePlaylists()
        }
    }

    fun loadLocalPlaylists() {
        swipeRefreshPlaylists?.isRefreshing = true
        val localSongs = getLocalSongs()
        val userLists = playlistManager.getLocalPlaylists()
        val likedSongs = playlistManager.getLikedSongs(localSongs)
        val likedPlaylist = Playlist(
            id = -1L,
            name = "Liked Music",
            trackCount = likedSongs.size,
            isRemote = false,
            trackFilepaths = likedSongs.map { it.filepath }
        )
        val allLists = listOf(likedPlaylist) + userLists
        localPlaylists = allLists
        playlistsAdapter.submitList(allLists)
        badgePlaylistsCount?.text = "${allLists.size} playlists"
        tvPlaylistsEmpty?.visibility = View.GONE
        swipeRefreshPlaylists?.isRefreshing = false
    }

    fun loadRemotePlaylists() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()

        if (ip.isEmpty() || ip == "0.0.0.0") {
            tvPlaylistsEmpty?.text = "No peer device configured. Tap 'Select Peer' above to discover devices."
            tvPlaylistsEmpty?.visibility = View.VISIBLE
            playlistsAdapter.submitList(emptyList())
            badgePlaylistsCount?.text = "0 playlists"
            return
        }

        updatePeerBarText()
        swipeRefreshPlaylists?.isRefreshing = true
        tvPlaylistsEmpty?.visibility = View.GONE

        scope.launch {
            try {
                val lists = apiClient.fetchPlaylists(ip, port)
                remotePlaylists = lists
                activity.runOnUiThread {
                    playlistsAdapter.submitList(lists)
                    badgePlaylistsCount?.text = "${lists.size} playlists"
                    val peerLabel = getActivePeerName() ?: "$ip:$port"
                    tvPlaylistsEmpty?.text = "No playlists found on $peerLabel.\nTap '+ New' to create one."
                    tvPlaylistsEmpty?.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
                    swipeRefreshPlaylists?.isRefreshing = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    swipeRefreshPlaylists?.isRefreshing = false
                    val peerLabel = getActivePeerName() ?: "$ip:$port"
                    tvPlaylistsEmpty?.text = "Could not reach $peerLabel.\n${e.message}\nTap 'Select Peer' to choose another device."
                    tvPlaylistsEmpty?.visibility = View.VISIBLE
                    Toast.makeText(activity, "Failed to load remote playlists: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openPlaylistDetail(playlist: Playlist) {
        selectedPlaylist = playlist
        layoutPlaylistsMain?.visibility = View.GONE
        layoutPlaylistDetail?.visibility = View.VISIBLE
        tvDetailPlaylistName?.text = playlist.name
        tvDetailEmpty?.visibility = View.GONE

        val localSongs = getLocalSongs()

        if (playlist.isRemote) {
            btnDetailSyncPlaylist?.visibility = View.VISIBLE
            val ip = getTargetPeerIp()
            val port = getTargetPeerPort()
            scope.launch {
                try {
                    val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id, playlist.name)
                    playlistTracks = tracks
                    activity.runOnUiThread {
                        val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
                        playlistDetailAdapter.setRemoteMode(true, localNames)
                        playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                        playlistDetailAdapter.submitList(tracks)
                        tvDetailEmpty?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Failed to load tracks: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (playlist.id == -1L) {
            btnDetailSyncPlaylist?.visibility = View.GONE
            val tracks = playlistManager.getLikedSongs(localSongs)
            playlistTracks = tracks
            playlistDetailAdapter.setRemoteMode(false)
            playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
            playlistDetailAdapter.submitList(tracks)
            tvDetailEmpty?.text = "No liked songs yet.\nTap Favorite on any song to add it here."
            tvDetailEmpty?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        } else {
            btnDetailSyncPlaylist?.visibility = View.GONE
            val tracks = playlistManager.getPlaylistSongs(playlist, localSongs)
            playlistTracks = tracks
            playlistDetailAdapter.setRemoteMode(false)
            playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
            playlistDetailAdapter.submitList(tracks)
            tvDetailEmpty?.text = "No tracks in this playlist"
            tvDetailEmpty?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    fun closePlaylistDetail() {
        selectedPlaylist = null
        playlistTracks = emptyList()
        btnDetailSyncPlaylist?.visibility = View.GONE
        layoutPlaylistDetail?.visibility = View.GONE
        layoutPlaylistsMain?.visibility = View.VISIBLE
    }

    fun playPlaylist(playlist: Playlist) {
        val localSongs = getLocalSongs()
        if (playlist.isRemote) {
            val ip = getTargetPeerIp()
            val port = getTargetPeerPort()
            scope.launch {
                try {
                    val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id, playlist.name)
                    if (tracks.isEmpty()) {
                        activity.runOnUiThread { Toast.makeText(activity, "Playlist is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val items = tracks.map { s -> createPlayableItem(s, true) }
                    activity.runOnUiThread {
                        audioPlayer.setQueue(items, 0)
                        Toast.makeText(activity, "Playing \"${playlist.name}\" (${items.size} tracks)", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread { Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        } else if (playlist.id == -1L) {
            val tracks = playlistManager.getLikedSongs(localSongs)
            if (tracks.isEmpty()) {
                Toast.makeText(activity, "Liked Music is empty", Toast.LENGTH_SHORT).show()
                return
            }
            val items = tracks.map { s -> PlayableItem(s, null) }
            audioPlayer.setQueue(items, 0)
            Toast.makeText(activity, "Playing Liked Music (${items.size} tracks)", Toast.LENGTH_SHORT).show()
        } else {
            val tracks = playlistManager.getPlaylistSongs(playlist, localSongs)
            if (tracks.isEmpty()) {
                Toast.makeText(activity, "Playlist is empty or tracks missing", Toast.LENGTH_SHORT).show()
                return
            }
            val items = tracks.map { s -> PlayableItem(s, null) }
            audioPlayer.setQueue(items, 0)
            Toast.makeText(activity, "Playing \"${playlist.name}\" (${items.size} tracks)", Toast.LENGTH_SHORT).show()
        }
    }

    fun confirmDeletePlaylist(playlist: Playlist) {
        if (playlist.id == -1L) {
            Toast.makeText(activity, "Cannot delete Liked Music playlist", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete playlist \"${playlist.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                if (playlist.isRemote) {
                    val ip = getTargetPeerIp()
                    val port = getTargetPeerPort()
                    scope.launch {
                        try {
                            apiClient.deletePlaylist(ip, port, playlist.id)
                            activity.runOnUiThread {
                                loadRemotePlaylists()
                                Toast.makeText(activity, "Deleted \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    playlistManager.deletePlaylist(playlist.id)
                    loadLocalPlaylists()
                    Toast.makeText(activity, "Deleted \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirmRemoveTrackFromPlaylist(playlist: Playlist, song: Song) {
        val title = if (playlist.id == -1L) "Unlike Song" else "Remove Track"
        val msg = if (playlist.id == -1L) "Remove \"${song.title}\" from Liked Music?" else "Remove \"${song.title}\" from ${playlist.name}?"

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Remove") { _, _ ->
                if (playlist.isRemote) {
                    val ip = getTargetPeerIp()
                    val port = getTargetPeerPort()
                    scope.launch {
                        try {
                            apiClient.removeTrackFromPlaylist(ip, port, playlist.id, song.filepath)
                            activity.runOnUiThread {
                                openPlaylistDetail(playlist)
                                Toast.makeText(activity, "Removed from ${playlist.name}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else if (playlist.id == -1L) {
                    playlistManager.toggleLike(song)
                    playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                    openPlaylistDetail(playlist)
                    Toast.makeText(activity, "Removed from Liked Music", Toast.LENGTH_SHORT).show()
                } else {
                    playlistManager.removeTrack(playlist.id, song.filepath)
                    val updatedPl = playlistManager.getPlaylist(playlist.id) ?: playlist
                    openPlaylistDetail(updatedPl)
                    Toast.makeText(activity, "Removed from ${playlist.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showPeerSelectionDialog() {
        PlaylistDialogsHelper.showPeerSelectionDialog(
            activity = activity,
            scope = scope,
            currentIp = getTargetPeerIp(),
            currentPort = getTargetPeerPort(),
            onLog = onLog,
            onPeerSelected = { ip, port, name ->
                setActivePeer(ip, port, name)
                updatePeerBarText()
                loadRemotePlaylists()
            }
        )
    }

    fun syncRemotePlaylistWithQuality(playlist: Playlist) {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()

        if (ip.isEmpty() || ip == "0.0.0.0") {
            Toast.makeText(activity, "No peer configured. Select a peer device first.", Toast.LENGTH_SHORT).show()
            showPeerSelectionDialog()
            return
        }

        val qualityOptions = arrayOf(
            "Original (Lossless / As-is)",
            "192 kbps AAC (Recommended - High Quality)",
            "128 kbps AAC (Space Saver)",
            "256 kbps AAC (Audiophile Quality)",
            "320 kbps AAC (Maximum Bitrate)"
        )
        val bitrateValues = arrayOf("original", "192k", "128k", "256k", "320k")
        val peerDisplay = getActivePeerName() ?: "$ip:$port"

        AlertDialog.Builder(activity)
            .setTitle("Sync \"${playlist.name}\"")
            .setMessage("Syncing from $peerDisplay.\nDownloads missing tracks directly and exports standard .m3u8 for Poweramp.\n\nChoose audio quality:")
            .setItems(qualityOptions) { _, which ->
                val selectedBitrate = bitrateValues[which]
                executePlaylistSync(playlist, ip, port, selectedBitrate)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executePlaylistSync(playlist: Playlist, ip: String, port: Int, bitrate: String) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Syncing \"${playlist.name}\"")
            .setMessage("Connecting to peer ($ip:$port)...")
            .setCancelable(false)
            .setNegativeButton("Hide") { d, _ -> d.dismiss() }
            .create()
        progressDialog.show()

        scope.launch {
            try {
                val (downloadedCount, m3uFile) = syncManager.syncPlaylistFromPeer(
                    peerIp = ip,
                    peerPort = port,
                    playlist = playlist,
                    targetBitrate = bitrate,
                    onProgress = { current, total, song, percent, _ ->
                        activity.runOnUiThread {
                            progressDialog.setMessage("Downloading ($current/$total) [$percent%]:\n${song.title.ifBlank { song.filename }}")
                        }
                    },
                    onLog = { msg -> activity.runOnUiThread { onLog(msg) } }
                )

                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    onRefreshLocalLibrary()
                    switchPlaylistMode(MODE_LOCAL)
                    loadLocalPlaylists()

                    val exportMsg = if (m3uFile != null) "\nPoweramp playlist exported: ${m3uFile.name}" else ""
                    val summary = "Synced \"${playlist.name}\": $downloadedCount track(s) downloaded.$exportMsg"
                    Toast.makeText(activity, summary, Toast.LENGTH_LONG).show()
                    onLog("[PLAYLIST-SYNC-SUCCESS] $summary")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    Toast.makeText(activity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                    onLog("[PLAYLIST-SYNC-ERR] ${e.message}")
                }
            }
        }
    }

    fun handlePowerampImport(uri: Uri) {
        Toast.makeText(activity, "Importing Poweramp backup...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val importer = PowerampImporter(activity.applicationContext, playlistManager)
                val report = importer.importFromUri(uri, getLocalSongs())
                withContext(Dispatchers.Main) {
                    loadPlaylists()
                    Toast.makeText(
                        activity,
                        "Imported ${report.totalPlaylists} playlists! ${report.matchedCount} songs matched.",
                        Toast.LENGTH_LONG
                    ).show()
                    PlaylistDialogsHelper.showAbsentSongsDialog(
                        activity = activity,
                        report = report,
                        localSongs = getLocalSongs(),
                        playlistManager = playlistManager,
                        onRefreshPlaylists = { loadPlaylists() }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        "Poweramp import failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun toggleSongLiked(song: Song) {
        val nowLiked = playlistManager.toggleLike(song)
        playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
        val msg = if (nowLiked) "Added to Liked Music" else "Removed from Liked Music"
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        if (playlistMode == MODE_LOCAL) {
            loadLocalPlaylists()
        }
    }
}
