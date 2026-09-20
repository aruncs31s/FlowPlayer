package com.aruncs.musicsync.ui.controller

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.PlaybackTarget
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.model.SyncDevice
import com.aruncs.musicsync.player.AudioInfoHelper
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.RepeatMode
import com.aruncs.musicsync.server.SyncForegroundService
import com.aruncs.musicsync.session.SessionState
import com.aruncs.musicsync.ui.adapter.QueueAdapter
import com.aruncs.musicsync.ui.dialog.DevicePickerHelper
import com.aruncs.musicsync.ui.dialog.NowPlayingDialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale

class PlayerUiController(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val audioPlayer: AudioPlayer,
    private val playlistManager: PlaylistManager,
    private val apiClient: DesktopApiClient,
    private val prefs: AppPreferences,
    private val getSavedDevices: () -> List<SyncDevice>,
    private val onAddDeviceRequested: () -> Unit,
    private val getLocalWifiIp: () -> String?,
    private val getLocalServerPort: () -> Int,
    private val getStreamUrl: (Song) -> String?,
    private val onNavigateToLibrary: () -> Unit,
    private val onNavigateToPlayerTab: () -> Unit,
    private val onShowAudioInfo: (Song) -> Unit,
    private val onShowQueue: () -> Unit,
    private val onShowSongOptions: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onDownloadSong: (Song) -> Unit,
    private val onToggleLike: (Song) -> Unit,
    private val onLog: (String) -> Unit,
    private val onActiveSongChanged: (song: Song?, isPlaying: Boolean) -> Unit,
    private val onTargetChanged: (PlaybackTarget) -> Unit
) {
    // Playback Target State
    var currentTarget: PlaybackTarget = PlaybackTarget.Local
        private set
    private var remotePollingJob: Job? = null
    private var latestRemoteSessionState: SessionState? = null
    private var remoteFailureCount = 0

    // Bottom Player Bar views
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



    // Player Screen views (fragment_player)
    private var layoutFpEmpty: View? = null
    private var ivFpEmptyIcon: ImageView? = null
    private var tvFpEmptyTitle: TextView? = null
    private var tvFpEmptySubtitle: TextView? = null
    private var btnFpEmptyResumeRemote: TextView? = null
    private var btnFpEmptyTransferHere: TextView? = null
    private var btnFpEmptyBrowse: View? = null
    private var btnFpEmptySwitchLocal: TextView? = null

    private var layoutFpActive: View? = null
    private var tvFpQueuePos: TextView? = null
    private var btnFpDeviceSwitch: ImageButton? = null
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
    private var btnFpTransferLocal: TextView? = null
    private var btnFpAudioInfo: TextView? = null
    private var btnFpOptions: ImageButton? = null
    private var tvFpQueueCount: TextView? = null
    private var rvFpQueue: RecyclerView? = null
    private lateinit var fpQueueAdapter: QueueAdapter

    private var localSongs: List<Song> = emptyList()
    private var localSongMap: Map<String, Song> = emptyMap()
    private var currentTab: Int = 0

    val nowPlayingHelper: NowPlayingDialogHelper by lazy {
        NowPlayingDialogHelper(
            activity = activity,
            audioPlayer = audioPlayer,
            playlistManager = playlistManager,
            getStreamUrl = getStreamUrl,
            onShowAudioInfo = onShowAudioInfo,
            onShowQueue = onShowQueue,
            onShowSongOptions = onShowSongOptions,
            onToggleLike = { song ->
                onToggleLike(song)
                updatePlayerLikeButton(song)
                updatePlayerScreenLike(song)
            },
            onModeChangedUI = { isShuffled, repMode ->
                updateShuffleButton(isShuffled)
                updateRepeatButton(repMode)
                updatePlayerScreenControls()
            }
        )
    }

    fun initPlayerBar() {
        playerBarContainer = activity.findViewById(R.id.player_bar_container)
        btnPlayerShuffle = activity.findViewById(R.id.btn_player_shuffle)
        btnPlayerPrev = activity.findViewById(R.id.btn_player_prev)
        btnPlayerPlayPause = activity.findViewById(R.id.btn_player_play_pause)
        btnPlayerNext = activity.findViewById(R.id.btn_player_next)
        btnPlayerRepeat = activity.findViewById(R.id.btn_player_repeat)
        btnPlayerLike = activity.findViewById(R.id.btn_player_like)
        btnPlayerQueue = activity.findViewById(R.id.btn_player_queue)
        tvPlayerTitle = activity.findViewById(R.id.tv_player_title)
        tvPlayerArtist = activity.findViewById(R.id.tv_player_artist)
        tvPlayerTime = activity.findViewById(R.id.tv_player_time)
        playerSeekbar = activity.findViewById(R.id.player_seekbar)
        btnPlayerClose = activity.findViewById(R.id.btn_player_close)

        btnPlayerPlayPause?.setOnClickListener {
            if (currentTarget is PlaybackTarget.Remote) {
                handleRemotePlayPause()
            } else {
                audioPlayer.togglePlayPause()
            }
        }

        btnPlayerShuffle?.setOnClickListener {
            val shuffled = audioPlayer.toggleShuffle()
            updateShuffleButton(shuffled)
            updatePlayerScreenControls()
            val msg = if (shuffled) "Shuffle On" else "Shuffle Off"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }

        btnPlayerPrev?.setOnClickListener {
            if (currentTarget is PlaybackTarget.Remote) {
                val rem = currentTarget as PlaybackTarget.Remote
                scope.launch { apiClient.sessionPrev(rem.ip, rem.port) }
            } else {
                audioPlayer.playPrevious()
            }
        }

        btnPlayerNext?.setOnClickListener {
            if (currentTarget is PlaybackTarget.Remote) {
                val rem = currentTarget as PlaybackTarget.Remote
                scope.launch { apiClient.sessionNext(rem.ip, rem.port) }
            } else {
                audioPlayer.playNext()
            }
        }

        btnPlayerRepeat?.setOnClickListener {
            val mode = audioPlayer.toggleRepeat()
            updateRepeatButton(mode)
            updatePlayerScreenControls()
            val msg = when (mode) {
                RepeatMode.OFF -> "Repeat Off"
                RepeatMode.ALL -> "Repeat All"
                RepeatMode.ONE -> "Repeat One"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }

        btnPlayerLike?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                onToggleLike(song)
                updatePlayerLikeButton(song)
                updatePlayerScreenLike(song)
            }
        }

        btnPlayerQueue?.setOnClickListener {
            onShowQueue()
        }

        btnPlayerClose?.setOnClickListener {
            playerBarContainer.visibility = View.GONE
        }

        val openPlayerTab = View.OnClickListener {
            onNavigateToPlayerTab()
        }
        tvPlayerTitle?.setOnClickListener(openPlayerTab)
        tvPlayerArtist?.setOnClickListener(openPlayerTab)
        tvPlayerTime?.setOnClickListener(openPlayerTab)
        playerBarContainer.setOnClickListener(openPlayerTab)

        tvPlayerTitle?.setOnLongClickListener {
            audioPlayer.currentSong?.let { onShowAudioInfo(it) }
            true
        }
        tvPlayerArtist?.setOnLongClickListener {
            audioPlayer.currentSong?.let { onShowAudioInfo(it) }
            true
        }
        playerBarContainer.setOnLongClickListener {
            audioPlayer.currentSong?.let { onShowAudioInfo(it) }
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
                pendingPlayerSeekRatio?.let { ratio ->
                    if (currentTarget is PlaybackTarget.Remote) {
                        val rem = currentTarget as PlaybackTarget.Remote
                        val dur = latestRemoteSessionState?.durationMs ?: 0
                        if (dur > 0) {
                            scope.launch { apiClient.sessionSeek(rem.ip, rem.port, (dur * ratio).toInt()) }
                        }
                    } else {
                        audioPlayer.seekTo(ratio)
                    }
                }
                pendingPlayerSeekRatio = null
            }
        })

        setupAudioPlayerCallbacks()

        audioPlayer.currentSong?.let { song ->
            tvPlayerTitle?.text = song.title.ifBlank { song.filename }
            tvPlayerArtist?.text = song.artist.ifBlank { "Unknown Artist" }
            val isPlaying = audioPlayer.isPlaying
            btnPlayerPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            updatePlayerControlsState()
            updatePlayerLikeButton(song)
            updateShuffleButton(audioPlayer.isShuffled)
            updateRepeatButton(audioPlayer.repeatMode)

            val totalMs = audioPlayer.duration
            val currentMs = audioPlayer.currentPosition
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
        if (::playerBarContainer.isInitialized) {
            playerBarContainer.visibility = if (audioPlayer.currentSong != null && currentTab != 2) View.VISIBLE else View.GONE
        }
    }

    fun initPlayerScreen(playerView: View) {
        // Sticky Target Device Bar

        // Empty / Idle State
        layoutFpEmpty = playerView.findViewById(R.id.layout_fp_empty)
        ivFpEmptyIcon = playerView.findViewById(R.id.iv_fp_empty_icon)
        tvFpEmptyTitle = playerView.findViewById(R.id.tv_fp_empty_title)
        tvFpEmptySubtitle = playerView.findViewById(R.id.tv_fp_empty_subtitle)
        btnFpEmptyResumeRemote = playerView.findViewById(R.id.btn_fp_empty_resume_remote)
        btnFpEmptyTransferHere = playerView.findViewById(R.id.btn_fp_empty_transfer_here)
        btnFpEmptyBrowse = playerView.findViewById(R.id.btn_fp_empty_browse)
        btnFpEmptySwitchLocal = playerView.findViewById(R.id.btn_fp_empty_switch_local)

        // Active State
        layoutFpActive = playerView.findViewById(R.id.layout_fp_active)
        tvFpQueuePos = playerView.findViewById(R.id.tv_fp_queue_pos)
        btnFpDeviceSwitch = playerView.findViewById(R.id.btn_fp_device_switch)
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
        btnFpTransferLocal = playerView.findViewById(R.id.btn_fp_transfer_local)
        btnFpAudioInfo = playerView.findViewById(R.id.btn_fp_audio_info)
        btnFpOptions = playerView.findViewById(R.id.btn_fp_options)
        tvFpQueueCount = playerView.findViewById(R.id.tv_fp_queue_count)
        rvFpQueue = playerView.findViewById(R.id.rv_fp_queue)

        tvFpTitle?.isSelected = true

        fpQueueAdapter = QueueAdapter(
            onItemClick = { index ->
                if (currentTarget is PlaybackTarget.Local) {
                    audioPlayer.playTrackAtIndex(index)
                }
            }
        )
        rvFpQueue?.layoutManager = LinearLayoutManager(activity)
        rvFpQueue?.adapter = fpQueueAdapter

        // Device Target Switch -> Open Device Picker
        btnFpDeviceSwitch?.setOnClickListener { showDevicePicker() }

        btnFpEmptyBrowse?.setOnClickListener { onNavigateToLibrary() }
        btnFpEmptyResumeRemote?.setOnClickListener { resumeRemotePlayback() }
        btnFpEmptyTransferHere?.setOnClickListener { transferCurrentSongToRemote() }
        btnFpEmptySwitchLocal?.setOnClickListener { setPlaybackTarget(PlaybackTarget.Local) }
        btnFpTransferLocal?.setOnClickListener { transferRemoteToLocal() }

        btnFpPlayPause?.setOnClickListener {
            if (currentTarget is PlaybackTarget.Remote) {
                handleRemotePlayPause()
            } else {
                audioPlayer.togglePlayPause()
            }
        }
        btnFpPrev?.setOnClickListener {
            if (currentTarget is PlaybackTarget.Remote) {
                val rem = currentTarget as PlaybackTarget.Remote
                scope.launch { apiClient.sessionPrev(rem.ip, rem.port) }
            } else {
                audioPlayer.playPrevious()
            }
        }
        btnFpNext?.setOnClickListener {
            if (currentTarget is PlaybackTarget.Remote) {
                val rem = currentTarget as PlaybackTarget.Remote
                scope.launch { apiClient.sessionNext(rem.ip, rem.port) }
            } else {
                audioPlayer.playNext()
            }
        }
        btnFpShuffle?.setOnClickListener {
            val s = audioPlayer.toggleShuffle()
            updateShuffleButton(s)
            updatePlayerScreenControls()
            Toast.makeText(activity, if (s) "Shuffle On" else "Shuffle Off", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
        btnFpLike?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                onToggleLike(song)
                updatePlayerLikeButton(song)
                updatePlayerScreenLike(song)
            }
        }
        btnFpAddPlaylist?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                onAddToPlaylist(song)
            }
        }
        btnFpDownload?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                onDownloadSong(song)
            }
        }
        btnFpOptions?.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                onShowSongOptions(song)
            }
        }
        btnFpAudioInfo?.setOnClickListener {
            audioPlayer.currentSong?.let { song -> onShowAudioInfo(song) }
        }
        layoutFpAlbumArt?.setOnClickListener {
            audioPlayer.currentSong?.let { song -> onShowAudioInfo(song) }
        }
        layoutFpSongInfo?.setOnClickListener {
            audioPlayer.currentSong?.let { song -> onShowAudioInfo(song) }
        }
        btnFpInfo?.setOnClickListener {
            audioPlayer.currentSong?.let { song -> onShowAudioInfo(song) }
        }
        btnFpQueueToggle?.setOnClickListener {
            onShowQueue()
        }

        var pendingFpSeekRatio: Float? = null
        fpSeekbar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = progress.toFloat() / 1000f
                    pendingFpSeekRatio = ratio
                    val dur = if (currentTarget is PlaybackTarget.Remote) {
                        latestRemoteSessionState?.durationMs ?: 0
                    } else {
                        audioPlayer.duration
                    }
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
                pendingFpSeekRatio?.let { ratio ->
                    if (currentTarget is PlaybackTarget.Remote) {
                        val rem = currentTarget as PlaybackTarget.Remote
                        val dur = latestRemoteSessionState?.durationMs ?: 0
                        if (dur > 0) {
                            scope.launch { apiClient.sessionSeek(rem.ip, rem.port, (dur * ratio).toInt()) }
                        }
                    } else {
                        audioPlayer.seekTo(ratio)
                    }
                }
                pendingFpSeekRatio = null
            }
        })

        updateTargetDeviceBar()
        if (currentTarget is PlaybackTarget.Remote) {
            renderRemoteState(currentTarget as PlaybackTarget.Remote, latestRemoteSessionState)
        } else {
            updatePlayerScreenUI(audioPlayer.currentSong)
        }
    }

    private fun setupAudioPlayerCallbacks() {
        audioPlayer.onTrackChanged = { song ->
            prefs.saveLastPlayedSong(song, audioPlayer.currentItem?.streamUrl)
            activity.runOnUiThread {
                if (currentTarget is PlaybackTarget.Local) {
                    if (currentTab != 2) { // TAB_PLAYER = 2
                        playerBarContainer.visibility = View.VISIBLE
                    }
                    tvPlayerTitle?.text = song.title.ifBlank { song.filename }
                    tvPlayerArtist?.text = song.artist.ifBlank { "Unknown Artist" }
                    val isPlaying = audioPlayer.isPlaying
                    btnPlayerPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    onActiveSongChanged(song, isPlaying)
                    updatePlayerControlsState()
                    updatePlayerLikeButton(song)
                    updatePlayerScreenUI(song)
                    nowPlayingHelper.onSongUpdated?.invoke(song)
                }
            }
        }

        audioPlayer.onStateChanged = { isPlaying ->
            activity.runOnUiThread {
                if (currentTarget is PlaybackTarget.Local) {
                    btnPlayerPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    onActiveSongChanged(audioPlayer.currentSong, isPlaying)
                    updatePlayerControlsState()
                    updatePlayerLikeButton(audioPlayer.currentSong)
                    updatePlayerScreenControls()
                    nowPlayingHelper.onStateUpdated?.invoke(isPlaying)
                }
            }
        }

        audioPlayer.onQueueChanged = { _, _ ->
            activity.runOnUiThread {
                if (currentTarget is PlaybackTarget.Local) {
                    updatePlayerControlsState()
                    updatePlayerScreenQueue()
                    audioPlayer.currentSong?.let { nowPlayingHelper.onSongUpdated?.invoke(it) }
                }
            }
        }

        audioPlayer.onModeChanged = { isShuffled, repeatMode ->
            activity.runOnUiThread {
                updateShuffleButton(isShuffled)
                updateRepeatButton(repeatMode)
                updatePlayerScreenControls()
                nowPlayingHelper.onModeUpdated?.invoke(isShuffled, repeatMode)
            }
        }

        audioPlayer.onProgress = { currentMs, totalMs ->
            activity.runOnUiThread {
                if (currentTarget is PlaybackTarget.Local && totalMs > 0) {
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
                    nowPlayingHelper.onProgressUpdated?.invoke(currentMs, totalMs)
                }
            }
        }

        audioPlayer.onError = { err ->
            activity.runOnUiThread {
                Toast.makeText(activity, err, Toast.LENGTH_SHORT).show()
                onLog("[PLAYER-ERROR] $err")
            }
        }
    }

    fun onTabChanged(tab: Int) {
        currentTab = tab
        if (tab == 2) { // TAB_PLAYER
            playerBarContainer.visibility = View.GONE
            if (currentTarget is PlaybackTarget.Remote) {
                startRemotePolling(currentTarget as PlaybackTarget.Remote)
            } else {
                updatePlayerScreenUI(audioPlayer.currentSong)
            }
        } else {
            playerBarContainer.visibility = if (audioPlayer.currentSong != null) View.VISIBLE else View.GONE
        }
    }

    fun setPlaybackTarget(target: PlaybackTarget) {
        val previousTarget = currentTarget
        currentTarget = target
        onTargetChanged(target)
        updateTargetDeviceBar()

        if (target is PlaybackTarget.Remote) {
            tvFpTitle?.text = "Connecting to ${target.device.name}..."
            tvFpArtist?.text = target.device.name
            startRemotePolling(target)
            scope.launch {
                try {
                    val state = apiClient.fetchSessionState(target.ip, target.port)
                    withContext(Dispatchers.Main) {
                        if (currentTarget == target) {
                            latestRemoteSessionState = state
                            renderRemoteState(target, state)
                        }
                    }
                } catch (_: Exception) {}
            }
            onLog("[SESSION] Controlling ${target.device.name} (${target.ip}:${target.port})")
            Toast.makeText(activity, "Controlling ${target.device.name}", Toast.LENGTH_SHORT).show()

            // Seamless music flow: transfer currently playing track to newly selected remote device
            val songToTransfer = audioPlayer.currentSong
            if (songToTransfer != null && (audioPlayer.isPlaying || previousTarget is PlaybackTarget.Local)) {
                startPlaybackOnRemote(songToTransfer, audioPlayer.currentPosition, target)
            }
        } else {
            stopRemotePolling()
            updatePlayerScreenUI(audioPlayer.currentSong)
            onLog("[SESSION] Switched active playback target to This Device")
            Toast.makeText(activity, "Controlling This Device", Toast.LENGTH_SHORT).show()

            // Seamless music flow: if remote was playing, transfer back to local
            val remoteState = latestRemoteSessionState
            if (remoteState != null && remoteState.isPlaying) {
                transferRemoteToLocal()
            }
        }
    }

    fun showDevicePicker() {
        DevicePickerHelper.show(
            activity = activity,
            scope = scope,
            apiClient = apiClient,
            currentTarget = currentTarget,
            devicesProvider = getSavedDevices,
            onTargetSelected = { target -> setPlaybackTarget(target) },
            onAddDeviceRequested = onAddDeviceRequested
        )
    }

    private fun updateTargetDeviceBar() {
        val target = currentTarget
        if (target is PlaybackTarget.Remote) {
            btnFpDeviceSwitch?.setImageResource(if (target.device.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer)
            btnFpTransferLocal?.visibility = View.VISIBLE
        } else {
            btnFpDeviceSwitch?.setImageResource(R.drawable.ic_computer)
            btnFpTransferLocal?.visibility = View.GONE
        }
    }

    private fun startRemotePolling(remote: PlaybackTarget.Remote) {
        stopRemotePolling()
        remoteFailureCount = 0
        remotePollingJob = scope.launch {
            while (isActive) {
                try {
                    val state = apiClient.fetchSessionState(remote.ip, remote.port)
                    withContext(Dispatchers.Main) {
                        remoteFailureCount = 0
                        latestRemoteSessionState = state
                        if (currentTarget == remote) {
                            renderRemoteState(remote, state)
                        }
                    }
                } catch (_: Exception) {
                    remoteFailureCount++
                    if (remoteFailureCount >= 3) {
                        withContext(Dispatchers.Main) {
                            if (currentTarget == remote) {
                                renderRemoteState(remote, null)
                            }
                        }
                    }
                }
                delay(1200)
            }
        }
    }

    private fun stopRemotePolling() {
        remotePollingJob?.cancel()
        remotePollingJob = null
        latestRemoteSessionState = null
    }

    private fun renderRemoteState(remote: PlaybackTarget.Remote, state: SessionState?) {
        if (state == null) {
            layoutFpEmpty?.visibility = View.VISIBLE
            layoutFpActive?.visibility = View.GONE
            ivFpEmptyIcon?.setImageResource(if (remote.device.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer)
            tvFpEmptyTitle?.text = "${remote.device.name} Unreachable"
            tvFpEmptySubtitle?.text = "Could not connect to ${remote.ip}:${remote.port}.\nCheck Wi-Fi and verify the server is running."
            btnFpEmptyResumeRemote?.visibility = View.GONE
            btnFpEmptyTransferHere?.visibility = View.GONE
            btnFpEmptyBrowse?.visibility = View.VISIBLE
            btnFpEmptySwitchLocal?.visibility = View.VISIBLE
            return
        }

        val hasTrack = state.isPlaying || state.currentTitle.isNotBlank()
        if (!hasTrack) {
            layoutFpEmpty?.visibility = View.VISIBLE
            layoutFpActive?.visibility = View.GONE
            ivFpEmptyIcon?.setImageResource(if (remote.device.isAndroid) R.drawable.ic_phone_android else R.drawable.ic_computer)
            tvFpEmptyTitle?.text = "${remote.device.name} is Idle"
            tvFpEmptySubtitle?.text = "No track currently playing on ${remote.device.name}."
            btnFpEmptyResumeRemote?.visibility = View.VISIBLE
            btnFpEmptyResumeRemote?.text = ":> Start / Resume on ${remote.device.name}"

            val localSong = audioPlayer.currentSong
            if (localSong != null) {
                btnFpEmptyTransferHere?.visibility = View.VISIBLE
                btnFpEmptyTransferHere?.text = "Cast '${localSong.title}' to ${remote.device.name}"
            } else {
                btnFpEmptyTransferHere?.visibility = View.GONE
            }

            btnFpEmptyBrowse?.visibility = View.VISIBLE
            btnFpEmptySwitchLocal?.visibility = View.VISIBLE
            return
        }

        layoutFpEmpty?.visibility = View.GONE
        layoutFpActive?.visibility = View.VISIBLE

        tvFpTitle?.text = state.currentTitle.ifBlank { "Unknown Title" }
        tvFpArtist?.text = state.currentArtist.ifBlank { remote.device.name }
        tvFpAlbum?.text = "Playing Over-IP on ${remote.device.name}"

        tvFpBadgeFormat?.text = "REMOTE"
        tvFpBadgeBitrate?.text = "OVER-IP"
        tvFpBadgeSamplerate?.text = "SESSION"
        tvFpBadgeSource?.text = remote.device.name.uppercase(Locale.US)
        tvFpBadgeSource?.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))
        tvFpBadgeSource?.setBackgroundResource(R.drawable.bg_badge_yellow)

        btnFpDownload?.visibility = View.GONE
        btnFpTransferLocal?.visibility = View.VISIBLE

        if (state.durationMs > 0) {
            val ratio = (state.positionMs.toFloat() / state.durationMs.toFloat()) * 1000f
            fpSeekbar?.progress = ratio.toInt()
            val curMin = state.positionMs / 1000 / 60
            val curSec = (state.positionMs / 1000) % 60
            val totMin = state.durationMs / 1000 / 60
            val totSec = (state.durationMs / 1000) % 60
            tvFpCurrentTime?.text = String.format(Locale.US, "%02d:%02d", curMin, curSec)
            tvFpTotalTime?.text = String.format(Locale.US, "%02d:%02d", totMin, totSec)
        }

        btnFpPlayPause?.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        tvFpQueuePos?.text = "Playing on ${remote.device.name}"
        tvFpQueueCount?.text = if (state.queueSize > 0) "${state.queueSize} in queue" else "0 in queue"
        fpQueueAdapter.submitQueue(emptyList(), -1)
    }

    fun startPlaybackOnRemote(
        song: Song,
        positionMs: Int = 0,
        targetOverride: PlaybackTarget.Remote? = null
    ) {
        val target = targetOverride ?: (currentTarget as? PlaybackTarget.Remote) ?: run {
            val firstDev = getSavedDevices().firstOrNull()
            if (firstDev != null) {
                PlaybackTarget.Remote(firstDev).also { setPlaybackTarget(it) }
            } else {
                Toast.makeText(activity, "No remote device found. Please add a device.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        scope.launch {
            try {
                val isLocalFile = File(song.filepath).exists()
                val streamUrl = if (isLocalFile) {
                    if (!SyncForegroundService.isRunning) {
                        SyncForegroundService.start(activity)
                    }
                    val wifiIp = getLocalWifiIp() ?: "0.0.0.0"
                    val port = getLocalServerPort()
                    val encPath = URLEncoder.encode(song.filepath, "UTF-8")
                    val encName = URLEncoder.encode(song.filename, "UTF-8")
                    "http://$wifiIp:$port/api/song/stream?filepath=$encPath&filename=$encName"
                } else {
                    null
                }

                val ok = apiClient.sessionTransfer(
                    ip = target.ip,
                    port = target.port,
                    filepath = song.filepath,
                    title = song.title.ifBlank { song.filename },
                    artist = song.artist,
                    album = song.album,
                    positionMs = positionMs,
                    streamUrl = streamUrl
                )

                withContext(Dispatchers.Main) {
                    if (ok) {
                        if (audioPlayer.isPlaying) audioPlayer.togglePlayPause()
                        setPlaybackTarget(target)
                        onLog("[SESSION] Started '${song.title}' on ${target.device.name}")
                        Toast.makeText(activity, "Playing '${song.title}' on ${target.device.name}", Toast.LENGTH_SHORT).show()
                        onNavigateToPlayerTab()
                    } else {
                        Toast.makeText(activity, "Failed to start playback on ${target.device.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleRemotePlayPause() {
        val rem = (currentTarget as? PlaybackTarget.Remote) ?: return
        val state = latestRemoteSessionState
        scope.launch {
            if (state != null && state.isPlaying) {
                apiClient.sessionPause(rem.ip, rem.port)
            } else if (state != null && state.currentTitle.isNotBlank()) {
                apiClient.sessionPlay(rem.ip, rem.port)
            } else if (audioPlayer.currentSong != null) {
                startPlaybackOnRemote(audioPlayer.currentSong!!, audioPlayer.currentPosition, rem)
            } else {
                apiClient.sessionPlay(rem.ip, rem.port)
            }
        }
    }

    private fun resumeRemotePlayback() {
        val rem = (currentTarget as? PlaybackTarget.Remote) ?: return
        scope.launch {
            apiClient.sessionPlay(rem.ip, rem.port)
            val state = apiClient.fetchSessionState(rem.ip, rem.port)
            withContext(Dispatchers.Main) {
                latestRemoteSessionState = state
                renderRemoteState(rem, state)
            }
        }
    }

    private fun transferCurrentSongToRemote() {
        val rem = (currentTarget as? PlaybackTarget.Remote) ?: return
        val song = audioPlayer.currentSong
        if (song != null) {
            startPlaybackOnRemote(song, audioPlayer.currentPosition, rem)
        } else {
            onNavigateToLibrary()
            Toast.makeText(activity, "Choose a track to play on ${rem.device.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun transferRemoteToLocal() {
        val state = latestRemoteSessionState ?: return
        val rem = (currentTarget as? PlaybackTarget.Remote) ?: return

        scope.launch {
            try {
                apiClient.sessionPause(rem.ip, rem.port)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                // Check if matching local song exists in current queue or current song
                val localMatch = audioPlayer.queue.map { it.song }.find {
                    it.title.equals(state.currentTitle, ignoreCase = true) ||
                    (state.currentFilepath.isNotBlank() && it.filepath == state.currentFilepath)
                } ?: audioPlayer.currentSong?.takeIf {
                    it.title.equals(state.currentTitle, ignoreCase = true)
                }

                setPlaybackTarget(PlaybackTarget.Local)

                if (localMatch != null) {
                    audioPlayer.play(localMatch)
                    if (state.positionMs > 2000 && state.durationMs > 0) {
                        activity.window.decorView.postDelayed({
                            val dur = audioPlayer.duration
                            if (dur > 0) audioPlayer.seekTo(state.positionMs.toFloat() / dur.toFloat())
                        }, 600)
                    }
                    onLog("[SESSION] Resumed '${localMatch.title}' locally on phone")
                    Toast.makeText(activity, "Playing on Phone: ${localMatch.title}", Toast.LENGTH_SHORT).show()
                } else if (state.currentFilepath.isNotBlank()) {
                    val filename = File(state.currentFilepath).name
                    val encodedPath = URLEncoder.encode(state.currentFilepath, "UTF-8")
                    val streamUrl = "http://${rem.ip}:${rem.port}/api/song/stream?filepath=$encodedPath"
                    val song = Song(
                        id = System.currentTimeMillis(),
                        title = state.currentTitle.ifBlank { filename },
                        artist = state.currentArtist,
                        album = "Streamed from ${rem.device.name}",
                        filepath = state.currentFilepath,
                        filename = filename,
                        size = 0L,
                        sizeFormatted = "",
                        mtime = 0.0,
                        mtimeStr = "",
                        durationSec = state.durationMs / 1000.0,
                        durationFormatted = String.format(Locale.US, "%02d:%02d", state.durationMs / 1000 / 60, (state.durationMs / 1000) % 60),
                        bitrateKbps = "",
                        searchableText = "${state.currentTitle} ${state.currentArtist}"
                    )
                    audioPlayer.play(song, streamUrl)
                    if (state.positionMs > 2000 && state.durationMs > 0) {
                        activity.window.decorView.postDelayed({
                            val dur = audioPlayer.duration
                            if (dur > 0) audioPlayer.seekTo(state.positionMs.toFloat() / dur.toFloat())
                        }, 800)
                    }
                    onLog("[SESSION] Transferred '${state.currentTitle}' from ${rem.device.name} to phone")
                    Toast.makeText(activity, "Playing on Phone: ${state.currentTitle}", Toast.LENGTH_SHORT).show()
                } else {
                    onLog("[SESSION] Switched active playback target to This Device")
                    Toast.makeText(activity, "Switched to Phone", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setLocalSongs(songs: List<Song>) {
        localSongs = songs
        localSongMap = songs.associateBy { it.filename.lowercase(Locale.US) }
    }

    fun updatePlayerControlsState() {
        btnPlayerPrev?.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
        btnPlayerNext?.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
    }

    fun updatePlayerLikeButton(song: Song?) {
        if (song == null) {
            btnPlayerLike?.setImageResource(R.drawable.ic_favorite_border)
            btnPlayerLike?.setColorFilter(ContextCompat.getColor(activity, R.color.text_muted))
            return
        }
        val isLiked = playlistManager.isLiked(song)
        if (isLiked) {
            btnPlayerLike?.setImageResource(R.drawable.ic_favorite)
            btnPlayerLike?.setColorFilter(Color.parseColor("#FFFF0055"))
        } else {
            btnPlayerLike?.setImageResource(R.drawable.ic_favorite_border)
            btnPlayerLike?.setColorFilter(ContextCompat.getColor(activity, R.color.text_muted))
        }
    }

    private fun updateShuffleButton(shuffled: Boolean) {
        val yellow = ContextCompat.getColor(activity, R.color.yellow_primary)
        val muted = ContextCompat.getColor(activity, R.color.text_muted)
        btnPlayerShuffle?.setColorFilter(if (shuffled) yellow else muted)
    }

    private fun updateRepeatButton(mode: RepeatMode) {
        val yellow = ContextCompat.getColor(activity, R.color.yellow_primary)
        val muted = ContextCompat.getColor(activity, R.color.text_muted)
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

    fun updatePlayerScreenUI(song: Song?) {
        if (currentTarget is PlaybackTarget.Remote) {
            renderRemoteState(currentTarget as PlaybackTarget.Remote, latestRemoteSessionState)
            return
        }

        if (song == null) {
            layoutFpEmpty?.visibility = View.VISIBLE
            layoutFpActive?.visibility = View.GONE
            ivFpEmptyIcon?.setImageResource(R.drawable.ic_album)
            tvFpEmptyTitle?.text = "No Track Playing"
            tvFpEmptySubtitle?.text = "Choose a track from your Library or Playlists to start listening."
            btnFpEmptyResumeRemote?.visibility = View.GONE
            btnFpEmptyTransferHere?.visibility = View.GONE
            btnFpEmptyBrowse?.visibility = View.VISIBLE
            btnFpEmptySwitchLocal?.visibility = View.GONE
            return
        }
        layoutFpEmpty?.visibility = View.GONE
        layoutFpActive?.visibility = View.VISIBLE

        val isRemote = audioPlayer.currentItem?.isRemote ?: false
        val ext = File(song.filepath).extension.uppercase(Locale.US).ifBlank { "MP3" }

        tvFpTitle?.text = song.title.ifBlank { song.filename }
        tvFpArtist?.text = song.artist.ifBlank { "Unknown Artist" }
        tvFpAlbum?.text = if (song.album.isNotBlank() && song.album != "Unknown Album") song.album else "Music Sync Library"

        tvFpBadgeFormat?.text = ext
        tvFpBadgeBitrate?.text = song.bitrateKbps.ifBlank { "320 kbps" }.replace(" (CBR)", "").replace(" (Lossless)", "")
        tvFpBadgeSamplerate?.text = "44.1 kHz"

        if (isRemote) {
            tvFpBadgeSource?.text = "OVER-IP"
            tvFpBadgeSource?.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))
            tvFpBadgeSource?.setBackgroundResource(R.drawable.bg_badge_yellow)
            val alreadyLocal = localSongMap.containsKey(song.filename.lowercase(Locale.US)) ||
                    localSongs.any { it.filename.equals(song.filename, ignoreCase = true) }
            btnFpDownload?.visibility = View.VISIBLE
            if (alreadyLocal) {
                btnFpDownload?.setColorFilter(ContextCompat.getColor(activity, R.color.status_online))
                btnFpDownload?.contentDescription = "Already on Device"
                btnFpDownload?.alpha = 0.55f
            } else {
                btnFpDownload?.setColorFilter(ContextCompat.getColor(activity, R.color.yellow_primary))
                btnFpDownload?.contentDescription = "Download to Device"
                btnFpDownload?.alpha = 1.0f
            }
        } else {
            tvFpBadgeSource?.text = "LOCAL"
            tvFpBadgeSource?.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
            tvFpBadgeSource?.setBackgroundResource(R.drawable.bg_badge_green)
            btnFpDownload?.visibility = View.GONE
        }
        btnFpTransferLocal?.visibility = View.GONE

        updatePlayerScreenQueue()
        updatePlayerScreenLike(song)
        updatePlayerScreenControls()

        val totalMs = audioPlayer.duration
        val currentMs = audioPlayer.currentPosition
        if (totalMs > 0) {
            val ratio = (currentMs.toFloat() / totalMs.toFloat()) * 1000f
            fpSeekbar?.progress = ratio.toInt()
            val curMin = currentMs / 1000 / 60
            val curSec = (currentMs / 1000) % 60
            val totMin = totalMs / 1000 / 60
            val totSec = (totalMs / 1000) % 60
            tvFpCurrentTime?.text = String.format(Locale.US, "%02d:%02d", curMin, curSec)
            tvFpTotalTime?.text = String.format(Locale.US, "%02d:%02d", totMin, totSec)
        }

        scope.launch(Dispatchers.IO) {
            val bitmap = if (!isRemote) AudioInfoHelper.getEmbeddedArtwork(song) else null
            val streamUrl = if (isRemote) getStreamUrl(song) else null
            val details = AudioInfoHelper.extract(song, streamUrl)
            withContext(Dispatchers.Main) {
                if (audioPlayer.currentSong?.id == song.id) {
                    if (bitmap != null) {
                        ivFpArtwork?.setImageBitmap(bitmap)
                        ivFpArtwork?.colorFilter = null
                        ivFpArtwork?.scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        ivFpArtwork?.setImageResource(R.drawable.ic_album)
                        ivFpArtwork?.setColorFilter(ContextCompat.getColor(activity, R.color.card_stroke))
                        ivFpArtwork?.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
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

    fun updatePlayerScreenQueue() {
        if (currentTarget is PlaybackTarget.Remote) {
            val rem = currentTarget as PlaybackTarget.Remote
            tvFpQueuePos?.text = "Playing on ${rem.device.name}"
            val sz = latestRemoteSessionState?.queueSize ?: 0
            tvFpQueueCount?.text = if (sz > 0) "$sz in queue" else "0 in queue"
            fpQueueAdapter.submitQueue(emptyList(), -1)
            return
        }

        val q = audioPlayer.queue
        val qIdx = audioPlayer.currentIndex
        if (q.isNotEmpty() && qIdx >= 0) {
            tvFpQueuePos?.text = "Track ${qIdx + 1} of ${q.size}"
            tvFpQueueCount?.text = "${q.size} tracks"
            fpQueueAdapter.submitQueue(q, qIdx)
        } else {
            tvFpQueuePos?.text = "Now Playing"
            tvFpQueueCount?.text = "0 tracks"
            fpQueueAdapter.submitQueue(emptyList(), -1)
        }
    }

    fun updatePlayerScreenControls() {
        btnFpPlayPause?.setImageResource(if (audioPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnFpShuffle?.setColorFilter(if (audioPlayer.isShuffled) ContextCompat.getColor(activity, R.color.yellow_primary) else ContextCompat.getColor(activity, R.color.text_muted))
        when (audioPlayer.repeatMode) {
            RepeatMode.OFF -> {
                btnFpRepeat?.setImageResource(R.drawable.ic_repeat)
                btnFpRepeat?.setColorFilter(ContextCompat.getColor(activity, R.color.text_muted))
            }
            RepeatMode.ALL -> {
                btnFpRepeat?.setImageResource(R.drawable.ic_repeat)
                btnFpRepeat?.setColorFilter(ContextCompat.getColor(activity, R.color.yellow_primary))
            }
            RepeatMode.ONE -> {
                btnFpRepeat?.setImageResource(R.drawable.ic_repeat_one)
                btnFpRepeat?.setColorFilter(ContextCompat.getColor(activity, R.color.yellow_primary))
            }
        }
        btnFpPrev?.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
        btnFpNext?.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
    }

    fun updatePlayerScreenLike(song: Song?) {
        if (song == null) {
            btnFpLike?.setImageResource(R.drawable.ic_favorite_border)
            btnFpLike?.setColorFilter(ContextCompat.getColor(activity, R.color.text_muted))
            return
        }
        val isLiked = playlistManager.isLiked(song)
        btnFpLike?.setImageResource(if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        btnFpLike?.setColorFilter(if (isLiked) Color.parseColor("#FFFF0055") else ContextCompat.getColor(activity, R.color.text_muted))
    }

    fun showNowPlayingDialog() {
        nowPlayingHelper.show(
            initialProgressRatio = playerSeekbar?.progress,
            initialTimeFormatted = tvPlayerTime?.text?.toString()
        )
    }
}
