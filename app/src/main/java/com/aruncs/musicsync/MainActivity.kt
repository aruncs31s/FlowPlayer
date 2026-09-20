package com.aruncs.musicsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2
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
import com.aruncs.musicsync.player.AudioInfoHelper
import com.aruncs.musicsync.player.PlayableItem
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import androidx.activity.OnBackPressedCallback
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

    // ViewPager2 for tab views
    private lateinit var viewPager: ViewPager2
    private lateinit var tvMainIpBadge: TextView

    // Bottom Navigation (5 Tabs: Sync, Library, Player, Playlists, Logs)
    private lateinit var navTabSync: LinearLayout
    private lateinit var navTabLibrary: LinearLayout
    private lateinit var navTabPlayer: LinearLayout
    private lateinit var navTabPlaylists: LinearLayout
    private lateinit var navTabLogs: LinearLayout
    private lateinit var ivTabSync: ImageView
    private lateinit var tvTabSync: TextView
    private lateinit var ivTabLibrary: ImageView
    private lateinit var tvTabLibrary: TextView
    private lateinit var ivTabPlayer: ImageView
    private lateinit var tvTabPlayer: TextView
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
    private lateinit var playerView: View
    private lateinit var playlistsView: View
    private lateinit var logsView: View

    // Main Player Screen Views (fragment_player)
    private var layoutFpEmpty: View? = null
    private var btnFpEmptyBrowse: View? = null
    private var layoutFpActive: View? = null
    private var tvFpQueuePos: TextView? = null
    private var btnFpInfo: ImageButton? = null
    private var btnFpQueueToggle: ImageButton? = null
    private var layoutFpAlbumArt: FrameLayout? = null
    private var ivFpArtwork: ImageView? = null
    private var layoutFpSongInfo: LinearLayout? = null
    private var tvFpTitle: TextView? = null
    private var tvFpArtist: TextView? = null
    private var tvFpAlbum: TextView? = null
    private var tvFpBadgeFormat: TextView? = null
    private var tvFpBadgeBitrate: TextView? = null
    private var tvFpBadgeSamplerate: TextView? = null
    private var tvFpBadgeSource: TextView? = null
    private var fpSeekbar: SeekBar? = null
    private var tvFpCurrentTime: TextView? = null
    private var tvFpTotalTime: TextView? = null
    private var btnFpShuffle: ImageButton? = null
    private var btnFpPrev: ImageButton? = null
    private var btnFpPlayPause: ImageButton? = null
    private var btnFpNext: ImageButton? = null
    private var btnFpRepeat: ImageButton? = null
    private var btnFpLike: ImageButton? = null
    private var btnFpAddPlaylist: ImageButton? = null
    private var btnFpDownload: ImageButton? = null
    private var btnFpAudioInfo: TextView? = null
    private var btnFpOptions: ImageButton? = null
    private var tvFpQueueCount: TextView? = null
    private var rvFpQueue: RecyclerView? = null
    private lateinit var fpQueueAdapter: QueueAdapter

    // Sync Tab Views
    private var badgeServerStatus: TextView? = null
    private var tvServerAddress: TextView? = null
    private var btnToggleServer: Button? = null
    private var etDesktopIp: EditText? = null
    private var etDesktopPort: EditText? = null
    private var btnAutoDiscover: Button? = null
    private var btnPingDesktop: Button? = null
    private var tvDesktopStatus: TextView? = null
    private var layoutSyncDevicesList: LinearLayout? = null
    private var badgeDevicesCount: TextView? = null
    private var pbDevicesScanning: ProgressBar? = null
    private val discoveredSyncDevices = mutableListOf<com.aruncs.musicsync.server.DiscoveredPeer>()
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
    private var btnDetailSyncPlaylist: TextView? = null
    private var tvDetailEmpty: TextView? = null
    private var rvPlaylistTracks: RecyclerView? = null
    private lateinit var playlistsAdapter: PlaylistsAdapter
    private lateinit var playlistDetailAdapter: SongsAdapter

    // Peer Selector Views (Playlists Tab)
    private var layoutPeerSelectorBar: LinearLayout? = null
    private var tvActivePeerInfo: TextView? = null
    private var btnChangePeer: TextView? = null
    private var activePeerIp: String? = null
    private var activePeerPort: Int? = null
    private var activePeerName: String? = null

    // Logs Tab Views
    private var rvLogs: RecyclerView? = null
    private var btnLogsClear: Button? = null
    private var btnLogsCrash: Button? = null
    private val logAdapter = LogAdapter()

    private var currentTab = TAB_SYNC
    private var libraryMode = MODE_LOCAL
    private var playlistMode = MODE_LOCAL
    private var localSongs: List<Song> = emptyList()
        set(value) {
            field = value
            localSongMap = value.associateBy { it.filename.lowercase(Locale.US) }
        }
    private var localSongMap: Map<String, Song> = emptyMap()
    private var remoteSongs: List<Song> = emptyList()
    private var localPlaylists: List<Playlist> = emptyList()
    private var remotePlaylists: List<Playlist> = emptyList()
    private var selectedPlaylist: Playlist? = null
    private var playlistTracks: List<Song> = emptyList()

    // Now Playing & Audio Info Dialog
    private var nowPlayingDialog: BottomSheetDialog? = null
    private var nowPlayingUpdateCallback: ((Song) -> Unit)? = null
    private var nowPlayingStateCallback: ((Boolean) -> Unit)? = null
    private var nowPlayingProgressCallback: ((Int, Int) -> Unit)? = null
    private var nowPlayingModeCallback: ((Boolean, RepeatMode) -> Unit)? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2001
        private const val TAB_SYNC = 0
        private const val TAB_LIBRARY = 1
        private const val TAB_PLAYER = 2
        private const val TAB_PLAYLISTS = 3
        private const val TAB_LOGS = 4
        private const val MODE_LOCAL = 0
        private const val MODE_REMOTE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        com.aruncs.musicsync.data.CrashLogger.init(applicationContext)
        com.aruncs.musicsync.data.CrashLogger.onPlayerErrorLogged = { logMsg ->
            runOnUiThread {
                logAdapter.addLog(logMsg)
                rvLogs?.scrollToPosition(logAdapter.itemCount - 1)
            }
        }

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

        val prevCrash = com.aruncs.musicsync.data.CrashLogger.checkAndClearPreviousCrash(this)
        if (prevCrash != null) {
            logAdapter.addLog("[CRASH DETECTED] Previous crash: $prevCrash (Tap 'Crash Dump' in Logs tab for details)")
        }

        if (NetworkUtils.isWifiConnected(this)) {
            autoDiscoverDesktop(showToast = false)
        }
        PeerDiscoveryManager.startListener(this) { logMsg ->
            runOnUiThread { logAdapter.addLog(logMsg) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layoutPlaylistDetail?.visibility == View.VISIBLE) {
                    closePlaylistDetail()
                } else if (viewPager.currentItem != TAB_SYNC) {
                    switchTab(TAB_SYNC)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        switchTab(TAB_SYNC, smoothScroll = false)
    }

    private fun initViews() {
        viewPager = findViewById(R.id.view_pager)
        tvMainIpBadge = findViewById(R.id.tv_main_ip_badge)

        navTabSync = findViewById(R.id.nav_tab_sync)
        navTabLibrary = findViewById(R.id.nav_tab_library)
        navTabPlayer = findViewById(R.id.nav_tab_player)
        navTabPlaylists = findViewById(R.id.nav_tab_playlists)
        navTabLogs = findViewById(R.id.nav_tab_logs)

        ivTabSync = findViewById(R.id.iv_tab_sync)
        tvTabSync = findViewById(R.id.tv_tab_sync)
        ivTabLibrary = findViewById(R.id.iv_tab_library)
        tvTabLibrary = findViewById(R.id.tv_tab_library)
        ivTabPlayer = findViewById(R.id.iv_tab_player)
        tvTabPlayer = findViewById(R.id.tv_tab_player)
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
        syncView = inflater.inflate(R.layout.fragment_sync, viewPager, false)
        badgeServerStatus = syncView.findViewById(R.id.badge_server_status)
        tvServerAddress = syncView.findViewById(R.id.tv_server_address)
        btnToggleServer = syncView.findViewById(R.id.btn_toggle_server)
        etDesktopIp = syncView.findViewById(R.id.et_desktop_ip)
        etDesktopPort = syncView.findViewById(R.id.et_desktop_port)
        btnAutoDiscover = syncView.findViewById(R.id.btn_auto_discover)
        btnPingDesktop = syncView.findViewById(R.id.btn_ping_desktop)
        tvDesktopStatus = syncView.findViewById(R.id.tv_desktop_status)
        layoutSyncDevicesList = syncView.findViewById(R.id.layout_sync_devices_list)
        badgeDevicesCount = syncView.findViewById(R.id.badge_devices_count)
        pbDevicesScanning = syncView.findViewById(R.id.pb_devices_scanning)
        btnSyncPull = syncView.findViewById(R.id.btn_sync_pull)
        btnSyncPush = syncView.findViewById(R.id.btn_sync_push)
        layoutSyncProgress = syncView.findViewById(R.id.layout_sync_progress)
        tvSyncStatusLabel = syncView.findViewById(R.id.tv_sync_status_label)
        tvSyncPercent = syncView.findViewById(R.id.tv_sync_percent)
        pbSync = syncView.findViewById(R.id.pb_sync)
        tvSyncDetail = syncView.findViewById(R.id.tv_sync_detail)

        etDesktopIp?.setText(prefs.desktopIp)
        etDesktopPort?.setText(prefs.desktopPort.toString())
        renderSyncDevicesList()

        // 2. Library View
        libraryView = inflater.inflate(R.layout.fragment_library, viewPager, false)
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
                        createPlayableItem(s, libraryMode == MODE_REMOTE)
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
                val item = createPlayableItem(song, libraryMode == MODE_REMOTE)
                audioPlayer.addToQueue(item.song, item.streamUrl)
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

        // 3. Player View (Main Player Screen)
        playerView = inflater.inflate(R.layout.fragment_player, viewPager, false)
        layoutFpEmpty = playerView.findViewById(R.id.layout_fp_empty)
        btnFpEmptyBrowse = playerView.findViewById(R.id.btn_fp_empty_browse)
        layoutFpActive = playerView.findViewById(R.id.layout_fp_active)
        tvFpQueuePos = playerView.findViewById(R.id.tv_fp_queue_pos)
        btnFpInfo = playerView.findViewById(R.id.btn_fp_info)
        btnFpQueueToggle = playerView.findViewById(R.id.btn_fp_queue_toggle)
        layoutFpAlbumArt = playerView.findViewById(R.id.layout_fp_album_art)
        ivFpArtwork = playerView.findViewById(R.id.iv_fp_artwork)
        layoutFpSongInfo = playerView.findViewById(R.id.layout_fp_song_info)
        tvFpTitle = playerView.findViewById(R.id.tv_fp_title)
        tvFpArtist = playerView.findViewById(R.id.tv_fp_artist)
        tvFpAlbum = playerView.findViewById(R.id.tv_fp_album)
        tvFpBadgeFormat = playerView.findViewById(R.id.tv_fp_badge_format)
        tvFpBadgeBitrate = playerView.findViewById(R.id.tv_fp_badge_bitrate)
        tvFpBadgeSamplerate = playerView.findViewById(R.id.tv_fp_badge_samplerate)
        tvFpBadgeSource = playerView.findViewById(R.id.tv_fp_badge_source)
        fpSeekbar = playerView.findViewById(R.id.fp_seekbar)
        tvFpCurrentTime = playerView.findViewById(R.id.tv_fp_current_time)
        tvFpTotalTime = playerView.findViewById(R.id.tv_fp_total_time)
        btnFpShuffle = playerView.findViewById(R.id.btn_fp_shuffle)
        btnFpPrev = playerView.findViewById(R.id.btn_fp_prev)
        btnFpPlayPause = playerView.findViewById(R.id.btn_fp_play_pause)
        btnFpNext = playerView.findViewById(R.id.btn_fp_next)
        btnFpRepeat = playerView.findViewById(R.id.btn_fp_repeat)
        btnFpLike = playerView.findViewById(R.id.btn_fp_like)
        btnFpAddPlaylist = playerView.findViewById(R.id.btn_fp_add_playlist)
        btnFpDownload = playerView.findViewById(R.id.btn_fp_download)
        btnFpAudioInfo = playerView.findViewById(R.id.btn_fp_audio_info)
        btnFpOptions = playerView.findViewById(R.id.btn_fp_options)
        tvFpQueueCount = playerView.findViewById(R.id.tv_fp_queue_count)
        rvFpQueue = playerView.findViewById(R.id.rv_fp_queue)

        tvFpTitle?.isSelected = true

        fpQueueAdapter = QueueAdapter(
            onItemClick = { index ->
                audioPlayer.playTrackAtIndex(index)
            }
        )
        rvFpQueue?.layoutManager = LinearLayoutManager(this)
        rvFpQueue?.adapter = fpQueueAdapter

        // 4. Playlists View
        playlistsView = inflater.inflate(R.layout.fragment_playlists, viewPager, false)
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
                    createPlayableItem(s, isRemote)
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
                val item = createPlayableItem(song, isRemote)
                audioPlayer.addToQueue(item.song, item.streamUrl)
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
        logsView = inflater.inflate(R.layout.fragment_logs, viewPager, false)
        rvLogs = logsView.findViewById(R.id.rv_logs)
        btnLogsClear = logsView.findViewById(R.id.btn_logs_clear)
        btnLogsCrash = logsView.findViewById(R.id.btn_logs_crash)

        rvLogs?.layoutManager = LinearLayoutManager(this)
        rvLogs?.adapter = logAdapter

        // Setup ViewPager2 swipe navigation
        setupViewPager()
    }

    private fun setupListeners() {
        // Navigation tabs
        navTabSync.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabSync clicked"); switchTab(TAB_SYNC) }
        ivTabSync.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabSync clicked"); switchTab(TAB_SYNC) }
        tvTabSync.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabSync clicked"); switchTab(TAB_SYNC) }

        navTabLibrary.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabLibrary clicked"); switchTab(TAB_LIBRARY) }
        ivTabLibrary.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabLibrary clicked"); switchTab(TAB_LIBRARY) }
        tvTabLibrary.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabLibrary clicked"); switchTab(TAB_LIBRARY) }

        navTabPlayer.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabPlayer clicked"); switchTab(TAB_PLAYER) }
        ivTabPlayer.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabPlayer clicked"); switchTab(TAB_PLAYER) }
        tvTabPlayer.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabPlayer clicked"); switchTab(TAB_PLAYER) }

        navTabPlaylists.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabPlaylists clicked"); switchTab(TAB_PLAYLISTS) }
        ivTabPlaylists.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabPlaylists clicked"); switchTab(TAB_PLAYLISTS) }
        tvTabPlaylists.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabPlaylists clicked"); switchTab(TAB_PLAYLISTS) }

        navTabLogs.setOnClickListener { android.util.Log.d("NAV_TAB", "navTabLogs clicked"); switchTab(TAB_LOGS) }
        ivTabLogs.setOnClickListener { android.util.Log.d("NAV_TAB", "ivTabLogs clicked"); switchTab(TAB_LOGS) }
        tvTabLogs.setOnClickListener { android.util.Log.d("NAV_TAB", "tvTabLogs clicked"); switchTab(TAB_LOGS) }

        // Main Player Screen listeners
        btnFpEmptyBrowse?.setOnClickListener { switchTab(TAB_LIBRARY) }
        btnFpPlayPause?.setOnClickListener { audioPlayer.togglePlayPause() }
        btnFpPrev?.setOnClickListener { audioPlayer.playPrevious() }
        btnFpNext?.setOnClickListener { audioPlayer.playNext() }
        btnFpShuffle?.setOnClickListener {
            val s = audioPlayer.toggleShuffle()
            updateShuffleButton(s)
            updatePlayerScreenControls()
            Toast.makeText(this, if (s) "Shuffle On" else "Shuffle Off", Toast.LENGTH_SHORT).show()
        }
        btnFpRepeat?.setOnClickListener {
            val m = audioPlayer.toggleRepeat()
            updateRepeatButton(m)
            updatePlayerScreenControls()
            val msg = when (m) {
                RepeatMode.OFF -> "Repeat Off"
                RepeatMode.ALL -> "Repeat All"
                RepeatMode.ONE -> "Repeat One"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        btnFpLike?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                toggleSongLiked(song)
                updatePlayerScreenLike(song)
            }
        }
        btnFpAddPlaylist?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                showAddToPlaylistDialog(song)
            }
        }
        btnFpDownload?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                val ip = getTargetPeerIp()
                val port = getTargetPeerPort()
                if (ip.isBlank() || ip == "0.0.0.0") {
                    Toast.makeText(this, "No peer connected — cannot download", Toast.LENGTH_SHORT).show()
                } else {
                    downloadRemoteSong(song)
                }
            }
        }
        btnFpOptions?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                showSongOptionsDialog(song)
            }
        }
        btnFpAudioInfo?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                showAudioInfoDialog(song)
            }
        }
        layoutFpAlbumArt?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                showAudioInfoDialog(song)
            }
        }
        layoutFpSongInfo?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                showAudioInfoDialog(song)
            }
        }
        btnFpInfo?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                showAudioInfoDialog(song)
            }
        }
        btnFpQueueToggle?.setOnClickListener {
            showQueueDialog()
        }
        var pendingFpSeekRatio: Float? = null
        fpSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = progress.toFloat() / 1000f
                    pendingFpSeekRatio = ratio
                    val dur = audioPlayer.duration
                    if (dur > 0) {
                        val curMs = (dur * ratio).toInt()
                        val curMin = curMs / 1000 / 60
                        val curSec = (curMs / 1000) % 60
                        tvFpCurrentTime?.text = String.format(Locale.US, "%02d:%02d", curMin, curSec)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                pendingFpSeekRatio?.let { audioPlayer.seekTo(it) }
                pendingFpSeekRatio = null
            }
        })

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
            try {
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        try {
                            logAdapter.addLog(logMsg)
                            val count = logAdapter.itemCount
                            if (count > 0) {
                                rvLogs?.post {
                                    try {
                                        rvLogs?.scrollToPosition(count - 1)
                                    } catch (ignored: Throwable) {}
                                }
                            }
                        } catch (t: Throwable) {}
                    }
                }
            } catch (ignored: Throwable) {}
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
        btnChangePeer?.setOnClickListener { showPeerSelectionDialog() }
        btnDetailBack?.setOnClickListener { closePlaylistDetail() }
        btnDetailPlayAll?.setOnClickListener {
            selectedPlaylist?.let { pl -> playPlaylist(pl) }
        }
        btnDetailSyncPlaylist?.setOnClickListener {
            selectedPlaylist?.let { pl -> syncRemotePlaylistWithQuality(pl) }
        }

        btnLogsClear?.setOnClickListener {
            logAdapter.clear()
        }

        btnLogsCrash?.setOnClickListener {
            val logs = com.aruncs.musicsync.data.CrashLogger.getCrashLogs(this)
            AlertDialog.Builder(this)
                .setTitle("Player & Crash Logs")
                .setMessage(logs)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy Logs") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Crash Logs", logs)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Clear") { _, _ ->
                    com.aruncs.musicsync.data.CrashLogger.clearLogs(this)
                    Toast.makeText(this, "Crash logs cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
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
            updatePlayerScreenUI(null)
            nowPlayingDialog?.dismiss()
        }

        // Tapping player bar or title/artist opens the Main Player Screen
        findViewById<View>(R.id.layout_player_track_info)?.setOnClickListener { switchTab(TAB_PLAYER) }
        tvPlayerTitle?.setOnClickListener { switchTab(TAB_PLAYER) }
        tvPlayerArtist?.setOnClickListener { switchTab(TAB_PLAYER) }
        playerBarContainer.setOnClickListener { switchTab(TAB_PLAYER) }

        // Long-pressing player bar or title/artist opens Poweramp Audio Info & Tags directly
        tvPlayerTitle?.setOnLongClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
            true
        }
        tvPlayerArtist?.setOnLongClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
            true
        }
        playerBarContainer.setOnLongClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
            true
        }

        var pendingPlayerSeekRatio: Float? = null
        playerSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    pendingPlayerSeekRatio = progress.toFloat() / 1000f
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                pendingPlayerSeekRatio?.let { audioPlayer.seekTo(it) }
                pendingPlayerSeekRatio = null
            }
        })

        audioPlayer.onTrackChanged = { song ->
            runOnUiThread {
                if (currentTab != TAB_PLAYER) {
                    playerBarContainer.visibility = View.VISIBLE
                }
                tvPlayerTitle?.text = song.title.ifBlank { song.filename }
                tvPlayerArtist?.text = song.artist.ifBlank { "Unknown Artist" }
                btnPlayerPlayPause?.setImageResource(R.drawable.ic_pause)
                songsAdapter.setActiveSong(song, true)
                playlistDetailAdapter.setActiveSong(song, true)
                updatePlayerControlsState()
                updatePlayerLikeButton(song)
                updatePlayerScreenUI(song)
                nowPlayingUpdateCallback?.invoke(song)
            }
        }

        audioPlayer.onStateChanged = { isPlaying ->
            runOnUiThread {
                btnPlayerPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                songsAdapter.setActiveSong(audioPlayer.currentSong, isPlaying)
                playlistDetailAdapter.setActiveSong(audioPlayer.currentSong, isPlaying)
                updatePlayerControlsState()
                updatePlayerLikeButton(audioPlayer.currentSong)
                updatePlayerScreenControls()
                nowPlayingStateCallback?.invoke(isPlaying)
            }
        }

        audioPlayer.onQueueChanged = { _, _ ->
            runOnUiThread {
                updatePlayerControlsState()
                updatePlayerScreenQueue()
                audioPlayer.currentSong?.let { nowPlayingUpdateCallback?.invoke(it) }
            }
        }

        audioPlayer.onModeChanged = { isShuffled, repeatMode ->
            runOnUiThread {
                updateShuffleButton(isShuffled)
                updateRepeatButton(repeatMode)
                updatePlayerScreenControls()
                nowPlayingModeCallback?.invoke(isShuffled, repeatMode)
            }
        }

        audioPlayer.onProgress = { currentMs, totalMs ->
            runOnUiThread {
                if (totalMs > 0) {
                    val ratio = (currentMs.toFloat() / totalMs.toFloat()) * 1000f
                    playerSeekbar?.progress = ratio.toInt()
                    fpSeekbar?.progress = ratio.toInt()

                    val curMin = currentMs / 1000 / 60
                    val curSec = (currentMs / 1000) % 60
                    val totMin = totalMs / 1000 / 60
                    val totSec = (totalMs / 1000) % 60
                    tvPlayerTime?.text = String.format(Locale.US, "%02d:%02d / %02d:%02d", curMin, curSec, totMin, totSec)
                    tvFpCurrentTime?.text = String.format(Locale.US, "%02d:%02d", curMin, curSec)
                    tvFpTotalTime?.text = String.format(Locale.US, "%02d:%02d", totMin, totSec)
                    nowPlayingProgressCallback?.invoke(currentMs, totalMs)
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

    private fun updatePlayerScreenUI(song: Song?) {
        if (song == null) {
            layoutFpEmpty?.visibility = View.VISIBLE
            layoutFpActive?.visibility = View.GONE
            return
        }
        layoutFpEmpty?.visibility = View.GONE
        layoutFpActive?.visibility = View.VISIBLE

        val isRemote = audioPlayer.currentItem?.isRemote ?: (libraryMode == MODE_REMOTE)
        val ext = File(song.filepath).extension.uppercase(Locale.US).ifBlank { "MP3" }

        tvFpTitle?.text = song.title.ifBlank { song.filename }
        tvFpArtist?.text = song.artist.ifBlank { "Unknown Artist" }
        tvFpAlbum?.text = if (song.album.isNotBlank() && song.album != "Unknown Album") song.album else "Music Sync Library"

        tvFpBadgeFormat?.text = ext
        tvFpBadgeBitrate?.text = song.bitrateKbps.ifBlank { "320 kbps" }.replace(" (CBR)", "").replace(" (Lossless)", "")
        tvFpBadgeSamplerate?.text = "44.1 kHz"

        if (isRemote) {
            tvFpBadgeSource?.text = "OVER-IP"
            tvFpBadgeSource?.setTextColor(ContextCompat.getColor(this, R.color.yellow_primary))
            tvFpBadgeSource?.setBackgroundResource(R.drawable.bg_badge_yellow)
            // Show download button; dim it if already saved locally
            val alreadyLocal = localSongMap.containsKey(song.filename.lowercase(Locale.US)) ||
                    localSongs.any { it.filename.equals(song.filename, ignoreCase = true) }
            btnFpDownload?.visibility = View.VISIBLE
            if (alreadyLocal) {
                btnFpDownload?.setColorFilter(ContextCompat.getColor(this, R.color.status_online))
                btnFpDownload?.contentDescription = "Already on Device"
                btnFpDownload?.alpha = 0.55f
            } else {
                btnFpDownload?.setColorFilter(ContextCompat.getColor(this, R.color.yellow_primary))
                btnFpDownload?.contentDescription = "Download to Device"
                btnFpDownload?.alpha = 1.0f
            }
        } else {
            tvFpBadgeSource?.text = "LOCAL"
            tvFpBadgeSource?.setTextColor(ContextCompat.getColor(this, R.color.status_online))
            tvFpBadgeSource?.setBackgroundResource(R.drawable.bg_badge_green)
            btnFpDownload?.visibility = View.GONE
        }

        updatePlayerScreenQueue()
        updatePlayerScreenLike(song)
        updatePlayerScreenControls()

        // Asynchronously enrich audio technical details in background without blocking main thread
        lifecycleScope.launch(Dispatchers.IO) {
            val streamUrl = if (isRemote) getStreamUrl(song) else null
            val details = AudioInfoHelper.extract(song, streamUrl)
            withContext(Dispatchers.Main) {
                if (audioPlayer.currentSong?.id == song.id) {
                    tvFpBadgeFormat?.text = details.containerFormat
                    tvFpBadgeBitrate?.text = details.bitrateKbps.replace(" (CBR)", "").replace(" (Lossless)", "")
                    tvFpBadgeSamplerate?.text = if (details.sampleRateHz.contains("(")) {
                        details.sampleRateHz.substringAfter("(").substringBefore(")")
                    } else {
                        details.sampleRateHz
                    }
                }
            }
        }
    }

    private fun updatePlayerScreenQueue() {
        val q = audioPlayer.queue
        val qIdx = audioPlayer.currentIndex
        if (q.isNotEmpty() && qIdx >= 0) {
            tvFpQueuePos?.text = "Track ${qIdx + 1} of ${q.size}"
            tvFpQueueCount?.text = "${q.size} tracks"
        } else {
            tvFpQueuePos?.text = "Now Playing"
            tvFpQueueCount?.text = "0 tracks"
        }
        fpQueueAdapter.submitQueue(q, qIdx)
    }

    private fun updatePlayerScreenControls() {
        btnFpPlayPause?.setImageResource(if (audioPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnFpShuffle?.setColorFilter(if (audioPlayer.isShuffled) ContextCompat.getColor(this, R.color.yellow_primary) else ContextCompat.getColor(this, R.color.text_muted))
        when (audioPlayer.repeatMode) {
            RepeatMode.OFF -> {
                btnFpRepeat?.setImageResource(R.drawable.ic_repeat)
                btnFpRepeat?.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            }
            RepeatMode.ALL -> {
                btnFpRepeat?.setImageResource(R.drawable.ic_repeat)
                btnFpRepeat?.setColorFilter(ContextCompat.getColor(this, R.color.yellow_primary))
            }
            RepeatMode.ONE -> {
                btnFpRepeat?.setImageResource(R.drawable.ic_repeat_one)
                btnFpRepeat?.setColorFilter(ContextCompat.getColor(this, R.color.yellow_primary))
            }
        }
        btnFpPrev?.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
        btnFpNext?.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
    }

    private fun updatePlayerScreenLike(song: Song?) {
        if (song == null) {
            btnFpLike?.setImageResource(R.drawable.ic_favorite_border)
            btnFpLike?.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
            return
        }
        val isLiked = playlistManager.isLiked(song)
        btnFpLike?.setImageResource(if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        btnFpLike?.setColorFilter(if (isLiked) Color.parseColor("#FFFF0055") else ContextCompat.getColor(this, R.color.text_muted))
    }

    private fun setupViewPager() {
        val tabViews = listOf(syncView, libraryView, playerView, playlistsView, logsView)
        viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = tabViews.size

            override fun getItemViewType(position: Int): Int = position

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val container = FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val pageView = tabViews[viewType]
                (pageView.parent as? ViewGroup)?.removeView(pageView)
                container.addView(pageView)
                return object : RecyclerView.ViewHolder(container) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as FrameLayout
                val pageView = tabViews[position]
                if (pageView.parent != container) {
                    (pageView.parent as? ViewGroup)?.removeView(pageView)
                    container.removeAllViews()
                    container.addView(pageView)
                }
            }
        }
        viewPager.offscreenPageLimit = 4
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateNavTabUI(position)
                onTabActivated(position)
            }
        })
    }

    private fun updateNavTabUI(tab: Int) {
        currentTab = tab

        val yellow = ContextCompat.getColor(this, R.color.yellow_primary)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        ivTabSync.setColorFilter(if (tab == TAB_SYNC) yellow else muted)
        tvTabSync.setTextColor(if (tab == TAB_SYNC) yellow else muted)

        ivTabLibrary.setColorFilter(if (tab == TAB_LIBRARY) yellow else muted)
        tvTabLibrary.setTextColor(if (tab == TAB_LIBRARY) yellow else muted)

        ivTabPlayer.setColorFilter(if (tab == TAB_PLAYER) yellow else muted)
        tvTabPlayer.setTextColor(if (tab == TAB_PLAYER) yellow else muted)

        ivTabPlaylists.setColorFilter(if (tab == TAB_PLAYLISTS) yellow else muted)
        tvTabPlaylists.setTextColor(if (tab == TAB_PLAYLISTS) yellow else muted)

        ivTabLogs.setColorFilter(if (tab == TAB_LOGS) yellow else muted)
        tvTabLogs.setTextColor(if (tab == TAB_LOGS) yellow else muted)
    }

    private fun onTabActivated(tab: Int) {
        when (tab) {
            TAB_SYNC -> { /* No-op */ }
            TAB_LIBRARY -> {
                if (libraryMode == MODE_LOCAL && localSongs.isEmpty()) {
                    loadLocalLibrary()
                } else if (libraryMode == MODE_REMOTE && remoteSongs.isEmpty()) {
                    loadRemoteLibrary()
                }
            }
            TAB_PLAYER -> {
                playerBarContainer.visibility = View.GONE
                updatePlayerScreenUI(audioPlayer.currentSong)
            }
            TAB_PLAYLISTS -> {
                loadPlaylists()
            }
            TAB_LOGS -> { /* No-op */ }
        }

        if (tab != TAB_PLAYER) {
            playerBarContainer.visibility = if (audioPlayer.currentSong != null) View.VISIBLE else View.GONE
        }
    }

    private fun switchTab(tab: Int, smoothScroll: Boolean = true) {
        if (viewPager.currentItem != tab) {
            viewPager.setCurrentItem(tab, smoothScroll)
        } else {
            updateNavTabUI(tab)
            onTabActivated(tab)
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

    private fun renderSyncDevicesList() {
        val container = layoutSyncDevicesList ?: return
        container.removeAllViews()

        if (discoveredSyncDevices.isEmpty()) {
            badgeDevicesCount?.visibility = View.GONE
            return
        }

        badgeDevicesCount?.text = "${discoveredSyncDevices.size} Found"
        badgeDevicesCount?.visibility = View.VISIBLE

        val currentTargetIp = getTargetPeerIp()
        val currentTargetPort = getTargetPeerPort()

        for (peer in discoveredSyncDevices) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_discovered_device, container, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.iv_device_icon)
            val tvName = itemView.findViewById<TextView>(R.id.tv_device_name)
            val tvAddress = itemView.findViewById<TextView>(R.id.tv_device_address)
            val tvRole = itemView.findViewById<TextView>(R.id.tv_device_role_badge)
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_device_status_badge)

            ivIcon?.setImageResource(if (peer.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer)
            tvName.text = peer.hostname
            tvAddress.text = "${peer.ip}:${peer.port}"
            tvRole.text = if (peer.isAndroid) "PHONE" else "DESKTOP"

            val isSelected = peer.ip.equals(currentTargetIp, ignoreCase = true) && peer.port == currentTargetPort

            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_card)
                tvStatus.text = "ACTIVE"
                tvStatus.setBackgroundResource(R.drawable.bg_badge_yellow)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.yellow_primary))
                tvName.setTextColor(ContextCompat.getColor(this, R.color.yellow_primary))
            } else {
                itemView.setBackgroundResource(R.drawable.bg_button_dark)
                tvStatus.text = "CONNECT"
                tvStatus.setBackgroundResource(R.drawable.bg_card)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                tvName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }

            itemView.setOnClickListener {
                selectSyncDevice(peer)
            }

            container.addView(itemView)
        }
    }

    private fun selectSyncDevice(peer: com.aruncs.musicsync.server.DiscoveredPeer) {
        etDesktopIp?.setText(peer.ip)
        etDesktopPort?.setText(peer.port.toString())
        prefs.desktopIp = peer.ip
        prefs.desktopPort = peer.port
        activePeerIp = peer.ip
        activePeerPort = peer.port
        activePeerName = peer.hostname
        updatePeerBarText()

        renderSyncDevicesList()
        pingDesktopServer()
        Toast.makeText(this, "Target set to ${peer.hostname} (${peer.ip}:${peer.port})", Toast.LENGTH_SHORT).show()
    }

    private fun autoDiscoverDesktop(showToast: Boolean = false) {
        scanForNearbyDevices(showToast)
    }

    private fun scanForNearbyDevices(showToast: Boolean = false) {
        pbDevicesScanning?.visibility = View.VISIBLE
        btnAutoDiscover?.isEnabled = false
        tvDesktopStatus?.text = "Scanning Wi-Fi / Hotspot for devices..."
        tvDesktopStatus?.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        logAdapter.addLog("[DISCOVERY] Scanning Wi-Fi / Hotspot for nearby devices...")

        lifecycleScope.launch {
            val peers = com.aruncs.musicsync.server.PeerDiscoveryManager.discoverAllPeers(
                context = this@MainActivity,
                timeoutMs = 2500,
                onPeerFound = { peer ->
                    runOnUiThread {
                        val exists = discoveredSyncDevices.any { it.ip == peer.ip && it.port == peer.port }
                        if (!exists) {
                            discoveredSyncDevices.add(peer)
                            renderSyncDevicesList()
                        }
                    }
                },
                onLog = { msg ->
                    runOnUiThread { logAdapter.addLog(msg) }
                }
            )

            runOnUiThread {
                pbDevicesScanning?.visibility = View.GONE
                btnAutoDiscover?.isEnabled = true

                for (p in peers) {
                    if (discoveredSyncDevices.none { it.ip == p.ip && it.port == p.port }) {
                        discoveredSyncDevices.add(p)
                    }
                }
                renderSyncDevicesList()

                if (discoveredSyncDevices.isNotEmpty()) {
                    tvDesktopStatus?.text = "Discovered ${discoveredSyncDevices.size} device(s) on network."
                    tvDesktopStatus?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_online))

                    val curIp = etDesktopIp?.text?.toString()?.trim() ?: prefs.desktopIp
                    val curPort = etDesktopPort?.text?.toString()?.toIntOrNull() ?: prefs.desktopPort
                    val currentMatch = discoveredSyncDevices.firstOrNull { it.ip.equals(curIp, true) && it.port == curPort }

                    if (currentMatch == null && curIp.isEmpty() && discoveredSyncDevices.isNotEmpty()) {
                        selectSyncDevice(discoveredSyncDevices.first())
                    }

                    if (showToast) {
                        Toast.makeText(this@MainActivity, "Found ${discoveredSyncDevices.size} device(s) on network", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    tvDesktopStatus?.text = "No devices detected. Check Wi-Fi/Hotspot or enter IP manually."
                    tvDesktopStatus?.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    if (showToast) {
                        Toast.makeText(this@MainActivity, "No devices detected via UDP broadcast. Set IP manually.", Toast.LENGTH_SHORT).show()
                    }
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
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val targetName = activePeerName ?: "$ip:$port"

        setSyncInProgress(true, "Comparing tracks with $targetName...")
        logAdapter.addLog("[SYNC-PULL] Starting Pull Sync from $targetName ($ip:$port)...")

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
                        tvSyncStatusLabel?.text = "Library is already in sync with $targetName!"
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
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val targetName = activePeerName ?: "$ip:$port"

        setSyncInProgress(true, "Scanning local library for new songs...")
        logAdapter.addLog("[SYNC-PUSH] Starting Push Sync to $targetName ($ip:$port)...")

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
                        tvSyncStatusLabel?.text = "No new songs to push to $targetName."
                        Toast.makeText(this@MainActivity, "All local songs already on $targetName!", Toast.LENGTH_SHORT).show()
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
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val cleanIp = ip.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val encodedPath = URLEncoder.encode(song.filepath, "UTF-8")
        val encodedName = URLEncoder.encode(song.filename, "UTF-8")
        return "http://$cleanIp:$port/api/song/stream?filepath=$encodedPath&filename=$encodedName"
    }

    private fun createPlayableItem(song: Song, isRemote: Boolean): PlayableItem {
        if (!isRemote) {
            return PlayableItem(song, streamUrl = null)
        }
        // For remote tracks, check if already present locally using our fast in-memory index
        val localMatch = localSongMap[song.filename.lowercase(Locale.US)]
        return if (localMatch != null) {
            PlayableItem(song.copy(filepath = localMatch.filepath), streamUrl = null)
        } else {
            PlayableItem(song, streamUrl = getStreamUrl(song))
        }
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
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val destDir = prefs.musicStorageDirectory
        val destFile = File(destDir, song.filename)
        val quality = prefs.downloadQuality
        val targetName = activePeerName ?: "Peer"

        Toast.makeText(this, "Downloading ${song.title}...", Toast.LENGTH_SHORT).show()
        logAdapter.addLog("[DOWNLOAD] Starting download of '${song.filename}' from $targetName (quality: $quality)...")

        lifecycleScope.launch {
            try {
                val ok = apiClient.downloadSong(ip, port, song.filepath, destFile, bitrate = quality, context = this@MainActivity)
                if (ok) {
                    withContext(Dispatchers.IO) {
                        com.aruncs.musicsync.data.MediaScannerHelper.scanFile(this@MainActivity, destFile.absolutePath)
                        localSongs = MediaStoreHelper.getAllDeviceSongs(this@MainActivity)
                    }
                    runOnUiThread {
                        val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
                        songsAdapter.setRemoteMode(true, localNames)
                        Toast.makeText(this@MainActivity, "Downloaded ${song.title}", Toast.LENGTH_SHORT).show()
                        logAdapter.addLog("[DOWNLOAD] Successfully saved '${destFile.name}' to /Music")
                        // If this song is still the one playing, update the download button to dimmed green
                        if (audioPlayer.currentSong?.id == song.id) {
                            btnFpDownload?.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.status_online))
                            btnFpDownload?.contentDescription = "Already on Device"
                            btnFpDownload?.alpha = 0.55f
                        }
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

        val msg = if (nowLiked) "Added to Liked Music" else "Removed from Liked Music"
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
        val options = arrayOf("Song Info / Audio Tags", "Play Next", "Add to Queue", "Add to Playlist...", "Delete Song")
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                val item = createPlayableItem(song, libraryMode == MODE_REMOTE)
                when (which) {
                    0 -> {
                        showAudioInfoDialog(song)
                    }
                    1 -> {
                        audioPlayer.addToQueueNext(item.song, item.streamUrl)
                        Toast.makeText(this, "Will play next: ${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        audioPlayer.addToQueue(item.song, item.streamUrl)
                        Toast.makeText(this, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        showAddToPlaylistDialog(song)
                    }
                    4 -> {
                        confirmDeleteSong(song)
                    }
                }
            }
            .show()
    }

    private fun showNowPlayingDialog() {
        val currentSong = audioPlayer.currentSong ?: return
        if (nowPlayingDialog?.isShowing == true) return

        val dialog = BottomSheetDialog(this, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_now_playing, null)
        dialog.setContentView(dialogView)

        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val btnCollapse = dialogView.findViewById<ImageButton>(R.id.btn_np_collapse)
        val tvQueuePos = dialogView.findViewById<TextView>(R.id.tv_np_queue_pos)
        val btnInfo = dialogView.findViewById<ImageButton>(R.id.btn_np_info)
        val btnQueue = dialogView.findViewById<ImageButton>(R.id.btn_np_queue)
        val layoutAlbumArt = dialogView.findViewById<FrameLayout>(R.id.layout_np_album_art)
        val layoutSongInfo = dialogView.findViewById<LinearLayout>(R.id.layout_np_song_info)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_np_title)
        val tvArtist = dialogView.findViewById<TextView>(R.id.tv_np_artist)
        val tvAlbum = dialogView.findViewById<TextView>(R.id.tv_np_album)
        val tvBadgeFormat = dialogView.findViewById<TextView>(R.id.tv_np_badge_format)
        val tvBadgeBitrate = dialogView.findViewById<TextView>(R.id.tv_np_badge_bitrate)
        val tvBadgeSamplerate = dialogView.findViewById<TextView>(R.id.tv_np_badge_samplerate)
        val tvBadgeSource = dialogView.findViewById<TextView>(R.id.tv_np_badge_source)
        val npSeekbar = dialogView.findViewById<SeekBar>(R.id.np_seekbar)
        val tvCurrentTime = dialogView.findViewById<TextView>(R.id.tv_np_current_time)
        val tvTotalTime = dialogView.findViewById<TextView>(R.id.tv_np_total_time)
        val btnShuffle = dialogView.findViewById<ImageButton>(R.id.btn_np_shuffle)
        val btnPrev = dialogView.findViewById<ImageButton>(R.id.btn_np_prev)
        val btnPlayPause = dialogView.findViewById<ImageButton>(R.id.btn_np_play_pause)
        val btnNext = dialogView.findViewById<ImageButton>(R.id.btn_np_next)
        val btnRepeat = dialogView.findViewById<ImageButton>(R.id.btn_np_repeat)
        val btnLike = dialogView.findViewById<ImageButton>(R.id.btn_np_like)
        val btnAudioInfo = dialogView.findViewById<TextView>(R.id.btn_np_audio_info)
        val btnOptions = dialogView.findViewById<ImageButton>(R.id.btn_np_options)

        // Enable marquee scrolling for song title
        tvTitle.isSelected = true

        fun updateNowPlayingSongUI(song: Song) {
            val streamUrl = if (libraryMode == MODE_REMOTE) getStreamUrl(song) else null
            val details = AudioInfoHelper.extract(song, streamUrl)

            tvTitle.text = details.title
            tvArtist.text = details.artist
            tvAlbum.text = if (details.album.isNotBlank() && details.album != "Unknown Album") details.album else "Music Sync Library"

            tvBadgeFormat.text = details.containerFormat
            tvBadgeBitrate.text = details.bitrateKbps.replace(" (CBR)", "").replace(" (Lossless)", "")
            tvBadgeSamplerate.text = if (details.sampleRateHz.contains("(")) {
                details.sampleRateHz.substringAfter("(").substringBefore(")")
            } else {
                details.sampleRateHz
            }
            if (details.isRemote) {
                tvBadgeSource.text = "OVER-IP"
                tvBadgeSource.setTextColor(ContextCompat.getColor(this, R.color.yellow_primary))
                tvBadgeSource.setBackgroundResource(R.drawable.bg_badge_yellow)
            } else {
                tvBadgeSource.text = "LOCAL"
                tvBadgeSource.setTextColor(ContextCompat.getColor(this, R.color.status_online))
                tvBadgeSource.setBackgroundResource(R.drawable.bg_badge_green)
            }

            val q = audioPlayer.queue
            val qIdx = audioPlayer.currentIndex
            if (q.isNotEmpty() && qIdx >= 0) {
                tvQueuePos.text = "Track ${qIdx + 1} of ${q.size}"
            } else {
                tvQueuePos.text = "Now Playing"
            }

            val isLiked = playlistManager.isLiked(song)
            btnLike.setImageResource(if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            btnLike.setColorFilter(if (isLiked) ContextCompat.getColor(this, R.color.status_offline) else ContextCompat.getColor(this, R.color.text_muted))

            btnPrev.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
            btnNext.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
        }

        fun updateNowPlayingControls() {
            btnPlayPause.setImageResource(if (audioPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            btnShuffle.setColorFilter(if (audioPlayer.isShuffled) ContextCompat.getColor(this, R.color.yellow_primary) else ContextCompat.getColor(this, R.color.text_muted))
            when (audioPlayer.repeatMode) {
                RepeatMode.OFF -> {
                    btnRepeat.setImageResource(R.drawable.ic_repeat)
                    btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
                }
                RepeatMode.ALL -> {
                    btnRepeat.setImageResource(R.drawable.ic_repeat)
                    btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.yellow_primary))
                }
                RepeatMode.ONE -> {
                    btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                    btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.yellow_primary))
                }
            }
            btnPrev.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
            btnNext.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
        }

        updateNowPlayingSongUI(currentSong)
        updateNowPlayingControls()

        // Initialize progress
        npSeekbar.progress = playerSeekbar?.progress ?: 0
        tvCurrentTime.text = tvPlayerTime?.text?.split("/")?.firstOrNull()?.trim() ?: "00:00"
        tvTotalTime.text = tvPlayerTime?.text?.split("/")?.getOrNull(1)?.trim() ?: currentSong.durationFormatted

        btnCollapse.setOnClickListener { dialog.dismiss() }

        // Pressing screen, artwork, or song info opens Poweramp Audio Info & Tags
        layoutAlbumArt.setOnClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
        }
        layoutAlbumArt.setOnLongClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
            true
        }
        layoutSongInfo.setOnClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
        }
        layoutSongInfo.setOnLongClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
            true
        }
        btnInfo.setOnClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
        }
        btnAudioInfo.setOnClickListener {
            audioPlayer.currentSong?.let { showAudioInfoDialog(it) }
        }

        btnQueue.setOnClickListener {
            showQueueDialog()
        }

        btnPlayPause.setOnClickListener {
            audioPlayer.togglePlayPause()
            updateNowPlayingControls()
        }

        btnPrev.setOnClickListener {
            audioPlayer.playPrevious()
        }

        btnNext.setOnClickListener {
            audioPlayer.playNext()
        }

        btnShuffle.setOnClickListener {
            val s = audioPlayer.toggleShuffle()
            updateShuffleButton(s)
            updateNowPlayingControls()
            Toast.makeText(this, if (s) "Shuffle On" else "Shuffle Off", Toast.LENGTH_SHORT).show()
        }

        btnRepeat.setOnClickListener {
            val m = audioPlayer.toggleRepeat()
            updateRepeatButton(m)
            updateNowPlayingControls()
            val msg = when (m) {
                RepeatMode.OFF -> "Repeat Off"
                RepeatMode.ALL -> "Repeat All"
                RepeatMode.ONE -> "Repeat One"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnLike.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                toggleSongLiked(song)
                val isLiked = playlistManager.isLiked(song)
                btnLike.setImageResource(if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
                btnLike.setColorFilter(if (isLiked) ContextCompat.getColor(this, R.color.status_offline) else ContextCompat.getColor(this, R.color.text_muted))
            }
        }

        btnOptions.setOnClickListener {
            audioPlayer.currentSong?.let { showSongOptionsDialog(it) }
        }

        npSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = progress.toFloat() / 1000f
                    audioPlayer.seekTo(ratio)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Live player updates in Now Playing dialog
        nowPlayingUpdateCallback = { song ->
            runOnUiThread {
                if (dialog.isShowing) {
                    updateNowPlayingSongUI(song)
                    updateNowPlayingControls()
                }
            }
        }

        nowPlayingStateCallback = { _ ->
            runOnUiThread {
                if (dialog.isShowing) {
                    updateNowPlayingControls()
                }
            }
        }

        nowPlayingProgressCallback = { currentMs, totalMs ->
            runOnUiThread {
                if (dialog.isShowing && totalMs > 0) {
                    val ratio = (currentMs.toFloat() / totalMs.toFloat()) * 1000f
                    npSeekbar.progress = ratio.toInt()

                    val curMin = currentMs / 1000 / 60
                    val curSec = (currentMs / 1000) % 60
                    val totMin = totalMs / 1000 / 60
                    val totSec = (totalMs / 1000) % 60
                    tvCurrentTime.text = String.format(Locale.US, "%02d:%02d", curMin, curSec)
                    tvTotalTime.text = String.format(Locale.US, "%02d:%02d", totMin, totSec)
                }
            }
        }

        nowPlayingModeCallback = { _, _ ->
            runOnUiThread {
                if (dialog.isShowing) {
                    updateNowPlayingControls()
                }
            }
        }

        dialog.setOnDismissListener {
            nowPlayingDialog = null
            nowPlayingUpdateCallback = null
            nowPlayingStateCallback = null
            nowPlayingProgressCallback = null
            nowPlayingModeCallback = null
        }

        nowPlayingDialog = dialog
        dialog.show()
    }

    private fun showAudioInfoDialog(song: Song) {
        val streamUrl = if (libraryMode == MODE_REMOTE) getStreamUrl(song) else null
        val details = AudioInfoHelper.extract(song, streamUrl)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_audio_info, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_info_title)
        val tvArtist = dialogView.findViewById<TextView>(R.id.tv_info_artist)
        val tvAlbumArtist = dialogView.findViewById<TextView>(R.id.tv_info_album_artist)
        val tvAlbum = dialogView.findViewById<TextView>(R.id.tv_info_album)
        val tvTrackDisc = dialogView.findViewById<TextView>(R.id.tv_info_track_disc)
        val tvYearGenre = dialogView.findViewById<TextView>(R.id.tv_info_year_genre)

        val tvFormat = dialogView.findViewById<TextView>(R.id.tv_info_format)
        val tvBitrate = dialogView.findViewById<TextView>(R.id.tv_info_bitrate)
        val tvSampleBits = dialogView.findViewById<TextView>(R.id.tv_info_sample_bits)
        val tvChannels = dialogView.findViewById<TextView>(R.id.tv_info_channels)
        val tvSize = dialogView.findViewById<TextView>(R.id.tv_info_size)

        val tvSourceBadge = dialogView.findViewById<TextView>(R.id.tv_info_source_badge)
        val tvFilePath = dialogView.findViewById<TextView>(R.id.tv_info_file_path)
        val btnCopyPath = dialogView.findViewById<TextView>(R.id.btn_copy_file_path)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_audio_info_close)
        val btnDone = dialogView.findViewById<TextView>(R.id.btn_audio_info_done)

        tvTitle.text = details.title
        tvArtist.text = details.artist
        tvAlbumArtist.text = details.albumArtist
        tvAlbum.text = details.album
        tvTrackDisc.text = "Track ${details.trackNumber} / Disc ${details.discNumber}"
        tvYearGenre.text = "${details.year} • ${details.genre}"

        tvFormat.text = details.formatLabel
        tvBitrate.text = details.bitrateKbps
        tvSampleBits.text = "${details.sampleRateHz} • ${details.bitDepth}"
        tvChannels.text = "${details.channels} • ${details.durationFormatted}"
        tvSize.text = details.sizeFormatted

        if (details.isRemote) {
            tvSourceBadge.text = "OVER-IP STREAM"
            tvSourceBadge.setBackgroundResource(R.drawable.bg_badge_yellow)
            tvSourceBadge.setTextColor(ContextCompat.getColor(this, R.color.yellow_primary))
        } else {
            tvSourceBadge.text = "LOCAL STORAGE"
            tvSourceBadge.setBackgroundResource(R.drawable.bg_badge_green)
            tvSourceBadge.setTextColor(ContextCompat.getColor(this, R.color.status_online))
        }

        tvFilePath.text = details.filePath

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCopyPath.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("File Path", details.filePath)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDone.setOnClickListener { dialog.dismiss() }

        dialog.show()
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

    private fun getTargetPeerIp(): String {
        return activePeerIp?.ifBlank { null }
            ?: etDesktopIp?.text?.toString()?.trim()?.ifBlank { null }
            ?: prefs.desktopIp
    }

    private fun getTargetPeerPort(): Int {
        return activePeerPort
            ?: etDesktopPort?.text?.toString()?.toIntOrNull()
            ?: prefs.desktopPort
    }

    private fun updatePeerBarText() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val name = activePeerName ?: "Peer"
        tvActivePeerInfo?.text = "$name: $ip:$port"
    }

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

    private fun loadRemotePlaylists() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()

        if (ip.isEmpty()) {
            tvPlaylistsEmpty?.text = "No peer device configured. Tap 'Select Peer' above to discover devices."
            tvPlaylistsEmpty?.visibility = View.VISIBLE
            playlistsAdapter.submitList(emptyList())
            badgePlaylistsCount?.text = "0 playlists"
            return
        }

        updatePeerBarText()
        swipeRefreshPlaylists?.isRefreshing = true
        tvPlaylistsEmpty?.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val lists = apiClient.fetchPlaylists(ip, port)
                remotePlaylists = lists
                runOnUiThread {
                    playlistsAdapter.submitList(lists)
                    badgePlaylistsCount?.text = "${lists.size} playlists"
                    val peerLabel = activePeerName ?: "$ip:$port"
                    tvPlaylistsEmpty?.text = "No playlists found on $peerLabel.\nTap '+ New' to create one."
                    tvPlaylistsEmpty?.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
                    swipeRefreshPlaylists?.isRefreshing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefreshPlaylists?.isRefreshing = false
                    val peerLabel = activePeerName ?: "$ip:$port"
                    tvPlaylistsEmpty?.text = "Could not reach $peerLabel.\n${e.message}\nTap 'Select Peer' to choose another device."
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
            btnDetailSyncPlaylist?.visibility = View.VISIBLE
            val ip = getTargetPeerIp()
            val port = getTargetPeerPort()
            lifecycleScope.launch {
                try {
                    val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id, playlist.name)
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

    private fun closePlaylistDetail() {
        selectedPlaylist = null
        playlistTracks = emptyList()
        btnDetailSyncPlaylist?.visibility = View.GONE
        layoutPlaylistDetail?.visibility = View.GONE
        layoutPlaylistsMain?.visibility = View.VISIBLE
    }

    private fun playPlaylist(playlist: Playlist) {
        if (playlist.isRemote) {
            val ip = getTargetPeerIp()
            val port = getTargetPeerPort()
            lifecycleScope.launch {
                try {
                    val tracks = apiClient.fetchPlaylistTracks(ip, port, playlist.id, playlist.name)
                    if (tracks.isEmpty()) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Playlist is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val items = tracks.map { s -> createPlayableItem(s, true) }
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

    fun showPeerSelectionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_peer_selection, null)
        val pbScanning = dialogView.findViewById<ProgressBar>(R.id.pb_peer_scanning)
        val btnRescan = dialogView.findViewById<TextView>(R.id.btn_peer_rescan)
        val layoutDiscovered = dialogView.findViewById<LinearLayout>(R.id.layout_discovered_peers)
        val tvNoPeers = dialogView.findViewById<TextView>(R.id.tv_no_peers_found)
        val etManualIp = dialogView.findViewById<EditText>(R.id.et_peer_manual_ip)
        val etManualPort = dialogView.findViewById<EditText>(R.id.et_peer_manual_port)

        etManualIp.setText(getTargetPeerIp())
        etManualPort.setText(getTargetPeerPort().toString())

        var dialog: AlertDialog? = null

        fun addPeerItem(peer: com.aruncs.musicsync.server.DiscoveredPeer) {
            val itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, layoutDiscovered, false)
            val text1 = itemView.findViewById<TextView>(android.R.id.text1)
            val text2 = itemView.findViewById<TextView>(android.R.id.text2)
            text1.text = peer.displayName
            text1.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            text1.setTypeface(null, android.graphics.Typeface.BOLD)
            text2.text = "${peer.ip}:${peer.port}"
            text2.setTextColor(ContextCompat.getColor(this, R.color.yellow_primary))

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
                activePeerIp = peer.ip
                activePeerPort = peer.port
                activePeerName = peer.hostname
                updatePeerBarText()
                dialog?.dismiss()
                loadRemotePlaylists()
                Toast.makeText(this, "Connected to ${peer.hostname}", Toast.LENGTH_SHORT).show()
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

            lifecycleScope.launch {
                val peers = com.aruncs.musicsync.server.PeerDiscoveryManager.discoverAllPeers(
                    context = this@MainActivity,
                    timeoutMs = 2500,
                    onPeerFound = { peer ->
                        runOnUiThread {
                            addPeerItem(peer)
                        }
                    },
                    onLog = { msg ->
                        runOnUiThread { logAdapter.addLog(msg) }
                    }
                )

                runOnUiThread {
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

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Connect Manual IP") { _, _ ->
                val ip = etManualIp.text.toString().trim()
                val port = etManualPort.text.toString().toIntOrNull() ?: 5000
                if (ip.isNotEmpty()) {
                    activePeerIp = ip
                    activePeerPort = port
                    activePeerName = "Manual Peer"
                    updatePeerBarText()
                    loadRemotePlaylists()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        scanForPeers()
    }

    private fun syncRemotePlaylistWithQuality(playlist: Playlist) {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()

        if (ip.isEmpty()) {
            Toast.makeText(this, "No peer configured. Select a peer device first.", Toast.LENGTH_SHORT).show()
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

        val peerDisplay = activePeerName ?: "$ip:$port"

        AlertDialog.Builder(this)
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
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Syncing \"${playlist.name}\"")
            .setMessage("Connecting to peer ($ip:$port)...")
            .setCancelable(false)
            .setNegativeButton("Hide") { d, _ -> d.dismiss() }
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val (downloadedCount, m3uFile) = syncManager.syncPlaylistFromPeer(
                    peerIp = ip,
                    peerPort = port,
                    playlist = playlist,
                    targetBitrate = bitrate,
                    onProgress = { current, total, song, percent, message ->
                        runOnUiThread {
                            progressDialog.setMessage("Downloading ($current/$total) [$percent%]:\n${song.title.ifBlank { song.filename }}")
                        }
                    },
                    onLog = { msg ->
                        runOnUiThread { logAdapter.addLog(msg) }
                    }
                )

                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    loadLocalLibrary()
                    switchPlaylistMode(MODE_LOCAL)
                    loadLocalPlaylists()

                    val exportMsg = if (m3uFile != null) "\nPoweramp playlist exported: ${m3uFile.name}" else ""
                    val summary = "Synced \"${playlist.name}\": $downloadedCount track(s) downloaded.$exportMsg"
                    Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
                    logAdapter.addLog("[PLAYLIST-SYNC-SUCCESS] $summary")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                    logAdapter.addLog("[PLAYLIST-SYNC-ERR] ${e.message}")
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
                        val ip = getTargetPeerIp()
                        val port = getTargetPeerPort()
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
                    "Added \"${matchedSong.title.ifBlank { matchedSong.filename }}\" → ${absentSong.playlistName}",
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

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestAllFilesAccessDialog()
        }
    }

    private fun requestAllFilesAccessDialog(onDismiss: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("Android requires 'All files access' for Music Sync to save downloaded music files, export Poweramp playlists, and organize your music library.\n\nPlease tap 'Grant Access' and toggle 'Allow access to manage all files' for Music Sync.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                startActivity(intent)
                            } catch (e2: Exception) {
                                Toast.makeText(this, "Could not open storage settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Later") { _, _ ->
                        onDismiss?.invoke()
                    }
                    .show()
            }
        }
    }

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
            checkAllFilesAccess()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                logAdapter.addLog("[PERMISSIONS] Media permissions granted.")
                loadLocalLibrary()
            } else {
                logAdapter.addLog("[WARN] Media permissions denied. Library scan may be empty.")
                Toast.makeText(this, "Storage permission is required to read & save music", Toast.LENGTH_LONG).show()
            }
            checkAllFilesAccess()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFilesAccess() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)) {
            if (localSongs.isEmpty()) {
                loadLocalLibrary()
            }
        }
    }

    override fun onDestroy() {
        try {
            com.aruncs.musicsync.server.SyncForegroundService.onLogReceived = null
            com.aruncs.musicsync.server.SyncForegroundService.onStateChanged = null
        } catch (ignored: Throwable) {}
        PeerDiscoveryManager.stopListener()
        audioPlayer.release()
        super.onDestroy()
    }
}
