package com.aruncs.musicsync.ui.dialog

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aruncs.musicsync.R
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioInfoHelper
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.RepeatMode
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

class NowPlayingDialogHelper(
    private val activity: Activity,
    private val audioPlayer: AudioPlayer,
    private val playlistManager: PlaylistManager,
    private val getStreamUrl: (Song) -> String?,
    private val onShowAudioInfo: (Song) -> Unit,
    private val onShowQueue: () -> Unit,
    private val onShowSongOptions: (Song) -> Unit,
    private val onToggleLike: (Song) -> Unit,
    private val onModeChangedUI: ((Boolean, RepeatMode) -> Unit)? = null
) {
    private var nowPlayingDialog: BottomSheetDialog? = null
    var onSongUpdated: ((Song) -> Unit)? = null
        private set
    var onStateUpdated: ((Boolean) -> Unit)? = null
        private set
    var onProgressUpdated: ((Int, Int) -> Unit)? = null
        private set
    var onModeUpdated: ((Boolean, RepeatMode) -> Unit)? = null
        private set

    val isShowing: Boolean
        get() = nowPlayingDialog?.isShowing == true

    fun dismiss() {
        nowPlayingDialog?.dismiss()
    }

    fun show(initialProgressRatio: Int? = null, initialTimeFormatted: String? = null) {
        val currentSong = audioPlayer.currentSong ?: return
        if (nowPlayingDialog?.isShowing == true) return

        val dialog = BottomSheetDialog(activity, com.google.android.material.R.style.Theme_Design_BottomSheetDialog)
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_now_playing, null)
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

        tvTitle.isSelected = true

        fun updateNowPlayingSongUI(song: Song) {
            val streamUrl = getStreamUrl(song)
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
                tvBadgeSource.setTextColor(ContextCompat.getColor(activity, R.color.yellow_primary))
                tvBadgeSource.setBackgroundResource(R.drawable.bg_badge_yellow)
            } else {
                tvBadgeSource.text = "LOCAL"
                tvBadgeSource.setTextColor(ContextCompat.getColor(activity, R.color.status_online))
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
            btnLike.setColorFilter(if (isLiked) ContextCompat.getColor(activity, R.color.status_offline) else ContextCompat.getColor(activity, R.color.text_muted))

            btnPrev.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
            btnNext.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
        }

        fun updateNowPlayingControls() {
            btnPlayPause.setImageResource(if (audioPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            btnShuffle.setColorFilter(if (audioPlayer.isShuffled) ContextCompat.getColor(activity, R.color.yellow_primary) else ContextCompat.getColor(activity, R.color.text_muted))
            when (audioPlayer.repeatMode) {
                RepeatMode.OFF -> {
                    btnRepeat.setImageResource(R.drawable.ic_repeat)
                    btnRepeat.setColorFilter(ContextCompat.getColor(activity, R.color.text_muted))
                }
                RepeatMode.ALL -> {
                    btnRepeat.setImageResource(R.drawable.ic_repeat)
                    btnRepeat.setColorFilter(ContextCompat.getColor(activity, R.color.yellow_primary))
                }
                RepeatMode.ONE -> {
                    btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                    btnRepeat.setColorFilter(ContextCompat.getColor(activity, R.color.yellow_primary))
                }
            }
            btnPrev.alpha = if (audioPlayer.hasPrevious) 1.0f else 0.35f
            btnNext.alpha = if (audioPlayer.hasNext) 1.0f else 0.35f
        }

        updateNowPlayingSongUI(currentSong)
        updateNowPlayingControls()

        if (initialProgressRatio != null) {
            npSeekbar.progress = initialProgressRatio
        }
        if (initialTimeFormatted != null) {
            val parts = initialTimeFormatted.split("/")
            tvCurrentTime.text = parts.firstOrNull()?.trim() ?: "00:00"
            tvTotalTime.text = parts.getOrNull(1)?.trim() ?: currentSong.durationFormatted
        } else {
            tvCurrentTime.text = "00:00"
            tvTotalTime.text = currentSong.durationFormatted
        }

        btnCollapse.setOnClickListener { dialog.dismiss() }

        layoutAlbumArt.setOnClickListener { audioPlayer.currentSong?.let { onShowAudioInfo(it) } }
        layoutAlbumArt.setOnLongClickListener {
            audioPlayer.currentSong?.let { onShowAudioInfo(it) }
            true
        }
        layoutSongInfo.setOnClickListener { audioPlayer.currentSong?.let { onShowAudioInfo(it) } }
        layoutSongInfo.setOnLongClickListener {
            audioPlayer.currentSong?.let { onShowAudioInfo(it) }
            true
        }
        btnInfo.setOnClickListener { audioPlayer.currentSong?.let { onShowAudioInfo(it) } }
        btnAudioInfo.setOnClickListener { audioPlayer.currentSong?.let { onShowAudioInfo(it) } }

        btnQueue.setOnClickListener { onShowQueue() }

        btnPlayPause.setOnClickListener {
            audioPlayer.togglePlayPause()
            updateNowPlayingControls()
        }

        btnPrev.setOnClickListener { audioPlayer.playPrevious() }
        btnNext.setOnClickListener { audioPlayer.playNext() }

        btnShuffle.setOnClickListener {
            val s = audioPlayer.toggleShuffle()
            onModeChangedUI?.invoke(s, audioPlayer.repeatMode)
            updateNowPlayingControls()
            Toast.makeText(activity, if (s) "Shuffle On" else "Shuffle Off", Toast.LENGTH_SHORT).show()
        }

        btnRepeat.setOnClickListener {
            val m = audioPlayer.toggleRepeat()
            onModeChangedUI?.invoke(audioPlayer.isShuffled, m)
            updateNowPlayingControls()
            val msg = when (m) {
                RepeatMode.OFF -> "Repeat Off"
                RepeatMode.ALL -> "Repeat All"
                RepeatMode.ONE -> "Repeat One"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }

        btnLike.setOnClickListener {
            audioPlayer.currentSong?.let { song ->
                onToggleLike(song)
                val isLiked = playlistManager.isLiked(song)
                btnLike.setImageResource(if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
                btnLike.setColorFilter(if (isLiked) ContextCompat.getColor(activity, R.color.status_offline) else ContextCompat.getColor(activity, R.color.text_muted))
            }
        }

        btnOptions.setOnClickListener {
            audioPlayer.currentSong?.let { onShowSongOptions(it) }
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

        onSongUpdated = { song ->
            activity.runOnUiThread {
                if (dialog.isShowing) {
                    updateNowPlayingSongUI(song)
                    updateNowPlayingControls()
                }
            }
        }

        onStateUpdated = { _ ->
            activity.runOnUiThread {
                if (dialog.isShowing) {
                    updateNowPlayingControls()
                }
            }
        }

        onProgressUpdated = { currentMs, totalMs ->
            activity.runOnUiThread {
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

        onModeUpdated = { _, _ ->
            activity.runOnUiThread {
                if (dialog.isShowing) {
                    updateNowPlayingControls()
                }
            }
        }

        dialog.setOnDismissListener {
            nowPlayingDialog = null
            onSongUpdated = null
            onStateUpdated = null
            onProgressUpdated = null
            onModeUpdated = null
        }

        nowPlayingDialog = dialog
        dialog.show()
    }
}
