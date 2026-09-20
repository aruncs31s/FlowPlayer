package com.aruncs.musicsync.ui.controller

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.RepeatMode
import com.aruncs.musicsync.server.OverIpServer
import com.aruncs.musicsync.session.SessionState
import java.io.File

class SessionSyncController(
    private val context: Context,
    private val audioPlayer: AudioPlayer,
    private val onPlayerControlsUpdate: () -> Unit,
    private val onLog: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun registerCallbacks() {
        OverIpServer.onSessionStateRequest = {
            val song = audioPlayer.currentSong
            val repMode = when (audioPlayer.repeatMode) {
                RepeatMode.OFF -> "off"
                RepeatMode.ALL -> "all"
                RepeatMode.ONE -> "one"
            }
            SessionState(
                deviceId = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ANDROID_ID
                ) ?: Build.MODEL,
                deviceName = Build.MODEL,
                deviceRole = "android",
                isPlaying = audioPlayer.isPlaying,
                currentTitle = song?.title ?: "",
                currentArtist = song?.artist ?: "",
                currentFilepath = song?.filepath ?: "",
                positionMs = audioPlayer.currentPosition,
                durationMs = audioPlayer.duration,
                queueSize = audioPlayer.queue.size,
                repeatMode = repMode,
                isShuffled = audioPlayer.isShuffled
            )
        }

        OverIpServer.onSessionPlay = {
            mainHandler.post {
                if (!audioPlayer.isPlaying) audioPlayer.togglePlayPause()
                onPlayerControlsUpdate()
            }
        }

        OverIpServer.onSessionPause = {
            mainHandler.post {
                if (audioPlayer.isPlaying) audioPlayer.togglePlayPause()
                onPlayerControlsUpdate()
            }
        }

        OverIpServer.onSessionNext = {
            mainHandler.post { audioPlayer.playNext() }
        }

        OverIpServer.onSessionPrev = {
            mainHandler.post { audioPlayer.playPrevious() }
        }

        OverIpServer.onSessionSeek = { posMs ->
            mainHandler.post {
                val dur = audioPlayer.duration
                if (dur > 0) audioPlayer.seekTo(posMs.toFloat() / dur.toFloat())
            }
        }

        OverIpServer.onSessionQueueInject = { filepath, title, artist, album, streamUrl ->
            mainHandler.post {
                val filename = File(filepath).name
                val song = Song(
                    id = System.currentTimeMillis(),
                    title = title.ifBlank { filename },
                    artist = artist,
                    album = album,
                    filepath = filepath,
                    filename = filename,
                    size = 0L,
                    sizeFormatted = "",
                    mtime = 0.0,
                    mtimeStr = "",
                    durationSec = 0.0,
                    durationFormatted = "",
                    bitrateKbps = "",
                    searchableText = "$title $artist"
                )
                audioPlayer.addToQueueNext(song, streamUrl)
                onLog("[SESSION] '$title' injected into queue from remote")
                Toast.makeText(context, "Added '$title' to queue", Toast.LENGTH_SHORT).show()
            }
        }

        OverIpServer.onSessionTransfer = { filepath, title, artist, album, positionMs, streamUrl ->
            mainHandler.post {
                val filename = File(filepath).name
                val song = Song(
                    id = System.currentTimeMillis(),
                    title = title.ifBlank { filename },
                    artist = artist,
                    album = album,
                    filepath = filepath,
                    filename = filename,
                    size = 0L,
                    sizeFormatted = "",
                    mtime = 0.0,
                    mtimeStr = "",
                    durationSec = 0.0,
                    durationFormatted = "",
                    bitrateKbps = "",
                    searchableText = "$title $artist"
                )
                audioPlayer.play(song, streamUrl)
                if (positionMs > 2000) {
                    mainHandler.postDelayed({
                        val dur = audioPlayer.duration
                        if (dur > 0) audioPlayer.seekTo(positionMs.toFloat() / dur.toFloat())
                    }, 800)
                }
                onLog("[SESSION] Transferred '$title' from remote peer")
                Toast.makeText(context, "Playing: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
