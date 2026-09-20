package com.aruncs.musicsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.client.SyncManager
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.CrashLogger
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.PlaybackTarget
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.PlayableItem
import com.aruncs.musicsync.server.NetworkUtils
import com.aruncs.musicsync.server.PeerDiscoveryManager
import com.aruncs.musicsync.server.SyncForegroundService
import com.aruncs.musicsync.ui.adapter.LogAdapter
import com.aruncs.musicsync.ui.controller.LibraryTabController
import com.aruncs.musicsync.ui.controller.PlayerUiController
import com.aruncs.musicsync.ui.controller.PlaylistsTabController
import com.aruncs.musicsync.ui.controller.SessionSyncController
import com.aruncs.musicsync.ui.controller.SyncTabController
import com.aruncs.musicsync.ui.dialog.AudioInfoDialogHelper
import com.aruncs.musicsync.ui.dialog.PlaylistDialogsHelper
import com.aruncs.musicsync.ui.dialog.QueryRunnerDialogHelper
import com.aruncs.musicsync.ui.dialog.QueueDialogHelper
import com.aruncs.musicsync.ui.dialog.SessionManagerDialogHelper
import com.aruncs.musicsync.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_SYNC = 0
        const val TAB_LIBRARY = 1
        const val TAB_PLAYER = 2
        const val TAB_PLAYLISTS = 3
        const val TAB_LOGS = 4
    }

    // Core Managers & Clients
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var playlistManager: PlaylistManager
    @Inject lateinit var audioPlayer: AudioPlayer
    @Inject lateinit var apiClient: DesktopApiClient
    private val logAdapter = LogAdapter()

    // Tab Controllers
    private lateinit var playerController: PlayerUiController
    private lateinit var syncTabController: SyncTabController
    private lateinit var libraryTabController: LibraryTabController
    private lateinit var playlistsTabController: PlaylistsTabController
    private lateinit var sessionSyncController: SessionSyncController

    // Active Peer Target State
    private var activePeerIp: String? = null
    private var activePeerPort: Int? = null
    private var activePeerName: String? = null

    // Poweramp Backup Import Launcher
    private val openPowerampDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { playlistsTabController.handlePowerampImport(it) }
    }

    // ViewPager2 & Top Badge
    private lateinit var viewPager: ViewPager2
    private lateinit var tvMainIpBadge: TextView

    // Bottom Navigation Views
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

    // Cached Tab Views
    private lateinit var syncView: View
    private lateinit var libraryView: View
    private lateinit var playerView: View
    private lateinit var playlistsView: View
    private lateinit var logsView: View

    // Logs Tab Views
    private var rvLogs: RecyclerView? = null
    private var btnLogsClear: Button? = null
    private var btnLogsCrash: Button? = null
    private var btnLogsQuery: Button? = null

    private var currentTab = TAB_SYNC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        CrashLogger.init(applicationContext)
        CrashLogger.onPlayerErrorLogged = { logMsg ->
            runOnUiThread {
                logAdapter.addLog(logMsg)
                rvLogs?.scrollToPosition(logAdapter.itemCount - 1)
            }
        }

        initViews()
        initControllers()
        initTabViews()

        val lastPlayed = prefs.getLastPlayedSong()
        if (lastPlayed != null && audioPlayer.currentSong == null) {
            val (song, streamUrl) = lastPlayed
            audioPlayer.restoreState(PlayableItem(song, streamUrl))
        }

        setupViewPager()
        setupListeners()
        setupServiceCallbacks()

        PermissionHelper.checkPermissions(this) {
            libraryTabController.loadLocalLibrary()
        }

        sessionSyncController.registerCallbacks()
        syncTabController.updateServerStatusUI(SyncForegroundService.isRunning)
        refreshIpBadge()

        val prevCrash = CrashLogger.checkAndClearPreviousCrash(this)
        if (prevCrash != null) {
            logAdapter.addLog("[CRASH DETECTED] Previous crash: $prevCrash (Tap 'Crash Dump' in Logs tab for details)")
        }

        if (NetworkUtils.isWifiConnected(this)) {
            syncTabController.scanForNearbyDevices(showToast = false)
        }

        PeerDiscoveryManager.startListener(this) { logMsg ->
            runOnUiThread { logAdapter.addLog(logMsg) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playlistsTabController.isDetailOpen) {
                    playlistsTabController.closePlaylistDetail()
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
    }

    private fun initControllers() {
        syncTabController = SyncTabController(
            activity = this,
            scope = lifecycleScope,
            prefs = prefs,
            apiClient = apiClient,
            syncManager = syncManager,
            getTargetPeerIp = { getTargetPeerIp() },
            getTargetPeerPort = { getTargetPeerPort() },
            onTargetPeerSelected = { ip, port, name ->
                activePeerIp = ip
                activePeerPort = port
                activePeerName = name
                playlistsTabController.updatePeerBarText()
            },
            onBrowseLibraryRequested = { device ->
                activePeerIp = device.ip
                activePeerPort = device.port
                activePeerName = device.name
                playlistsTabController.updatePeerBarText()
                switchTab(TAB_LIBRARY)
                libraryTabController.switchLibraryMode(LibraryTabController.MODE_REMOTE)
                libraryTabController.loadRemoteLibrary()
                Toast.makeText(this, "Browsing library from ${device.name}...", Toast.LENGTH_SHORT).show()
            },
            onShowSessionManager = { showSessionManagerDialog() },
            onRefreshLocalLibrary = { libraryTabController.loadLocalLibrary() },
            onLog = { msg -> runOnUiThread { logAdapter.addLog(msg) } },
            onIpBadgeRefreshNeeded = { refreshIpBadge() }
        )

        playerController = PlayerUiController(
            activity = this,
            scope = lifecycleScope,
            audioPlayer = audioPlayer,
            playlistManager = playlistManager,
            apiClient = apiClient,
            prefs = prefs,
            getSavedDevices = { syncTabController.getKnownDevices() },
            onAddDeviceRequested = { syncTabController.showAddDeviceDialog() },
            getLocalWifiIp = { NetworkUtils.getWifiIpAddress(this) },
            getLocalServerPort = { prefs.serverPort },
            getStreamUrl = { song -> libraryTabController.getStreamUrl(song) },
            onNavigateToLibrary = { switchTab(TAB_LIBRARY) },
            onNavigateToPlayerTab = { switchTab(TAB_PLAYER) },
            onShowAudioInfo = { song -> showAudioInfoDialog(song) },
            onShowQueue = { showQueueDialog() },
            onShowSongOptions = { song -> libraryTabController.showSongOptionsDialog(song) },
            onAddToPlaylist = { song -> showAddToPlaylistDialog(song) },
            onDownloadSong = { song -> libraryTabController.downloadRemoteSong(song) },
            onToggleLike = { song ->
                libraryTabController.toggleSongLiked(song)
                playlistsTabController.loadLocalPlaylists()
            },
            onLog = { msg -> runOnUiThread { logAdapter.addLog(msg) } },
            onActiveSongChanged = { song, isPlaying ->
                libraryTabController.songsAdapter.setActiveSong(song, isPlaying)
                playlistsTabController.playlistDetailAdapter.setActiveSong(song, isPlaying)
            },
            onTargetChanged = { target ->
                if (target is PlaybackTarget.Remote) {
                    activePeerIp = target.ip
                    activePeerPort = target.port
                    activePeerName = target.device.name
                    playlistsTabController.updatePeerBarText()
                }
            }
        )

        libraryTabController = LibraryTabController(
            activity = this,
            scope = lifecycleScope,
            prefs = prefs,
            apiClient = apiClient,
            playlistManager = playlistManager,
            audioPlayer = audioPlayer,
            getTargetPeerIp = { getTargetPeerIp() },
            getTargetPeerPort = { getTargetPeerPort() },
            onShowAudioInfo = { song -> showAudioInfoDialog(song) },
            onAddToPlaylist = { song -> showAddToPlaylistDialog(song) },
            onLocalSongsUpdated = { songs -> playerController.setLocalSongs(songs) },
            onLog = { msg -> runOnUiThread { logAdapter.addLog(msg) } }
        )

        libraryTabController.isRemotePlaybackTargetActive = {
            playerController.currentTarget is PlaybackTarget.Remote
        }
        libraryTabController.onPlayOnRemoteRequested = { song ->
            playerController.startPlaybackOnRemote(song)
        }

        playlistsTabController = PlaylistsTabController(
            activity = this,
            scope = lifecycleScope,
            playlistManager = playlistManager,
            apiClient = apiClient,
            syncManager = syncManager,
            audioPlayer = audioPlayer,
            getTargetPeerIp = { getTargetPeerIp() },
            getTargetPeerPort = { getTargetPeerPort() },
            getActivePeerName = { activePeerName },
            setActivePeer = { ip, port, name ->
                activePeerIp = ip
                activePeerPort = port
                activePeerName = name
            },
            createPlayableItem = { song, isRemote -> libraryTabController.createPlayableItem(song, isRemote) },
            getLocalSongs = { libraryTabController.localSongs },
            onRefreshLocalLibrary = { libraryTabController.loadLocalLibrary() },
            onShowAudioInfo = { song -> showAudioInfoDialog(song) },
            onShowSongOptions = { song -> libraryTabController.showSongOptionsDialog(song) },
            onLog = { msg -> runOnUiThread { logAdapter.addLog(msg) } },
            onLaunchPowerampPicker = { openPowerampDocumentLauncher.launch(arrayOf("*/*")) }
        )

        sessionSyncController = SessionSyncController(
            context = this,
            audioPlayer = audioPlayer,
            onPlayerControlsUpdate = { playerController.updatePlayerControlsState() },
            onLog = { msg -> runOnUiThread { logAdapter.addLog(msg) } }
        )
    }

    private fun initTabViews() {
        val inflater = LayoutInflater.from(this)

        // 0. Sync Tab
        syncView = inflater.inflate(R.layout.fragment_sync, viewPager, false)
        syncTabController.init(syncView)

        // 1. Library Tab
        libraryView = inflater.inflate(R.layout.fragment_library, viewPager, false)
        libraryTabController.init(libraryView)

        // 2. Player Tab
        playerView = inflater.inflate(R.layout.fragment_player, viewPager, false)
        playerController.initPlayerScreen(playerView)
        playerController.initPlayerBar()

        // 3. Playlists Tab
        playlistsView = inflater.inflate(R.layout.fragment_playlists, viewPager, false)
        playlistsTabController.init(playlistsView)

        // 4. Logs Tab
        logsView = inflater.inflate(R.layout.fragment_logs, viewPager, false)
        rvLogs = logsView.findViewById(R.id.rv_logs)
        btnLogsClear = logsView.findViewById(R.id.btn_logs_clear)
        btnLogsCrash = logsView.findViewById(R.id.btn_logs_crash)
        btnLogsQuery = logsView.findViewById(R.id.btn_logs_query)

        rvLogs?.layoutManager = LinearLayoutManager(this)
        rvLogs?.adapter = logAdapter

        btnLogsClear?.setOnClickListener { logAdapter.clear() }
        btnLogsQuery?.setOnClickListener {
            QueryRunnerDialogHelper.show(this)
        }
        btnLogsCrash?.setOnClickListener {
            val logs = CrashLogger.getCrashLogs(this)
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
                    CrashLogger.clearLogs(this)
                    Toast.makeText(this, "Crash logs cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
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

    private fun setupListeners() {
        navTabSync.setOnClickListener { switchTab(TAB_SYNC) }
        ivTabSync.setOnClickListener { switchTab(TAB_SYNC) }
        tvTabSync.setOnClickListener { switchTab(TAB_SYNC) }

        navTabLibrary.setOnClickListener { switchTab(TAB_LIBRARY) }
        ivTabLibrary.setOnClickListener { switchTab(TAB_LIBRARY) }
        tvTabLibrary.setOnClickListener { switchTab(TAB_LIBRARY) }

        navTabPlayer.setOnClickListener { switchTab(TAB_PLAYER) }
        ivTabPlayer.setOnClickListener { switchTab(TAB_PLAYER) }
        tvTabPlayer.setOnClickListener { switchTab(TAB_PLAYER) }

        navTabPlaylists.setOnClickListener { switchTab(TAB_PLAYLISTS) }
        ivTabPlaylists.setOnClickListener { switchTab(TAB_PLAYLISTS) }
        tvTabPlaylists.setOnClickListener { switchTab(TAB_PLAYLISTS) }

        navTabLogs.setOnClickListener { switchTab(TAB_LOGS) }
        ivTabLogs.setOnClickListener { switchTab(TAB_LOGS) }
        tvTabLogs.setOnClickListener { switchTab(TAB_LOGS) }
    }

    private fun setupServiceCallbacks() {
        SyncForegroundService.onStateChanged = { running, _ ->
            runOnUiThread {
                syncTabController.updateServerStatusUI(running)
                refreshIpBadge()
            }
        }

        SyncForegroundService.onLogReceived = { logMsg ->
            try {
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        logAdapter.addLog(logMsg)
                        val count = logAdapter.itemCount
                        if (count > 0) {
                            rvLogs?.post {
                                try {
                                    rvLogs?.scrollToPosition(count - 1)
                                } catch (ignored: Throwable) {}
                            }
                        }
                    }
                }
            } catch (ignored: Throwable) {}
        }
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
        playerController.onTabChanged(tab)
        when (tab) {
            TAB_SYNC -> { /* No-op */ }
            TAB_LIBRARY -> {
                if (libraryTabController.libraryMode == LibraryTabController.MODE_LOCAL && libraryTabController.localSongs.isEmpty()) {
                    libraryTabController.loadLocalLibrary()
                } else if (libraryTabController.libraryMode == LibraryTabController.MODE_REMOTE && libraryTabController.remoteSongs.isEmpty()) {
                    libraryTabController.loadRemoteLibrary()
                }
            }
            TAB_PLAYER -> {
                playerController.updatePlayerScreenUI(audioPlayer.currentSong)
            }
            TAB_PLAYLISTS -> {
                playlistsTabController.loadPlaylists()
            }
            TAB_LOGS -> { /* No-op */ }
        }
    }

    fun switchTab(tab: Int, smoothScroll: Boolean = true) {
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

    fun getTargetPeerIp(): String {
        return activePeerIp?.ifBlank { null } ?: prefs.desktopIp
    }

    fun getTargetPeerPort(): Int {
        return activePeerPort ?: prefs.desktopPort
    }

    private fun showAudioInfoDialog(song: Song) {
        val isRemote = audioPlayer.currentItem?.isRemote ?: (libraryTabController.libraryMode == LibraryTabController.MODE_REMOTE)
        val streamUrl = if (isRemote) libraryTabController.getStreamUrl(song) else null
        AudioInfoDialogHelper.show(this, song, isRemote, streamUrl)
    }

    private fun showQueueDialog() {
        QueueDialogHelper.show(
            context = this,
            audioPlayer = audioPlayer,
            scope = lifecycleScope,
            apiClient = apiClient,
            getTargetPeerIp = { getTargetPeerIp() },
            getTargetPeerPort = { getTargetPeerPort() },
            getTargetPeerName = { activePeerName },
            onQueueCleared = { playerController.onTabChanged(currentTab) }
        )
    }

    private fun showAddToPlaylistDialog(song: Song) {
        PlaylistDialogsHelper.showAddToPlaylistDialog(
            activity = this,
            scope = lifecycleScope,
            song = song,
            isRemote = libraryTabController.libraryMode == LibraryTabController.MODE_REMOTE,
            targetPeerIp = getTargetPeerIp(),
            targetPeerPort = getTargetPeerPort(),
            apiClient = apiClient,
            playlistManager = playlistManager,
            onPlaylistCreated = {
                if (currentTab == TAB_PLAYLISTS) playlistsTabController.loadPlaylists()
            }
        )
    }

    private fun showSessionManagerDialog() {
        SessionManagerDialogHelper.show(
            activity = this,
            scope = lifecycleScope,
            apiClient = apiClient,
            audioPlayer = audioPlayer,
            targetIp = getTargetPeerIp(),
            targetPort = getTargetPeerPort(),
            onLog = { msg -> runOnUiThread { logAdapter.addLog(msg) } }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.handlePermissionResult(
            requestCode = requestCode,
            grantResults = grantResults,
            activity = this,
            onGranted = {
                logAdapter.addLog("[PERMISSIONS] Media permissions granted.")
                libraryTabController.loadLocalLibrary()
            },
            onDenied = {
                logAdapter.addLog("[WARN] Media permissions denied. Library scan may be empty.")
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (PermissionHelper.hasAllFilesAccess(this) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED)
        ) {
            if (libraryTabController.localSongs.isEmpty()) {
                libraryTabController.loadLocalLibrary()
            }
        }
    }

    override fun onDestroy() {
        try {
            SyncForegroundService.onLogReceived = null
            SyncForegroundService.onStateChanged = null
        } catch (ignored: Throwable) {}
        PeerDiscoveryManager.stopListener()
        if (isFinishing) {
            audioPlayer.release()
        }
        super.onDestroy()
    }
}
