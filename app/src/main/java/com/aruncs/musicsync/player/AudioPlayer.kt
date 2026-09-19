package com.aruncs.musicsync.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.aruncs.musicsync.model.Song
import java.io.File
import java.util.Collections

data class PlayableItem(
    val song: Song,
    val streamUrl: String? = null
)

enum class RepeatMode {
    OFF, ALL, ONE
}

class AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    var currentSong: Song? = null
        private set

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    private val _queue = mutableListOf<PlayableItem>()
    private val originalQueue = mutableListOf<PlayableItem>()

    val queue: List<PlayableItem>
        get() = _queue.toList()

    var currentIndex: Int = -1
        private set

    var isShuffled: Boolean = false
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    val hasNext: Boolean
        get() = when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> _queue.isNotEmpty()
            RepeatMode.OFF -> currentIndex >= 0 && currentIndex < _queue.size - 1
        }

    val hasPrevious: Boolean
        get() = currentIndex > 0 || (mediaPlayer != null && (mediaPlayer?.currentPosition ?: 0) > 3000)

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    var onStateChanged: ((isPlaying: Boolean) -> Unit)? = null
    var onTrackChanged: ((Song) -> Unit)? = null
    var onProgress: ((currentMs: Int, totalMs: Int) -> Unit)? = null
    var onQueueChanged: ((queue: List<PlayableItem>, currentIndex: Int) -> Unit)? = null
    var onModeChanged: ((isShuffled: Boolean, repeatMode: RepeatMode) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun play(song: Song, streamUrl: String? = null) {
        val item = PlayableItem(song, streamUrl)
        _queue.clear()
        _queue.add(item)
        originalQueue.clear()
        originalQueue.add(item)
        currentIndex = 0
        isShuffled = false
        onQueueChanged?.invoke(_queue, currentIndex)
        onModeChanged?.invoke(isShuffled, repeatMode)
        playItem(item)
    }

    fun setQueue(items: List<PlayableItem>, startIndex: Int = 0) {
        _queue.clear()
        _queue.addAll(items)
        originalQueue.clear()
        originalQueue.addAll(items)
        isShuffled = false
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        onQueueChanged?.invoke(_queue, currentIndex)
        onModeChanged?.invoke(isShuffled, repeatMode)
        if (_queue.isNotEmpty()) {
            playItem(_queue[currentIndex])
        }
    }

    fun addToQueue(song: Song, streamUrl: String? = null) {
        val item = PlayableItem(song, streamUrl)
        val wasEmpty = _queue.isEmpty() || currentIndex == -1
        _queue.add(item)
        originalQueue.add(item)
        if (wasEmpty) {
            currentIndex = _queue.size - 1
            onQueueChanged?.invoke(_queue, currentIndex)
            playItem(item)
        } else {
            onQueueChanged?.invoke(_queue, currentIndex)
        }
    }

    fun addToQueueNext(song: Song, streamUrl: String? = null) {
        val item = PlayableItem(song, streamUrl)
        if (_queue.isEmpty() || currentIndex == -1) {
            addToQueue(song, streamUrl)
        } else {
            val insertAt = (currentIndex + 1).coerceAtMost(_queue.size)
            _queue.add(insertAt, item)
            originalQueue.add(item)
            onQueueChanged?.invoke(_queue, currentIndex)
        }
    }

    fun playTrackAtIndex(index: Int): Boolean {
        if (index in 0 until _queue.size) {
            currentIndex = index
            onQueueChanged?.invoke(_queue, currentIndex)
            playItem(_queue[currentIndex])
            return true
        }
        return false
    }

    fun toggleShuffle(): Boolean {
        if (_queue.isEmpty()) return false

        if (isShuffled) {
            // Restore original order
            val cur = currentSong
            _queue.clear()
            _queue.addAll(originalQueue)
            currentIndex = _queue.indexOfFirst { it.song.filepath == cur?.filepath }.coerceAtLeast(0)
            isShuffled = false
        } else {
            // Save original queue and shuffle
            originalQueue.clear()
            originalQueue.addAll(_queue)

            val currentItem = if (currentIndex in 0 until _queue.size) _queue[currentIndex] else null
            val rest = _queue.filterIndexed { idx, _ -> idx != currentIndex }.toMutableList()
            Collections.shuffle(rest)

            _queue.clear()
            if (currentItem != null) {
                _queue.add(currentItem)
                currentIndex = 0
            }
            _queue.addAll(rest)
            isShuffled = true
        }

        onQueueChanged?.invoke(_queue, currentIndex)
        onModeChanged?.invoke(isShuffled, repeatMode)
        return isShuffled
    }

    fun toggleRepeat(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        onModeChanged?.invoke(isShuffled, repeatMode)
        return repeatMode
    }

    fun playNext(): Boolean {
        if (_queue.isEmpty()) return false

        if (repeatMode == RepeatMode.ONE && mediaPlayer != null) {
            mediaPlayer?.seekTo(0)
            mediaPlayer?.start()
            onStateChanged?.invoke(true)
            startProgressUpdates()
            return true
        }

        if (currentIndex < _queue.size - 1) {
            currentIndex++
            onQueueChanged?.invoke(_queue, currentIndex)
            playItem(_queue[currentIndex])
            return true
        } else if (repeatMode == RepeatMode.ALL && _queue.isNotEmpty()) {
            currentIndex = 0
            onQueueChanged?.invoke(_queue, currentIndex)
            playItem(_queue[currentIndex])
            return true
        }
        return false
    }

    fun playPrevious(): Boolean {
        val mp = mediaPlayer
        if (mp != null && mp.currentPosition > 3000) {
            mp.seekTo(0)
            return true
        }
        if (currentIndex > 0) {
            currentIndex--
            onQueueChanged?.invoke(_queue, currentIndex)
            playItem(_queue[currentIndex])
            return true
        } else if (repeatMode == RepeatMode.ALL && _queue.isNotEmpty()) {
            currentIndex = _queue.size - 1
            onQueueChanged?.invoke(_queue, currentIndex)
            playItem(_queue[currentIndex])
            return true
        }
        return false
    }

    fun clearQueue() {
        _queue.clear()
        originalQueue.clear()
        currentIndex = -1
        isShuffled = false
        onQueueChanged?.invoke(_queue, currentIndex)
        onModeChanged?.invoke(isShuffled, repeatMode)
        stop()
    }

    private fun playItem(item: PlayableItem) {
        val song = item.song
        val streamUrl = item.streamUrl
        val isRemote = streamUrl != null

        val file = if (!isRemote) File(song.filepath) else null
        if (!isRemote && (file == null || !file.exists())) {
            onError?.invoke("File not found: ${song.filepath}")
            if (hasNext) playNext()
            return
        }

        stopProgressUpdates()

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                setOnCompletionListener {
                    if (!playNext()) {
                        onStateChanged?.invoke(false)
                        stopProgressUpdates()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    onError?.invoke("Playback error ($what, $extra)")
                    if (hasNext) playNext()
                    true
                }

                if (isRemote) {
                    setDataSource(streamUrl)
                    setOnPreparedListener { mp ->
                        mp.start()
                        currentSong = song
                        onTrackChanged?.invoke(song)
                        onStateChanged?.invoke(true)
                        startProgressUpdates()
                    }
                    prepareAsync()
                } else {
                    setDataSource(file!!.absolutePath)
                    prepare()
                    start()
                    currentSong = song
                    onTrackChanged?.invoke(song)
                    onStateChanged?.invoke(true)
                    startProgressUpdates()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onError?.invoke("Playback error: ${e.message}")
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            stopProgressUpdates()
            onStateChanged?.invoke(false)
        } else {
            mp.start()
            startProgressUpdates()
            onStateChanged?.invoke(true)
        }
    }

    fun seekTo(ratio: Float) {
        val mp = mediaPlayer ?: return
        val targetMs = (mp.duration * ratio.coerceIn(0f, 1f)).toInt()
        mp.seekTo(targetMs)
    }

    fun stop() {
        stopProgressUpdates()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        currentSong = null
        onStateChanged?.invoke(false)
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        try {
                            onProgress?.invoke(mp.currentPosition, mp.duration)
                        } catch (ignored: Exception) {}
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    fun release() {
        stop()
    }
}
