package com.aruncs.musicsync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.client.SyncManager
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.Playlist
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.PlayableItem
import com.aruncs.musicsync.server.NetworkUtils
import com.aruncs.musicsync.server.PeerDiscoveryManager
import com.aruncs.musicsync.server.SyncForegroundService
import com.aruncs.musicsync.ui.adapter.LogAdapter
import com.aruncs.musicsync.ui.adapter.PlaylistsAdapter
import com.aruncs.musicsync.ui.adapter.SongsAdapter
import android.graphics.Color
import com.aruncs.musicsync.data.MediaScannerHelper
import com.aruncs.musicsync.player.RepeatMode
import com.aruncs.musicsync.ui.adapter.QueueAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.aruncs.musicsync.data.PowerampImporter
import com.aruncs.musicsync.data.PowerampImportReport
import com.aruncs.musicsync.ui.adapter.AbsentSongsAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var syncManager: SyncManager
    private lateinit var playlistManager: PlaylistManager
    private val audioPlayer = AudioPlayer()
    private val apiClient = DesktopApiClient()

    private val openPowerampDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importPowerampBackup(it) }
    }

    // Container for active tab view
    private lateinit var layoutContent: FrameLayout
    private lateinit var tvMainIpBadge: TextView

    // Bottom Navigation (4 Tabs: Sync, Library, Playlists, Logs)
    private lateinit var navTabSync: LinearLayout
    private lateinit var navTabLibrary: LinearLayout
    private lateinit var navTabPlaylists: LinearLayout
    private lateinit var navTabLogs: LinearLayout
    private lateinit var ivTabSync: ImageView
    private lateinit var tvTabSync: TextView
    private lateinit var ivTabLibrary: ImageView
    private lateinit var tvTabLibrary: TextView
    private lateinit var ivTabPlaylists: ImageView
    private lateinit var tvTabPlaylists: TextView
    private lateinit var ivTabLogs: ImageView
    private lateinit var tvTabLogs: TextView

    // Bottom Player Bar
    private lateinit var playerBarContainer: View
    private var btnPlayerShuffle: ImageButton? = null
    private var btnPlayerPrev: ImageButton? = null
    private var btnPlayerPlayPause: ImageButton? = null
    private var btnPlayerNext: ImageButton? = null
    private var btnPlayerRepeat: ImageButton? = null
    private var btnPlayerLike: ImageButton? = null
    private var btnPlayerQueue: ImageButton? = null
    private var tvPlayerTitle: TextView? = null
    private var tvPlayerArtist: TextView? = null
    private var tvPlayerTime: TextView? = null
    private var playerSeekbar: SeekBar? = null
    private var btnPlayerClose: ImageButton? = null

    // Cached views for tabs
    private lateinit var syncView: View
    private lateinit var libraryView: View
    private lateinit var playlistsView: View
    private lateinit var logsView: View

    // Sync Tab Views
    private var badgeServerStatus: TextView? = null
    private var tvServerAddress: TextView? = null
    private var btnToggleServer: Button? = null
    private var etDesktopIp: EditText? = null
    private var etDesktopPort: EditText? = null
    private var btnAutoDiscover: Button? = null
    private var btnPingDesktop: Button? = null
    private var tvDesktopStatus: TextView? = null
    private var btnSyncPull: Button? = null
    private var btnSyncPush: Button? = null
    private var layoutSyncProgress: LinearLayout? = null
    private var tvSyncStatusLabel: TextView? = null
    private var tvSyncPercent: TextView? = null
    private var pbSync: ProgressBar? = null
    private var tvSyncDetail: TextView? = null

    // Library Tab Views
    private var rvLibrarySongs: RecyclerView? = null
    private var swipeRefreshLibrary: SwipeRefreshLayout? = null
    private var etLibrarySearch: EditText? = null
    private var tvLibraryEmpty: TextView? = null
    private var badgeLibraryCount: TextView? = null
    private var btnLibraryRefresh: ImageButton? = null
    private var btnTabLocal: TextView? = null
    private var btnTabRemote: TextView? = null
    private var btnLibraryQuality: TextView? = null
    private var layoutFilterChips: LinearLayout? = null
    private var chipFilterAll: TextView? = null
    private var chipFilterDownloaded: TextView? = null
    private var chipFilterNotDownloaded: TextView? = null
    private var chipFilterLiked: TextView? = null
    private lateinit var songsAdapter: SongsAdapter

    // Playlists Tab Views
    private var badgePlaylistsCount: TextView? = null
    private var btnPlaylistImportPoweramp: TextView? = null
    private var btnPlaylistCreate: TextView? = null
    private var btnPlaylistsRefresh: ImageButton? = null
    private var btnPlaylistTabLocal: TextView? = null
    private var btnPlaylistTabRemote: TextView? = null
    private var layoutPlaylistsMain: LinearLayout? = null
    private var tvPlaylistsEmpty: TextView? = null
    private var swipeRefreshPlaylists: SwipeRefreshLayout? = null
    private var rvPlaylists: RecyclerView? = null
    private var layoutPlaylistDetail: LinearLayout? = null
    private var btnDetailBack: TextView? = null
    private var tvDetailPlaylistName: TextView? = null
    private var btnDetailPlayAll: TextView? = null
    private var tvDetailEmpty: TextView? = null
    private var rvPlaylistTracks: RecyclerView? = null
    private lateinit var playlistsAdapter: PlaylistsAdapter
    private lateinit var playlistDetailAdapter: SongsAdapter

    // Logs Tab Views
    private var rvLogs: RecyclerView? = null
    private var btnLogsClear: Button? = null
    private val logAdapter = LogAdapter()

    private var currentTab = TAB_SYNC
    private var libraryMode = MODE_LOCAL
    private var playlistMode = MODE_LOCAL
    private var localSongs: List<Song> = emptyList()
    private var remoteSongs: List<Song> = emptyList()
    private var localPlaylists: List<Playlist> = emptyList()
    private var remotePlaylists: List<Playlist> = emptyList()
    private var selectedPlaylist: Playlist? = null
    private var playlistTracks: List<Song> = emptyList()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2001
        private const val TAB_SYNC = 0
        private const val TAB_LIBRARY = 1
        private const val TAB_PLAYLISTS = 2
        private const val TAB_LOGS = 3
        private const val MODE_LOCAL = 0
        private const val MODE_REMOTE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        syncManager = SyncManager(this)
        playlistManager = PlaylistManager(this)

        initViews()
        initPlayerBar()
        initTabViews()
        setupListeners()
        checkPermissions()
        updateServerStatusUI(SyncForegroundService.isRunning, null)
        refreshIpBadge()

        if (NetworkUtils.isWifiConnected(this)) {
            autoDiscoverDesktop(showToast = false)
        }
        PeerDiscoveryManager.startListener(this) { logMsg ->
            runOnUiThread { logAdapter.addLog(logMsg) }
        }

        switchTab(TAB_SYNC)
    }

    private fun initViews() {
        layoutContent = findViewById(R.id.layout_content)
        tvMainIpBadge = findViewById(R.id.tv_main_ip_badge)

        navTabSync = findViewById(R.id.nav_tab_sync)
        navTabLibrary = findViewById(R.id.nav_tab_library)
        navTabPlaylists = findViewById(R.id.nav_tab_playlists)
        navTabLogs = findViewById(R.id.nav_tab_logs)

        ivTabSync = findViewById(R.id.iv_tab_sync)
        tvTabSync = findViewById(R.id.tv_tab_sync)
        ivTabLibrary = findViewById(R.id.iv_tab_library)
        tvTabLibrary = findViewById(R.id.tv_tab_library)
        ivTabPlaylists = findViewById(R.id.iv_tab_playlists)
        tvTabPlaylists = findViewById(R.id.tv_tab_playlists)
        ivTabLogs = findViewById(R.id.iv_tab_logs)
        tvTabLogs = findViewById(R.id.tv_tab_logs)

        playerBarContainer = findViewById(R.id.player_bar_container)
        btnPlayerShuffle = findViewById(R.id.btn_player_shuffle)
        btnPlayerPrev = findViewById(R.id.btn_player_prev)
        btnPlayerPlayPause = findViewById(R.id.btn_player_play_pause)
        btnPlayerNext = findViewById(R.id.btn_player_next)
        btnPlayerRepeat = findViewById(R.id.btn_player_repeat)
        btnPlayerLike = findViewById(R.id.btn_player_like)
        btnPlayerQueue = findViewById(R.id.btn_player_queue)
        tvPlayerTitle = findViewById(R.id.tv_player_title)
        tvPlayerArtist = findViewById(R.id.tv_player_artist)
        tvPlayerTime = findViewById(R.id.tv_player_time)
        playerSeekbar = findViewById(R.id.player_seekbar)
        btnPlayerClose = findViewById(R.id.btn_player_close)
    }

    private fun initTabViews() {
        val inflater = LayoutInflater.from(this)

        // 1. Sync View
        syncView = inflater.inflate(R.layout.fragment_sync, layoutContent, false)
        badgeServerStatus = syncView.findViewById(R.id.badge_server_status)
        tvServerAddress = syncView.findViewById(R.id.tv_server_address)
        btnToggleServer = syncView.findViewById(R.id.btn_toggle_server)
        etDesktopIp = syncView.findViewById(R.id.et_desktop_ip)
        etDesktopPort = syncView.findViewById(R.id.et_desktop_port)
        btnAutoDiscover = syncView.findViewById(R.id.btn_auto_discover)
        btnPingDesktop = syncView.findViewById(R.id.btn_ping_desktop)
        tvDesktopStatus = syncView.findViewById(R.id.tv_desktop_status)
        btnSyncPull = syncView.findViewById(R.id.btn_sync_pull)
        btnSyncPush = syncView.findViewById(R.id.btn_sync_push)
        layoutSyncProgress = syncView.findViewById(R.id.layout_sync_progress)
        tvSyncStatusLabel = syncView.findViewById(R.id.tv_sync_status_label)
        tvSyncPercent = syncView.findViewById(R.id.tv_sync_percent)
        pbSync = syncView.findViewById(R.id.pb_sync)
        tvSyncDetail = syncView.findViewById(R.id.tv_sync_detail)

        etDesktopIp?.setText(prefs.desktopIp)
        etDesktopPort?.setText(prefs.desktopPort.toString())

        // 2. Library View
        libraryView = inflater.inflate(R.layout.fragment_library, layoutContent, false)
        rvLibrarySongs = libraryView.findViewById(R.id.rv_library_songs)
        swipeRefreshLibrary = libraryView.findViewById(R.id.swipe_refresh_library)
        etLibrarySearch = libraryView.findViewById(R.id.et_library_search)
        tvLibraryEmpty = libraryView.findViewById(R.id.tv_library_empty)
        badgeLibraryCount = libraryView.findViewById(R.id.badge_library_count)
        btnLibraryRefresh = libraryView.findViewById(R.id.btn_library_refresh)
        btnTabLocal = libraryView.findViewById(R.id.btn_tab_local)
        btnTabRemote = libraryView.findViewById(R.id.btn_tab_remote)
        btnLibraryQuality = libraryView.findViewById(R.id.btn_library_quality)
        layoutFilterChips = libraryView.findViewById(R.id.layout_filter_chips)
        chipFilterAll = libraryView.findViewById(R.id.chip_filter_all)
        chipFilterDownloaded = libraryView.findViewById(R.id.chip_filter_downloaded)
        chipFilterNotDownloaded = libraryView.findViewById(R.id.chip_filter_not_downloaded)
        chipFilterLiked = libraryView.findViewById(R.id.chip_filter_liked)

        chipFilterLiked?.setOnClickListener {
            setFilterChip(SongsAdapter.FILTER_LIKED)
        }

        songsAdapter = SongsAdapter(
            onPlayClick = { song ->
                if (audioPlayer.currentSong?.id == song.id) {
                    audioPlayer.togglePlayPause()
                } else {
                    val displayed = songsAdapter.getDisplayedSongs()
                    val startIndex = displayed.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                    val items = displayed.map { s ->
                        val url = if (libraryMode == MODE_REMOTE) getStreamUrl(s) else null
                        PlayableItem(s, url)
                    }
                    audioPlayer.setQueue(items, startIndex)
                }
            },
            onDownloadClick = { song ->
                downloadRemoteSong(song)
            },
            onDeleteClick = { song ->
                confirmDeleteSong(song)
            },
            onAddToQueueClick = { song ->
                val url = if (libraryMode == MODE_REMOTE) getStreamUrl(song) else null
                audioPlayer.addToQueue(song, url)
                Toast.makeText(this@MainActivity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                logAdapter.addLog("[QUEUE] Added '${song.title}' to playback queue")
            },
            onLikeClick = { song ->
                toggleSongLiked(song)
            },
            onMoreOptionsClick = { song ->
                showSongOptionsDialog(song)
            }
        )
        rvLibrarySongs?.layoutManager = LinearLayoutManager(this)
        rvLibrarySongs?.adapter = songsAdapter

        // 3. Playlists View
        playlistsView = inflater.inflate(R.layout.fragment_playlists, layoutContent, false)
        badgePlaylistsCount = playlistsView.findViewById(R.id.badge_playlists_count)
        btnPlaylistImportPoweramp = playlistsView.findViewById(R.id.btn_playlist_import_poweramp)
        btnPlaylistCreate = playlistsView.findViewById(R.id.btn_playlist_create)
        btnPlaylistsRefresh = playlistsView.findViewById(R.id.btn_playlists_refresh)
        btnPlaylistTabLocal = playlistsView.findViewById(R.id.btn_playlist_tab_local)
        btnPlaylistTabRemote = playlistsView.findViewById(R.id.btn_playlist_tab_remote)
        layoutPlaylistsMain = playlistsView.findViewById(R.id.layout_playlists_main)
        tvPlaylistsEmpty = playlistsView.findViewById(R.id.tv_playlists_empty)
        swipeRefreshPlaylists = playlistsView.findViewById(R.id.swipe_refresh_playlists)
        rvPlaylists = playlistsView.findViewById(R.id.rv_playlists)
        layoutPlaylistDetail = playlistsView.findViewById(R.id.layout_playlist_detail)
        btnDetailBack = playlistsView.findViewById(R.id.btn_detail_back)
        tvDetailPlaylistName = playlistsView.findViewById(R.id.tv_detail_playlist_name)
        btnDetailPlayAll = playlistsView.findViewById(R.id.btn_detail_play_all)
        tvDetailEmpty = playlistsView.findViewById(R.id.tv_detail_empty)
        rvPlaylistTracks = playlistsView.findViewById(R.id.rv_playlist_tracks)

        playlistsAdapter = PlaylistsAdapter(
            onPlaylistClick = { playlist ->
                openPlaylistDetail(playlist)
            },
            onPlayClick = { playlist ->
                playPlaylist(playlist)
            },
            onDeleteClick = { playlist ->
                confirmDeletePlaylist(playlist)
            },
            onSyncClick = { playlist ->
                syncRemotePlaylistWithQuality(playlist)
            }
        )
        rvPlaylists?.layoutManager = LinearLayoutManager(this)
        rvPlaylists?.adapter = playlistsAdapter

        playlistDetailAdapter = SongsAdapter(
            onPlayClick = { song ->
                val tracks = playlistTracks
                val startIndex = tracks.indexOfFirst { it.filepath == song.filepath }.coerceAtLeast(0)
                val isRemote = selectedPlaylist?.isRemote == true
                val items = tracks.map { s ->
                    val url = if (isRemote) getStreamUrl(s) else null
                    PlayableItem(s, url)
                }
                audioPlayer.setQueue(items, startIndex)
            },
            onDeleteClick = { song ->
                selectedPlaylist?.let { pl ->
                    confirmRemoveTrackFromPlaylist(pl, song)
                }
            },
            onAddToQueueClick = { song ->
                val isRemote = selectedPlaylist?.isRemote == true
                val url = if (isRemote) getStreamUrl(song) else null
                audioPlayer.addToQueue(song, url)
                Toast.makeText(this@MainActivity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
            },
            onLikeClick = { song ->
                toggleSongLiked(song)
            },
            onMoreOptionsClick = { song ->
                showSongOptionsDialog(song)
            }
        )
        rvPlaylistTracks?.layoutManager = LinearLayoutManager(this)
        rvPlaylistTracks?.adapter = playlistDetailAdapter

        // 4. Logs View
        logsView = inflater.inflate(R.layout.fragment_logs, layoutContent, false)
        rvLogs = logsView.findViewById(R.id.rv_logs)
        btnLogsClear = logsView.findViewById(R.id.btn_logs_clear)

        rvLogs?.layoutManager = LinearLayoutManager(this)
        rvLogs?.adapter = logAdapter
    }

    private fun setupListeners() {
        // Navigation tabs
        navTabSync.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabSync clicked"); switchTab(TAB_SYNC) }
        ivTabSync.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabSync clicked"); switchTab(TAB_SYNC) }
        tvTabSync.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabSync clicked"); switchTab(TAB_SYNC) }

        navTabLibrary.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabLibrary clicked"); switchTab(TAB_LIBRARY) }
        ivTabLibrary.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabLibrary clicked"); switchTab(TAB_LIBRARY) }
        tvTabLibrary.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabLibrary clicked"); switchTab(TAB_LIBRARY) }

        navTabPlaylists.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabPlaylists clicked"); switchTab(TAB_PLAYLISTS) }
        ivTabPlaylists.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabPlaylists clicked"); switchTab(TAB_PLAYLISTS) }
        tvTabPlaylists.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabPlaylists clicked"); switchTab(TAB_PLAYLISTS) }

        navTabLogs.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabLogs clicked"); switchTab(TAB_LOGS) }
        ivTabLogs.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabLogs clicked"); switchTab(TAB_LOGS) }
        tvTabLogs.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabLogs clicked"); switchTab(TAB_LOGS) }

        // Server Toggle
        btnToggleServer?.setOnClickListener {
            if (SyncForegroundService.isRunning) {
                stopServerService()
            } else {
                startServerService()
            }
        }

        // Service callbacks
        SyncForegroundService.onStateChanged = { running, addr ->
            runOnUiThread {
                updateServerStatusUI(running, addr)
                refreshIpBadge()
            }
        }

        SyncForegroundService.onLogReceived = { logMsg ->
            runOnUiThread {
                logAdapter.addLog(logMsg)
                rvLogs?.scrollToPosition(logAdapter.itemCount - 1)
            }
        }

        // Desktop Discovery & Ping
        btnAutoDiscover?.setOnClickListener {
            autoDiscoverDesktop(showToast = true)
        }

        btnPingDesktop?.setOnClickListener {
            pingDesktopServer()
        }

        // Sync Actions
        btnSyncPull?.setOnClickListener {
            startSyncPull()
        }

        btnSyncPush?.setOnClickListener {
            startSyncPush()
        }

        // Library Tab Switchers
        btnTabLocal?.setOnClickListener {
            switchLibraryMode(MODE_LOCAL)
        }
        btnTabRemote?.setOnClickListener {
            switchLibraryMode(MODE_REMOTE)
        }

        // Quality selector
        btnLibraryQuality?.setOnClickListener {
            showQualityDialog()
        }

        // Filter chips
        chipFilterAll?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_ALL) }
        chipFilterDownloaded?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_DOWNLOADED) }
        chipFilterNotDownloaded?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_NOT_DOWNLOADED) }

        // Library Controls
        swipeRefreshLibrary?.setOnRefreshListener {
            if (libraryMode == MODE_LOCAL) {
                loadLocalLibrary()
            } else {
                loadRemoteLibrary()
            }
        }
        btnLibraryRefresh?.setOnClickListener {
            if (libraryMode == MODE_LOCAL) {
                loadLocalLibrary()
            } else {
                loadRemoteLibrary()
            }
        }

        etLibrarySearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                songsAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Playlists Listeners
        btnPlaylistTabLocal?.setOnClickListener { switchPlaylistMode(MODE_LOCAL) }
        btnPlaylistTabRemote?.setOnClickListener { switchPlaylistMode(MODE_REMOTE) }
        btnPlaylistImportPoweramp?.setOnClickListener {
            openPowerampDocumentLauncher.launch(arrayOf("*/*"))
        }
        btnPlaylistCreate?.setOnClickListener { promptCreatePlaylistDialog(playlistMode == MODE_REMOTE) }
        btnPlaylistsRefresh?.setOnClickListener { loadPlaylists() }
        swipeRefreshPlaylists?.setOnRefreshListener { loadPlaylists() }
        btnDetailBack?.setOnClickListener { closePlaylistDetail() }
        btnDetailPlayAll?.setOnClickListener {
            selectedPlaylist?.let { pl -> playPlaylist(pl) }
        }

        btnLogsClear?.setOnClickListener {
            logAdapter.clear()
        }

        // Save IP / Port on change
        etDesktopIp?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.desktopIp = s?.toString()?.trim() ?: "192.168.1.100"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etDesktopPort?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val p = s?.toString()?.toIntOrNull() ?: 5000
                prefs.desktopPort = p
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun initPlayerBar() {
        btnPlayerPlayPause?.setOnClickListener {
            audioPlayer.togglePlayPause()
        }

        btnPlayerShuffle?.setOnClickListener {
            val shuffled = audioPlayer.toggleShuffle()
            updateShuffleButton(shuffled)
            val msg = if (shuffled) "Shuffle On" else "Shuffle Off"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnPlayerPrev?.setOnClickListener {
            audioPlayer.playPrevious()
        }

        btnPlayerNext?.setOnClickListener {
            audioPlayer.playNext()
        }

        btnPlayerRepeat?.setOnClickListener {
            val mode = audioPlayer.toggleRepeat()
            updateRepeatButton(mode)
            val msg = when (mode) {
                RepeatMode.OFF -> "Repeat Off"
                RepeatMode.ALL -> "Repeat All"
                RepeatMode.ONE -> "Repeat One"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnPlayerLike?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                toggleSongLiked(song)
            }
        }

        btnPlayerQueue?.setOnClickListener {
            showQueueDialog()
        }

        btnPlayerClose?.setOnClickListener {
            audioPlayer.stop()
            playerBarContainer.visibility = View.GONE
            songsAdapter.setActiveSong(null, false)
            playlistDetailAdapter.setActiveSong(null, false)
        }

        playerSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = progress.toFloat() / 1000f
                    audioPlayer.seekTo(ratio)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        audioPlayer.onTrackChanged = { song ->
            runOnUiThread {
                playerBarContainer.visibility = View.VISIBLE
                tvPlayerTitle?.text = song.title.ifBlank { song.filename }
                tvPlayerArtist?.text = song.artist.ifBlank { "Unknown Artist" }
                btnPlayerPlayPause?.setImageResource(R.drawable.ic_pause)
                songsAdapter.setActiveSong(song, true)
                playlistDetailAdapter.setActiveSong(song, true)
                updatePlayerControlsState()
                updatePlayerLikeButton(song)
            }
        }

        audioPlayer.onStateChanged = { isPlaying ->
            runOnUiThread {
                btnPlayerPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                songsAdapter.setActiveSong(audioPlayer.currentSong, isPlaying)
                playlistDetailAdapter.setActiveSong(audioPlayer.currentSong, isPlaying)
                updatePlayerControlsState()
                updatePlayerLikeButton(audioPlayer.currentSong)
            }
        }

        audioPlayer.onQueueChanged = { _, _ ->
            runOnUiThread {
                updatePlayerControlsState()
            }
        }

        audioPlayer.onModeChanged = { isShuffled, repeatMode ->
            runOnUiThread {
                updateShuffleButton(isShuffled)
                updateRepeatButton(repeatMode)
            }
        }

        audioPlayer.onProgress = { currentMs, totalMs ->
            runOnUiThread {
                if (totalMs > 0) {
                    val ratio = (currentMs.toFloat() / totalMs.toFloat()) * 1000f
                    playerSeekbar?.progress = ratio.toInt()

                    val curMin = currentMs / 1000 / 60
                    val curSec = (currentMs / 1000) % 60
                    val totMin = totalMs / 1000 / 60
                    val totSec = (totalMs / 1000) % 60
                    tvPlayerTime?.text = String.format(Locale.US, "%02d:%02d / %02d:%02d", curMin, curSec, totMin, totSec)
                }
            }
        }

        audioPlayer.onError = { err ->
            runOnUiThread {
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                logAdapter.addLog("[PLAYER-ERROR] $err")
            }
        }
    }

    private fun updateShuffleButton(shuffled: Boolean) {
        val yellow = ContextCompat.getColor(this, R.color.yellow_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        btnPlayerShuffle?.setColorFilter(if (shuffled) yellow else muted)
    }

    private fun updateRepeatButton(mode: RepeatMode) {
        val yellow = ContextCompat.getColor(this, R.color.yellow_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        when (mode) {
            RepeatMode.OFF -> {
                btnPlayerRepeat?.setImageResource(R.drawable.ic_repeat)
                btnPlayerRepeat?.setColorFilter(muted)
            }
            RepeatMode.ALL -> {
                btnPlayerRepeat?.setImageResource(R.drawable.ic_repeat)
                btnPlayerRepeat?.setColorFilter(yellow)
            }
            RepeatMode.ONE -> {
                btnPlayerRepeat?.setImageResource(R.drawable.ic_repeat_one)
                btnPlayerRepeat?.setColorFilter(yellow)
            }
        }
    }

    private fun updatePlayerLikeButton(song: Song?) {
        if (song == null) {
            btnPlayerLike?.setImageResource(R.drawable.ic_favorite_border)
            btnPlayerLike?.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            return
        }
        val isLiked = playlistManager.isLiked(song)
        if (isLiked) {
            btnPlayerLike?.setImageResource(R.drawable.ic_favorite)
            btnPlayerLike?.setColorFilter(Color.parseColor("#FFFF0055"))
        } else {
            btnPlayerLike?.setImageResource(R.drawable.ic_favorite_border)
            btnPlayerLike?.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun updatePlayerControlsState() {
        btnPlayerPrev?.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
        btnPlayerNext?.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        layoutContent.removeAllViews()

        val yellow = ContextCompat.getColor(this, R.color.yellow_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        ivTabSync.setColorFilter(if (tab == TAB_SYNC) yellow else muted)
        tvTabSync.setTextColor(if (tab == TAB_SYNC) yellow else muted)

        ivTabLibrary.setColorFilter(if (tab == TAB_LIBRARY) yellow else muted)
        tvTabLibrary.setTextColor(if (tab == TAB_LIBRARY) yellow else muted)

        ivTabPlaylists.setColorFilter(if (tab == TAB_PLAYLISTS) yellow else muted)
        tvTabPlaylists.setTextColor(if (tab == TAB_PLAYLISTS) yellow else muted)

        ivTabLogs.setColorFilter(if (tab == TAB_LOGS) yellow else muted)
        tvTabLogs.setTextColor(if (tab == TAB_LOGS) yellow else muted)

        when (tab) {
            TAB_SYNC -> layoutContent.addView(syncView)
            TAB_LIBRARY -> {
                layoutContent.addView(libraryView)
                if (libraryMode == MODE_LOCAL && localSongs.isEmpty()) {
                    loadLocalLibrary()
                } else if (libraryMode == MODE_REMOTE && remoteSongs.isEmpty()) {
                    loadRemoteLibrary()
                }
            }
            TAB_PLAYLISTS -> {
                layoutContent.addView(playlistsView)
                loadPlaylists()
            }
            TAB_LOGS -> layoutContent.addView(logsView)
        }
    }

    private fun refreshIpBadge() {
        val ip = NetworkUtils.getWifiIpAddress(this)
        if (ip != null) {
            tvMainIpBadge.text = "IP: $ip"
            tvMainIpBadge.setBackgroundResource(R.drawable.bg_badge_yellow)
        } else {
            tvMainIpBadge.text = "Wi-Fi: Disconnected"
            tvMainIpBadge.setBackgroundResource(R.drawable.bg_badge_red)
        }
    }

    private fun updateServerStatusUI(running: Boolean, address: String?) {
        if (running) {
            badgeServerStatus?.text = "ONLINE"
            badgeServerStatus?.setBackgroundResource(R.drawable.bg_badge_green)
            badgeServerStatus?.setTextColor(ContextCompat.getColor(this, R.color.status_online))

            val port = prefs.serverPort
            val ip = NetworkUtils.getWifiIpAddress(this) ?: "0.0.0.0"
            tvServerAddress?.text = "Running at http://$ip:$port"
            tvServerAddress?.visibility = View.VISIBLE

            btnToggleServer?.text = getString(R.string.btn_stop_server)
            btnToggleServer?.setBackgroundResource(R.drawable.bg_button_danger)
            btnToggleServer?.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            badgeServerStatus?.text = "OFFLINE"
            badgeServerStatus?.setBackgroundResource(R.drawable.bg_badge_red)
            badgeServerStatus?.setTextColor(ContextCompat.getColor(this, R.color.status_offline))

            tvServerAddress?.text = getString(R.string.status_server_stopped)
            tvServerAddress?.visibility = View.VISIBLE

            btnToggleServer?.text = getString(R.string.btn_start_server)
            btnToggleServer?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnToggleServer?.setTextColor(ContextCompat.getColor(this, R.color.black))
        }
    }

    private fun startServerService() {
        val serviceIntent = Intent(this, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        logAdapter.addLog("[SERVER] Starting Over-IP sync server...")
    }

    private fun stopServerService() {
        val serviceIntent = Intent(this, SyncForegroundService::class.java)
        stopService(serviceIntent)
        updateServerStatusUI(false, null)
        refreshIpBadge()
        logAdapter.addLog("[SERVER] Stopped Over-IP server.")
    }

    private fun autoDiscoverDesktop(showToast: Boolean = false) {
        tvDesktopStatus?.text = "Scanning Wi-Fi network for Desktop..."
        logAdapter.addLog("[DISCOVERY] Scanning Wi-Fi network for Desktop...")

        lifecycleScope.launch {
            val found = PeerDiscoveryManager.discoverDesktop(
                context = this@MainActivity,
                timeoutMs = 3000,
                onFound = { foundIp, foundPort, hostname ->
                    runOnUiThread {
                        etDesktopIp?.setText(foundIp)
                        etDesktopPort?.setText(foundPort.toString())
                        prefs.desktopIp = foundIp
                        prefs.desktopPort = foundPort
                        tvDesktopStatus?.text = "Discovered Desktop at $foundIp:$foundPort"
                        logAdapter.addLog("[DISCOVERY] Found Desktop ($hostname) at http://$foundIp:$foundPort")
                        if (showToast) Toast.makeText(this@MainActivity, "Found Desktop: $foundIp", Toast.LENGTH_SHORT).show()
                    }
                },
                onLog = { msg ->
                    runOnUiThread { logAdapter.addLog(msg) }
                }
            )
            if (!found) {
                runOnUiThread {
                    tvDesktopStatus?.text = "No Desktop found via UDP broadcast"
                    if (showToast) Toast.makeText(this@MainActivity, "Desktop not detected. Set IP manually.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pingDesktopServer() {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter Desktop IP address", Toast.LENGTH_SHORT).show()
            return
        }

        btnPingDesktop?.isEnabled = false
        tvDesktopStatus?.text = "Pinging $ip:$port..."
        logAdapter.addLog("[CLIENT] Pinging Desktop at http://$ip:$port...")

        lifecycleScope.launch {
            try {
                val res = apiClient.ping(ip, port)
                val status = res.optString("status", "ok")
                val songs = res.optInt("song_count", 0)
                val hostname = res.optString("hostname", ip)

                runOnUiThread {
                    btnPingDesktop?.isEnabled = true
                    tvDesktopStatus?.text = "Connected to $hostname ($songs songs indexed)"
                    tvDesktopStatus?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_online))
                    logAdapter.addLog("[OK] Ping successful! Host: $hostname, songs: $songs")
                    Toast.makeText(this@MainActivity, "Connected! $songs tracks on Desktop", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnPingDesktop?.isEnabled = true
                    tvDesktopStatus?.text = "Cannot reach $ip:$port (${e.message})"
                    tvDesktopStatus?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_offline))
                    logAdapter.addLog("[ERROR] Ping failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startSyncPull() {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        setSyncInProgress(true, "Comparing tracks with Desktop...")
        logAdapter.addLog("[SYNC-PULL] Starting Pull Sync from Desktop ($ip:$port)...")

        lifecycleScope.launch {
            try {
                val desktopSongs = apiClient.fetchSongs(ip, port)
                val localSongs = MediaStoreHelper.getAllDeviceSongs(this@MainActivity)
                val diff = syncManager.calculateDiff(localSongs, desktopSongs)
                val songsToPull = diff.songsToPull

                logAdapter.addLog("[SYNC-PULL] Diff: ${songsToPull.size} to pull, ${diff.songsToPush.size} local only")

                if (songsToPull.isEmpty()) {
                    runOnUiThread {
                        setSyncInProgress(false)
                        tvSyncStatusLabel?.text = "Library is already in sync!"
                        Toast.makeText(this@MainActivity, "Device is already up to date!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                runOnUiThread {
                    tvSyncStatusLabel?.text = "Pulling ${songsToPull.size} track(s)..."
                }

                syncManager.pullSongs(
                    desktopIp = ip,
                    desktopPort = port,
                    songsToPull = songsToPull,
                    onProgress = { current, total, song, percent, message ->
                        runOnUiThread {
                            pbSync?.progress = percent
                            tvSyncPercent?.text = "$percent%"
                            tvSyncDetail?.text = "($current/$total) ${song.filename}"
                        }
                    },
                    onLog = { msg ->
                        runOnUiThread { logAdapter.addLog(msg) }
                    }
                )

                runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Pull Complete! Downloaded ${songsToPull.size} tracks."
                    Toast.makeText(this@MainActivity, "Downloaded ${songsToPull.size} songs!", Toast.LENGTH_LONG).show()
                    loadLocalLibrary()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Sync Failed: ${e.message}"
                    logAdapter.addLog("[ERROR] Sync Pull failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "Sync error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startSyncPush() {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        setSyncInProgress(true, "Scanning local library for new songs...")
        logAdapter.addLog("[SYNC-PUSH] Starting Push Sync to Desktop ($ip:$port)...")

        lifecycleScope.launch {
            try {
                val desktopSongs = apiClient.fetchSongs(ip, port)
                val localSongs = MediaStoreHelper.getAllDeviceSongs(this@MainActivity)
                val diff = syncManager.calculateDiff(localSongs, desktopSongs)
                val songsToPush = diff.songsToPush

                logAdapter.addLog("[SYNC-PUSH] Diff: ${songsToPush.size} to push, ${diff.songsToPull.size} remote only")

                if (songsToPush.isEmpty()) {
                    runOnUiThread {
                        setSyncInProgress(false)
                        tvSyncStatusLabel?.text = "No new songs to push to Desktop."
                        Toast.makeText(this@MainActivity, "All local songs already on Desktop!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                runOnUiThread {
                    tvSyncStatusLabel?.text = "Pushing ${songsToPush.size} track(s)..."
                }

                syncManager.pushSongs(
                    desktopIp = ip,
                    desktopPort = port,
                    songsToPush = songsToPush,
                    onProgress = { current, total, song, percent, message ->
                        runOnUiThread {
                            pbSync?.progress = percent
                            tvSyncPercent?.text = "$percent%"
                            tvSyncDetail?.text = "($current/$total) ${song.filename}"
                        }
                    },
                    onLog = { msg ->
                        runOnUiThread { logAdapter.addLog(msg) }
                    }
                )

                runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Push Complete! Uploaded ${songsToPush.size} tracks."
                    Toast.makeText(this@MainActivity, "Uploaded ${songsToPush.size} songs!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setSyncInProgress(false)
                    tvSyncStatusLabel?.text = "Push Failed: ${e.message}"
                    logAdapter.addLog("[ERROR] Sync Push failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "Push error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setSyncInProgress(inProgress: Boolean, statusLabel: String = "") {
        if (inProgress) {
            tvSyncStatusLabel?.text = statusLabel
            tvSyncPercent?.text = "0%"
            pbSync?.progress = 0
            tvSyncDetail?.text = "Preparing..."
        }
        btnSyncPull?.isEnabled = !inProgress
        btnSyncPush?.isEnabled = !inProgress
        btnPingDesktop?.isEnabled = !inProgress
        layoutSyncProgress?.visibility = if (inProgress) View.VISIBLE else layoutSyncProgress?.visibility ?: View.GONE
    }

    private fun getStreamUrl(song: Song): String {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
        val cleanIp = ip.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val encoded = URLEncoder.encode(song.filepath, "UTF-8")
        return "http://$cleanIp:$port/api/song/stream?filepath=$encoded"
    }

    // ==========================================
    // LIBRARY TAB
    // ==========================================

    private fun switchLibraryMode(mode: Int) {
        libraryMode = mode
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val black = ContextCompat.getColor(this, R.color.black)

        setFilterChip(SongsAdapter.FILTER_ALL)

        if (mode == MODE_LOCAL) {
            btnTabLocal?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnTabLocal?.setTextColor(black)
            btnTabRemote?.background = null
            btnTabRemote?.setTextColor(muted)

            layoutFilterChips?.visibility = View.GONE
            btnLibraryQuality?.visibility = View.GONE

            val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
            songsAdapter.setRemoteMode(false, localNames)
            songsAdapter.submitList(localSongs)
            badgeLibraryCount?.text = "${localSongs.size} tracks"
            tvLibraryEmpty?.text = getString(R.string.empty_library)
            tvLibraryEmpty?.visibility = if (localSongs.isEmpty()) View.VISIBLE else View.GONE
            etLibrarySearch?.text?.clear()
        } else {
            btnTabRemote?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnTabRemote?.setTextColor(black)
            btnTabLocal?.background = null
            btnTabLocal?.setTextColor(muted)

            layoutFilterChips?.visibility = View.VISIBLE
            btnLibraryQuality?.visibility = View.VISIBLE

            val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
            songsAdapter.setRemoteMode(true, localNames)

            if (remoteSongs.isEmpty()) {
                loadRemoteLibrary()
            } else {
                songsAdapter.submitList(remoteSongs)
                badgeLibraryCount?.text = "${remoteSongs.size} tracks"
                tvLibraryEmpty?.text = "No songs found on Desktop"
                tvLibraryEmpty?.visibility = if (remoteSongs.isEmpty()) View.VISIBLE else View.GONE
            }
            etLibrarySearch?.text?.clear()
        }
    }

    private fun loadLocalLibrary() {
        swipeRefreshLibrary?.isRefreshing = true
        lifecycleScope.launch {
            val songs = withContext(Dispatchers.IO) {
                MediaStoreHelper.getAllDeviceSongs(this@MainActivity)
            }
            localSongs = songs
            val localNames = songs.map { it.filename.lowercase(Locale.US) }.toSet()

            runOnUiThread {
                if (libraryMode == MODE_LOCAL) {
                    songsAdapter.setRemoteMode(false, localNames)
                    songsAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                    songsAdapter.submitList(songs)
                    badgeLibraryCount?.text = "${songs.size} tracks"
                    tvLibraryEmpty?.text = getString(R.string.empty_library)
                    tvLibraryEmpty?.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                }
                swipeRefreshLibrary?.isRefreshing = false
            }
        }
    }

    private fun loadRemoteLibrary() {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        if (ip.isEmpty()) {
            Toast.makeText(this, "Desktop IP is not configured", Toast.LENGTH_SHORT).show()
            tvLibraryEmpty?.text = "Desktop IP not configured. Set IP on Sync tab."
            tvLibraryEmpty?.visibility = View.VISIBLE
            return
        }

        swipeRefreshLibrary?.isRefreshing = true
        tvLibraryEmpty?.visibility = View.GONE
        logAdapter.addLog("[CLIENT] Fetching remote songs from Desktop ($ip:$port)...")

        lifecycleScope.launch {
            try {
                val songs = apiClient.fetchSongs(ip, port)
                remoteSongs = songs
                val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()

                runOnUiThread {
                    if (libraryMode == MODE_REMOTE) {
                        songsAdapter.setRemoteMode(true, localNames)
                        songsAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                        songsAdapter.submitList(songs)
                        badgeLibraryCount?.text = "${songs.size} tracks"
                        tvLibraryEmpty?.text = "No songs found on Desktop"
                        tvLibraryEmpty?.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                    }
                    swipeRefreshLibrary?.isRefreshing = false
                    logAdapter.addLog("[OK] Loaded ${songs.size} remote track(s) from Desktop")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefreshLibrary?.isRefreshing = false
                    if (libraryMode == MODE_REMOTE) {
                        tvLibraryEmpty?.text = "Could not reach Desktop ($ip:$port).\n${e.message}\nMake sure Desktop app is running and tap Refresh."
                        tvLibraryEmpty?.visibility = View.VISIBLE
                    }
                    logAdapter.addLog("[ERROR] Failed to fetch remote songs: ${e.message}")
                    Toast.makeText(this@MainActivity, "Failed to connect to Desktop: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadRemoteSong(song: Song) {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
        val destDir = prefs.musicStorageDirectory
        val destFile = File(destDir, song.filename)
        val quality = prefs.downloadQuality

        Toast.makeText(this, "Downloading ${song.title}...", Toast.LENGTH_SHORT).show()
        logAdapter.addLog("[DOWNLOAD] Starting download of '${song.filename}' from Desktop (quality: $quality)...")

        lifecycleScope.launch {
            try {
                val ok = apiClient.downloadSong(ip, port, song.filepath, destFile, bitrate = quality)
                if (ok && destFile.exists()) {
                    withContext(Dispatchers.IO) {
                        com.aruncs.musicsync.data.MediaScannerHelper.scanFile(this@MainActivity, destFile.absolutePath)
                        localSongs = MediaStoreHelper.getAllDeviceSongs(this@MainActivity)
                    }
                    runOnUiThread {
                        val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
                        songsAdapter.setRemoteMode(true, localNames)
                        Toast.makeText(this@MainActivity, "Downloaded ${song.title}", Toast.LENGTH_SHORT).show()
                        logAdapter.addLog("[DOWNLOAD] Successfully saved '${destFile.name}' to /Music")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    logAdapter.addLog("[ERROR] Failed to download '${song.filename}': ${e.message}")
                }
            }
        }
    }

    private fun showQualityDialog() {
        val options = arrayOf("Original (lossless)", "320 kbps", "256 kbps", "192 kbps", "128 kbps")
        val values = arrayOf("original", "320", "256", "192", "128")
        val current = prefs.downloadQuality
        val checkedItem = values.indexOfFirst { it == current }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Download Quality")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selected = values[which]
                prefs.downloadQuality = selected
                btnLibraryQuality?.text = if (selected == "original") "HQ" else "${selected}k"
                logAdapter.addLog("[SETTINGS] Download quality set to: ${options[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setFilterChip(filter: Int) {
        songsAdapter.setDownloadFilter(filter)
        val black = ContextCompat.getColor(this, R.color.black)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        listOf(
            chipFilterAll to SongsAdapter.FILTER_ALL,
            chipFilterDownloaded to SongsAdapter.FILTER_DOWNLOADED,
            chipFilterNotDownloaded to SongsAdapter.FILTER_NOT_DOWNLOADED,
            chipFilterLiked to SongsAdapter.FILTER_LIKED
        ).forEach { (chip, chipFilter) ->
            if (chipFilter == filter) {
                chip?.setBackgroundResource(R.drawable.bg_button_yellow)
                chip?.setTextColor(black)
            } else {
                chip?.setBackgroundResource(R.drawable.bg_button_dark)
                chip?.setTextColor(muted)
            }
        }
    }

    private fun toggleSongLiked(song: Song) {
        val nowLiked = playlistManager.toggleLike(song)
        val likedSet = playlistManager.getLikedFilepaths()
        songsAdapter.setLikedSet(likedSet)
        playlistDetailAdapter.setLikedSet(likedSet)

        if (audioPlayer.currentSong?.filepath == song.filepath ||
            audioPlayer.currentSong?.filename.equals(song.filename, ignoreCase = true)) {
            updatePlayerLikeButton(song)
        }

        val msg = if (nowLiked) "Added to Liked Music ❤️" else "Removed from Liked Music"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

        if (currentTab == TAB_PLAYLISTS && playlistMode == MODE_LOCAL) {
            loadLocalPlaylists()
        }
    }

    private fun confirmDeleteSong(song: Song) {
        if (libraryMode == MODE_REMOTE) {
            AlertDialog.Builder(this)
                .setTitle("Delete Song from Desktop")
                .setMessage("Delete \"${song.title}\" from the Desktop permanently?")
                .setPositiveButton("Delete") { _, _ ->
                    val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
                    val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
                    lifecycleScope.launch {
                        try {
                            apiClient.deleteSong(ip, port, song.filepath)
                            runOnUiThread {
                                remoteSongs = remoteSongs.filter { it.filepath != song.filepath }
                                songsAdapter.submitList(remoteSongs)
                                badgeLibraryCount?.text = "${remoteSongs.size} tracks"
                                Toast.makeText(this@MainActivity, "\"${song.title}\" deleted from Desktop", Toast.LENGTH_SHORT).show()
                                logAdapter.addLog("[DELETE] Removed '${song.filename}' from Desktop")
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                                logAdapter.addLog("[ERROR] Failed to delete '${song.filename}': ${e.message}")
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Delete Song")
                .setMessage("Choose how to delete \"${song.title}\":")
                .setPositiveButton("Move to Trash") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ok = MediaStoreHelper.moveToTrash(this@MainActivity, song)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                localSongs = localSongs.filter { it.id != song.id }
                                songsAdapter.submitList(localSongs)
                                badgeLibraryCount?.text = "${localSongs.size} tracks"
                                Toast.makeText(this@MainActivity, "\"${song.title}\" moved to .trash", Toast.LENGTH_SHORT).show()
                                logAdapter.addLog("[TRASH] Moved '${song.filename}' to .trash")
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to move song to trash", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNeutralButton("Delete Permanently") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ok = MediaStoreHelper.deleteSong(this@MainActivity, song)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                localSongs = localSongs.filter { it.id != song.id }
                                songsAdapter.submitList(localSongs)
                                badgeLibraryCount?.text = "${localSongs.size} tracks"
                                Toast.makeText(this@MainActivity, "\"${song.title}\" deleted permanently", Toast.LENGTH_SHORT).show()
                                logAdapter.addLog("[DELETE] Permanently removed '${song.filename}' from device")
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to delete song", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ==========================================
    // QUEUE & SONG MORE OPTIONS
    // ==========================================

    private fun showSongOptionsDialog(song: Song) {
        val options = arrayOf("Play Next", "Add to Queue", "Add to Playlist...", "Delete Song")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                val streamUrl = if (libraryMode == MODE_REMOTE) getStreamUrl(song) else null
                when (which) {
                    0 -> {
                        audioPlayer.addToQueueNext(song, streamUrl)
                        Toast.makeText(this, "Will play next: ${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        audioPlayer.addToQueue(song, streamUrl)
                        Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        showAddToPlaylistDialog(song)
                    }
                    3 -> {
                        confirmDeleteSong(song)
                    }
                }
            }
            .show()
    }

    private fun showQueueDialog() {
        val q = audioPlayer.queue
        if (q.isEmpty()) {
            Toast.makeText(this, "Playback queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_queue, null)
        val tvCount = dialogView.findViewById<TextView>(R.id.tv_dialog_queue_count)
        val btnClear = dialogView.findViewById<ImageButton>(R.id.btn_dialog_queue_clear)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_dialog_queue_close)
        val rvQueue = dialogView.findViewById<RecyclerView>(R.id.rv_dialog_queue)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        tvCount.text = "${q.size} ${if (q.size == 1) "track" else "tracks"}"
        rvQueue.layoutManager = LinearLayoutManager(this)

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
            playerBarContainer.visibility = View.GONE
            dialog.dismiss()
            Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ==========================================
    // PLAYLISTS TAB & MANAGEMENT
    // ==========================================

    private fun switchPlaylistMode(mode: Int) {
        playlistMode = mode
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val black = ContextCompat.getColor(this, R.color.black)

        closePlaylistDetail()

        if (mode == MODE_LOCAL) {
            btnPlaylistTabLocal?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnPlaylistTabLocal?.setTextColor(black)
            btnPlaylistTabRemote?.background = null
            btnPlaylistTabRemote?.setTextColor(muted)
            loadLocalPlaylists()
        } else {
            btnPlaylistTabRemote?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnPlaylistTabRemote?.setTextColor(black)
            btnPlaylistTabLocal?.background = null
            btnPlaylistTabLocal?.setTextColor(muted)
            loadRemotePlaylists()
        }
    }

    private fun loadPlaylists() {
        if (playlistMode == MODE_LOCAL) {
            loadLocalPlaylists()
        } else {
            loadRemotePlaylists()
        }
    }

    private fun loadLocalPlaylists() {
        swipeRefreshPlaylists?.isRefreshing = true
        val userLists = playlistManager.getLocalPlaylists()
        val likedSongs = playlistManager.getLikedSongs(localSongs)
        val likedPlaylist = Playlist(
            id = -1L,
            name = "❤️ Liked Music",
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

    private fun loadRemotePlaylists() {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        if (ip.isEmpty()) {
            tvPlaylistsEmpty?.text = "Desktop IP not configured. Set IP on Sync tab."
            tvPlaylistsEmpty?.visibility = View.VISIBLE
            playlistsAdapter.submitList(emptyList())
            badgePlaylistsCount?.text = "0 playlists"
            return
        }

        swipeRefreshPlaylists?.isRefreshing = true
        tvPlaylistsEmpty?.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val lists = apiClient.fetchPlaylists(ip, port)
                remotePlaylists = lists
                runOnUiThread {
                    playlistsAdapter.submitList(lists)
                    badgePlaylistsCount?.text = "${lists.size} playlists"
                    tvPlaylistsEmpty?.text = "No playlists found on Desktop.\nTap '+ New' to create one."
                    tvPlaylistsEmpty?.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
                    swipeRefreshPlaylists?.isRefreshing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefreshPlaylists?.isRefreshing = false
                    tvPlaylistsEmpty?.text = "Could not reach Desktop ($ip:$port).\n${e.message}"
                    tvPlaylistsEmpty?.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Failed to load remote playlists: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPlaylistDetail(playlist: Playlist) {
        selectedPlaylist = playlist
        layoutPlaylistsMain?.visibility = View.GONE
        layoutPlaylistDetail?.visibility = View.VISIBLE
        tvDetailPlaylistName?.text = playlist.name
        tvDetailEmpty?.visibility = View.GONE

        if (playlist.isRemote) {
            val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
            val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
            lifecycleScope.launch {
                try {
                    val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id)
                    playlistTracks = tracks
                    runOnUiThread {
                        val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
                        playlistDetailAdapter.setRemoteMode(true, localNames)
                        playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                        playlistDetailAdapter.submitList(tracks)
                        tvDetailEmpty?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to load tracks: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (playlist.id == -1L) {
            val tracks = playlistManager.getLikedSongs(localSongs)
            playlistTracks = tracks
            playlistDetailAdapter.setRemoteMode(false)
            playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
            playlistDetailAdapter.submitList(tracks)
            tvDetailEmpty?.text = "No liked songs yet.\nTap ❤️ on any song to add it here."
            tvDetailEmpty?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        } else {
            val tracks = playlistManager.getPlaylistSongs(playlist, localSongs)
            playlistTracks = tracks
            playlistDetailAdapter.setRemoteMode(false)
            playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
            playlistDetailAdapter.submitList(tracks)
            tvDetailEmpty?.text = "No tracks in this playlist"
            tvDetailEmpty?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun closePlaylistDetail() {
        selectedPlaylist = null
        playlistTracks = emptyList()
        layoutPlaylistDetail?.visibility = View.GONE
        layoutPlaylistsMain?.visibility = View.VISIBLE
    }

    private fun playPlaylist(playlist: Playlist) {
        if (playlist.isRemote) {
            val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
            val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
            lifecycleScope.launch {
                try {
                    val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id)
                    if (tracks.isEmpty()) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Playlist is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val items = tracks.map { s -> PlayableItem(s, getStreamUrl(s)) }
                    runOnUiThread {
                        audioPlayer.setQueue(items, 0)
                        Toast.makeText(this@MainActivity, "Playing \"${playlist.name}\" (${items.size} tracks)", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        } else if (playlist.id == -1L) {
            val tracks = playlistManager.getLikedSongs(localSongs)
            if (tracks.isEmpty()) {
                Toast.makeText(this, "Liked Music is empty", Toast.LENGTH_SHORT).show()
                return
            }
            val items = tracks.map { s -> PlayableItem(s, null) }
            audioPlayer.setQueue(items, 0)
            Toast.makeText(this, "Playing Liked Music (${items.size} tracks)", Toast.LENGTH_SHORT).show()
        } else {
            val tracks = playlistManager.getPlaylistSongs(playlist, localSongs)
            if (tracks.isEmpty()) {
                Toast.makeText(this, "Playlist is empty or tracks missing", Toast.LENGTH_SHORT).show()
                return
            }
            val items = tracks.map { s -> PlayableItem(s, null) }
            audioPlayer.setQueue(items, 0)
            Toast.makeText(this, "Playing \"${playlist.name}\" (${items.size} tracks)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeletePlaylist(playlist: Playlist) {
        if (playlist.id == -1L) {
            Toast.makeText(this, "Cannot delete Liked Music playlist", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete playlist \"${playlist.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                if (playlist.isRemote) {
                    val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
                    val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
                    lifecycleScope.launch {
                        try {
                            apiClient.deletePlaylist(ip, port, playlist.id)
                            runOnUiThread {
                                loadRemotePlaylists()
                                Toast.makeText(this@MainActivity, "Deleted \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    playlistManager.deletePlaylist(playlist.id)
                    loadLocalPlaylists()
                    Toast.makeText(this, "Deleted \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveTrackFromPlaylist(playlist: Playlist, song: Song) {
        val title = if (playlist.id == -1L) "Unlike Song" else "Remove Track"
        val msg = if (playlist.id == -1L) "Remove \"${song.title}\" from Liked Music?" else "Remove \"${song.title}\" from ${playlist.name}?"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Remove") { _, _ ->
                if (playlist.isRemote) {
                    val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
                    val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
                    lifecycleScope.launch {
                        try {
                            apiClient.removeTrackFromPlaylist(ip, port, playlist.id, song.filepath)
                            runOnUiThread {
                                openPlaylistDetail(playlist)
                                Toast.makeText(this@MainActivity, "Removed from ${playlist.name}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else if (playlist.id == -1L) {
                    playlistManager.toggleLike(song)
                    songsAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                    playlistDetailAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                    openPlaylistDetail(playlist)
                    Toast.makeText(this, "Removed from Liked Music", Toast.LENGTH_SHORT).show()
                } else {
                    playlistManager.removeTrack(playlist.id, song.filepath)
                    val updatedPl = playlistManager.getPlaylist(playlist.id) ?: playlist
                    openPlaylistDetail(updatedPl)
                    Toast.makeText(this, "Removed from ${playlist.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncRemotePlaylistWithQuality(playlist: Playlist) {
        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort

        if (ip.isEmpty()) {
            Toast.makeText(this, "Desktop IP is not configured", Toast.LENGTH_SHORT).show()
            return
        }

        val qualityOptions = arrayOf(
            "192 kbps (Recommended)",
            "128 kbps (Space Saver)",
            "256 kbps (High Quality)",
            "320 kbps (Maximum Quality)",
            "Original (Lossless / As-is)"
        )
        val bitrateValues = arrayOf("192k", "128k", "256k", "320k", "original")

        AlertDialog.Builder(this)
            .setTitle("Sync \"${playlist.name}\" to Device")
            .setItems(qualityOptions) { _, which ->
                val selectedBitrate = bitrateValues[which]
                executePlaylistSync(playlist, ip, port, selectedBitrate)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executePlaylistSync(playlist: Playlist, ip: String, port: Int, bitrate: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Syncing \"${playlist.name}\"")
            .setMessage("Contacting Desktop ($ip:$port)...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id)
                if (tracks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Playlist \"${playlist.name}\" is empty on Desktop", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val musicDir = prefs.musicStorageDirectory
                if (!musicDir.exists()) musicDir.mkdirs()

                // Query current local songs to ensure skip accuracy
                val currentLocal = withContext(Dispatchers.IO) {
                    MediaStoreHelper.getAllDeviceSongs(this@MainActivity)
                }
                localSongs = currentLocal

                val finalTrackPaths = mutableListOf<String>()
                val newlyDownloadedPaths = mutableListOf<String>()
                var skippedCount = 0
                var downloadedCount = 0

                for ((idx, track) in tracks.withIndex()) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Checking tracks (${idx + 1}/${tracks.size}):\n${track.title.ifBlank { track.filename }}")
                    }

                    // Check if track already exists locally
                    val existingSong = currentLocal.firstOrNull {
                        it.filename.equals(track.filename, ignoreCase = true) ||
                        (it.title.isNotBlank() && it.title.equals(track.title, ignoreCase = true) &&
                         it.artist.isNotBlank() && it.artist.equals(track.artist, ignoreCase = true))
                    }
                    val targetFile = File(musicDir, track.filename)

                    if (existingSong != null && File(existingSong.filepath).exists()) {
                        finalTrackPaths.add(existingSong.filepath)
                        skippedCount++
                        withContext(Dispatchers.Main) {
                            logAdapter.addLog("[SYNC-SKIP] Track already on device: ${track.filename}")
                        }
                    } else if (targetFile.exists() && targetFile.length() > 0) {
                        finalTrackPaths.add(targetFile.absolutePath)
                        skippedCount++
                        withContext(Dispatchers.Main) {
                            logAdapter.addLog("[SYNC-SKIP] File already exists: ${targetFile.name}")
                        }
                    } else {
                        // Download track from desktop with chosen bitrate
                        withContext(Dispatchers.Main) {
                            progressDialog.setMessage("Downloading (${idx + 1}/${tracks.size}) @ $bitrate:\n${track.title.ifBlank { track.filename }}")
                        }

                        val success = apiClient.downloadSong(
                            ip = ip,
                            port = port,
                            remoteFilepath = track.filepath,
                            destFile = targetFile,
                            bitrate = bitrate
                        )

                        if (success && targetFile.exists()) {
                            finalTrackPaths.add(targetFile.absolutePath)
                            newlyDownloadedPaths.add(targetFile.absolutePath)
                            downloadedCount++
                            withContext(Dispatchers.Main) {
                                logAdapter.addLog("[SYNC-DL] Downloaded '${targetFile.name}' ($bitrate)")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                logAdapter.addLog("[SYNC-ERR] Failed downloading '${track.filename}'")
                            }
                        }
                    }
                }

                // Scan newly downloaded files into MediaStore
                if (newlyDownloadedPaths.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Updating device media index...")
                    }
                    withContext(Dispatchers.IO) {
                        MediaScannerHelper.scanFiles(this@MainActivity, newlyDownloadedPaths)
                    }
                }

                // Save synced playlist into local playlist store
                playlistManager.syncRemotePlaylist(playlist.name, finalTrackPaths)

                // Refresh UI
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    loadLocalLibrary()
                    switchPlaylistMode(MODE_LOCAL)
                    loadLocalPlaylists()

                    val summary = "Synced \"${playlist.name}\": $downloadedCount downloaded, $skippedCount skipped"
                    Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
                    logAdapter.addLog("[SYNC-COMPLETE] $summary")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                    logAdapter.addLog("[SYNC-ERROR] Playlist sync error: ${e.message}")
                }
            }
        }
    }

    private fun promptCreatePlaylistDialog(isRemote: Boolean, onCreated: ((Playlist) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Playlist Name (e.g. Chill, Workout)"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
        }
        val container = FrameLayout(this).apply {
            setPadding(48, 16, 48, 16)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(if (isRemote) "New Desktop Playlist" else "New Local Playlist")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (isRemote) {
                        val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
                        val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
                        lifecycleScope.launch {
                            try {
                                val pl = apiClient.createPlaylist(ip, port, name)
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Created playlist: $name", Toast.LENGTH_SHORT).show()
                                    onCreated?.invoke(pl)
                                    if (currentTab == TAB_PLAYLISTS) loadPlaylists()
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        val pl = playlistManager.createPlaylist(name)
                        Toast.makeText(this, "Created playlist: $name", Toast.LENGTH_SHORT).show()
                        onCreated?.invoke(pl)
                        if (currentTab == TAB_PLAYLISTS) loadPlaylists()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importPowerampBackup(uri: Uri) {
        Toast.makeText(this, "Importing Poweramp backup...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val importer = PowerampImporter(applicationContext, playlistManager)
                val report = importer.importFromUri(uri, localSongs)
                withContext(Dispatchers.Main) {
                    loadPlaylists()
                    Toast.makeText(
                        this@MainActivity,
                        "Imported ${report.totalPlaylists} playlists! ${report.matchedCount} songs matched.",
                        Toast.LENGTH_LONG
                    ).show()
                    showAbsentSongsDialog(report)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Poweramp import failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showAbsentSongsDialog(report: PowerampImportReport) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_absent_songs, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Bind stats
        dialogView.findViewById<TextView>(R.id.tv_stat_playlists)?.text = report.totalPlaylists.toString()
        dialogView.findViewById<TextView>(R.id.tv_stat_matched)?.text = report.matchedCount.toString()
        dialogView.findViewById<TextView>(R.id.tv_stat_absent)?.text = report.absentCount.toString()
        dialogView.findViewById<TextView>(R.id.tv_stat_liked)?.text = report.likedCount.toString()

        val tvSectionTitle = dialogView.findViewById<TextView>(R.id.tv_absent_section_title)
        tvSectionTitle?.text = "Absent Songs (${report.absentCount})"

        val rvAbsent = dialogView.findViewById<RecyclerView>(R.id.rv_absent_songs)

        // onUseMatch: add the resolved song to the absent item's playlist via PlaylistManager
        val adapter = AbsentSongsAdapter(
            localSongs = localSongs,
            onUseMatch = { absentSong, matchedSong ->
                playlistManager.syncRemotePlaylist(
                    name = absentSong.playlistName,
                    trackFilepaths = listOf(matchedSong.filepath)
                )
                Toast.makeText(
                    this,
                    "✓ Added \"${matchedSong.title.ifBlank { matchedSong.filename }}\" → ${absentSong.playlistName}",
                    Toast.LENGTH_SHORT
                ).show()
                // Refresh playlist view if open
                loadPlaylists()
            }
        )
        rvAbsent?.layoutManager = LinearLayoutManager(this)
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
            startActivity(Intent.createChooser(shareIntent, "Share Absent Songs Report"))
        }

        dialog.show()
    }


    private fun showAddToPlaylistDialog(song: Song) {
        if (libraryMode == MODE_REMOTE) {
            val ip = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
            val port = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
            lifecycleScope.launch {
                val playlists = try {
                    apiClient.fetchPlaylists(ip, port)
                } catch (e: Exception) {
                    emptyList()
                }

                runOnUiThread {
                    val names = mutableListOf("+ Create New Playlist")
                    names.addAll(playlists.map { "${it.name} (${it.trackCount} tracks)" })

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Add to Desktop Playlist")
                        .setItems(names.toTypedArray()) { _, which ->
                            if (which == 0) {
                                promptCreatePlaylistDialog(isRemote = true) { newPl ->
                                    lifecycleScope.launch {
                                        apiClient.addTrackToPlaylist(ip, port, newPl.id, song.filepath)
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "Added to ${newPl.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                val selectedPl = playlists[which - 1]
                                lifecycleScope.launch {
                                    val ok = apiClient.addTrackToPlaylist(ip, port, selectedPl.id, song.filepath)
                                    runOnUiThread {
                                        if (ok) {
                                            Toast.makeText(this@MainActivity, "Added to ${selectedPl.name}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "Failed to add track", Toast.LENGTH_SHORT).show()
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

            AlertDialog.Builder(this)
                .setTitle("Add to Local Playlist")
                .setItems(names.toTypedArray()) { _, which ->
                    if (which == 0) {
                        promptCreatePlaylistDialog(isRemote = false) { newPl ->
                            playlistManager.addTrack(newPl.id, song.filepath)
                            Toast.makeText(this, "Added to ${newPl.name}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val selectedPl = localLists[which - 1]
                        val added = playlistManager.addTrack(selectedPl.id, song.filepath)
                        if (added) {
                            Toast.makeText(this, "Added to ${selectedPl.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Already in playlist", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ==========================================
    // PERMISSIONS & LIFECYCLE
    // ==========================================

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            loadLocalLibrary()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                logAdapter.addLog("[PERMISSIONS] Storage permissions granted.")
                loadLocalLibrary()
            } else {
                logAdapter.addLog("[WARN] Storage permissions denied. Library scan may be empty.")
                Toast.makeText(this, "Storage permission is required to read & save music", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        PeerDiscoveryManager.stopListener()
        audioPlayer.release()
        super.onDestroy()
    }
}
