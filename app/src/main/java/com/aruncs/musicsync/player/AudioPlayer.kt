package com.aruncs.musicsync.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.aruncs.musicsync.data.CrashLogger
import com.aruncs.musicsync.model.Song
import java.io.File
import java.util.Collections

data class PlayableItem(
    val song: Song,
    val streamUrl: String? = null
) {
    val isRemote: Boolean get() = !streamUrl.isNullOrBlank()
}

enum class RepeatMode {
    OFF, ALL, ONE
}

class AudioPlayer {

    private var mediaPlayer: MediaPlayer? = null
    var currentSong: Song? = null
        private set

    val currentItem: PlayableItem?
        get() = if (currentIndex in 0 until _queue.size) _queue[currentIndex] else null

    val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    val currentPosition: Int
        get() = try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }

    val duration: Int
        get() = try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }

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
        get() = currentIndex > 0 || currentPosition > 3000

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    // Safeguard against unbounded error recursion
    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 4

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
        consecutiveErrors = 0
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
        consecutiveErrors = 0
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
            consecutiveErrors = 0
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
            consecutiveErrors = 0
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
            try {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
                onStateChanged?.invoke(true)
                startProgressUpdates()
                return true
            } catch (e: Exception) {
                CrashLogger.logPlayerError("AudioPlayer", "Error looping track in Repeat.ONE: ${e.message}", e)
            }
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
        val pos = currentPosition
        if (pos > 3000) {
            try {
                mediaPlayer?.seekTo(0)
                return true
            } catch (e: Exception) {
                CrashLogger.logPlayerError("AudioPlayer", "Error rewinding track: ${e.message}", e)
            }
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
        consecutiveErrors = 0
        onQueueChanged?.invoke(_queue, currentIndex)
        onModeChanged?.invoke(isShuffled, repeatMode)
        stop()
    }

    fun restoreState(item: PlayableItem) {
        _queue.clear()
        _queue.add(item)
        originalQueue.clear()
        originalQueue.add(item)
        currentIndex = 0
        consecutiveErrors = 0
        currentSong = item.song
        CrashLogger.currentPlayingSong = item.song
        onQueueChanged?.invoke(_queue, currentIndex)
        onModeChanged?.invoke(isShuffled, repeatMode)
        onTrackChanged?.invoke(item.song)
        onStateChanged?.invoke(false)
    }

    private fun playItem(item: PlayableItem) {
        val song = item.song
        val streamUrl = item.streamUrl
        val isRemote = streamUrl != null

        var file = if (!isRemote) File(song.filepath) else null
        if (!isRemote && (file == null || !file.exists() || !file.isFile)) {
            val context = CrashLogger.appContext
            val fallbackFile = if (context != null) {
                try {
                    val musicDir = com.aruncs.musicsync.data.AppPreferences(context).musicStorageDirectory
                    val cand1 = File(musicDir, song.filename)
                    val cand2 = File(File(musicDir, "ADB"), song.filename)
                    when {
                        cand1.exists() && cand1.isFile -> cand1
                        cand2.exists() && cand2.isFile -> cand2
                        else -> null
                    }
                } catch (ignored: Throwable) {
                    null
                }
            } else null

            if (fallbackFile != null) {
                file = fallbackFile
            } else {
                val err = "File not found on device: ${song.filepath}"
                CrashLogger.logPlayerError("AudioPlayer", err)
                onError?.invoke(err)
                consecutiveErrors++
                if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && hasNext) {
                    handler.post { playNext() }
                } else if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    stop()
                }
                return
            }
        }

        stopProgressUpdates()

        // Safely dispose old MediaPlayer instance
        try {
            mediaPlayer?.apply {
                try {
                    setOnCompletionListener(null)
                    setOnErrorListener(null)
                    setOnPreparedListener(null)
                    if (isPlaying) stop()
                } catch (ignored: Exception) {}
                try { reset() } catch (ignored: Exception) {}
                try { release() } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            CrashLogger.logPlayerError("AudioPlayer", "Error releasing previous MediaPlayer instance: ${e.message}", e)
        }
        mediaPlayer = null

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                setOnCompletionListener {
                    try {
                        if (!playNext()) {
                            onStateChanged?.invoke(false)
                            stopProgressUpdates()
                        }
                    } catch (e: Exception) {
                        CrashLogger.logPlayerError("AudioPlayer", "Error in onCompletion: ${e.message}", e)
                    }
                }

                setOnErrorListener { _, what, extra ->
                    val whatStr = when (what) {
                        MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "SERVER_DIED"
                        MediaPlayer.MEDIA_ERROR_UNKNOWN -> "UNKNOWN"
                        else -> "code $what"
                    }
                    val extraStr = when (extra) {
                        MediaPlayer.MEDIA_ERROR_IO -> "IO_ERROR"
                        MediaPlayer.MEDIA_ERROR_MALFORMED -> "MALFORMED_STREAM"
                        MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "UNSUPPORTED_CODEC"
                        MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "TIMEOUT"
                        -2147483648 -> "SYSTEM_ERROR"
                        else -> "extra $extra"
                    }
                    val trackTitle = song.title.ifBlank { song.filename }
                    val errorDetail = "Playback error ($whatStr, $extraStr) on '$trackTitle' [${if (isRemote) streamUrl else song.filepath}]"
                    CrashLogger.logPlayerError("AudioPlayer", errorDetail)
                    onError?.invoke("Playback error: $whatStr ($extraStr)")

                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        CrashLogger.logPlayerError("AudioPlayer", "Playback halted: $consecutiveErrors consecutive errors encountered")
                        stop()
                    } else if (hasNext) {
                        handler.post { playNext() }
                    }
                    true
                }

                if (isRemote) {
                    setDataSource(streamUrl)
                    setOnPreparedListener { mp ->
                        try {
                            mp.start()
                            consecutiveErrors = 0
                            currentSong = song
                            CrashLogger.currentPlayingSong = song
                            onTrackChanged?.invoke(song)
                            onStateChanged?.invoke(true)
                            startProgressUpdates()
                        } catch (e: Exception) {
                            CrashLogger.logPlayerError("AudioPlayer", "Failed to start playback after prepareAsync: ${e.message}", e)
                            onError?.invoke("Playback start failed: ${e.message}")
                        }
                    }
                    prepareAsync()
                } else {
                    setDataSource(file!!.absolutePath)
                    prepare()
                    start()
                    consecutiveErrors = 0
                    currentSong = song
                    CrashLogger.currentPlayingSong = song
                    onTrackChanged?.invoke(song)
                    onStateChanged?.invoke(true)
                    startProgressUpdates()
                }
            }
        } catch (e: Exception) {
            val trackName = song.title.ifBlank { song.filename }
            CrashLogger.logPlayerError("AudioPlayer", "Exception initializing MediaPlayer for '$trackName': ${e.message}", e)
            onError?.invoke("Playback error: ${e.message}")
            consecutiveErrors++
            if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && hasNext) {
                handler.post { playNext() }
            }
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp == null) {
            if (currentIndex in 0 until _queue.size) {
                playItem(_queue[currentIndex])
            }
            return
        }
        try {
            val playing = try { mp.isPlaying } catch (e: Exception) { false }
            if (playing) {
                mp.pause()
                stopProgressUpdates()
                onStateChanged?.invoke(false)
            } else {
                mp.start()
                startProgressUpdates()
                onStateChanged?.invoke(true)
            }
        } catch (e: Exception) {
            CrashLogger.logPlayerError("AudioPlayer", "Error toggling play/pause: ${e.message}", e)
            onError?.invoke("Playback control error: ${e.message}")
        }
    }

    fun seekTo(ratio: Float) {
        val mp = mediaPlayer ?: return
        try {
            val dur = mp.duration
            if (dur > 0) {
                val targetMs = (dur * ratio.coerceIn(0f, 1f)).toInt()
                mp.seekTo(targetMs)
            }
        } catch (e: Exception) {
            CrashLogger.logPlayerError("AudioPlayer", "Error seeking: ${e.message}", e)
        }
    }

    fun stop() {
        stopProgressUpdates()
        try {
            mediaPlayer?.apply {
                try {
                    setOnCompletionListener(null)
                    setOnErrorListener(null)
                    setOnPreparedListener(null)
                    if (isPlaying) stop()
                } catch (ignored: Exception) {}
                try { reset() } catch (ignored: Exception) {}
                try { release() } catch (ignored: Exception) {}
            }
        } catch (e: Exception) {
            CrashLogger.logPlayerError("AudioPlayer", "Error during stop/release: ${e.message}", e)
        } finally {
            mediaPlayer = null
            currentSong = null
            CrashLogger.currentPlayingSong = null
            onStateChanged?.invoke(false)
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null) {
                    val playing = try { mp.isPlaying } catch (e: Exception) { false }
                    if (playing) {
                        try {
                            val cur = mp.currentPosition
                            val dur = mp.duration
                            if (dur > 0) {
                                onProgress?.invoke(cur, dur)
                            }
                        } catch (e: Exception) {
                            CrashLogger.logPlayerError("AudioPlayer", "Error reading playback progress: ${e.message}", e)
                        }
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
