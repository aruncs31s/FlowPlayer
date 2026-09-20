package com.aruncs.musicsync.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.aruncs.musicsync.model.Song
import java.io.File
import java.util.Locale

data class AudioTechnicalDetails(
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val trackNumber: String,
    val discNumber: String,
    val year: String,
    val genre: String,
    val composer: String,
    val mimeType: String,
    val formatLabel: String,
    val containerFormat: String,
    val bitrateKbps: String,
    val sampleRateHz: String,
    val bitDepth: String,
    val channels: String,
    val durationFormatted: String,
    val sizeFormatted: String,
    val sizeBytes: Long,
    val filePath: String,
    val isRemote: Boolean
)

object AudioInfoHelper {

    fun extract(song: Song, streamUrl: String? = null): AudioTechnicalDetails {
        var title = song.title.ifBlank { song.filename }
        var artist = song.artist.ifBlank { "Unknown Artist" }
        var albumArtist = "Unknown"
        var album = song.album.ifBlank { "Unknown Album" }
        var trackNumber = ""
        var discNumber = ""
        var year = ""
        var genre = ""
        var composer = ""
        var mimeType = ""
        var bitrate = song.bitrateKbps
        var sampleRate = ""
        var bitDepth = ""
        var channels = "2 (Stereo)"

        val isRemote = !streamUrl.isNullOrEmpty()
        val path = if (isRemote) streamUrl!! else song.filepath
        val ext = if (isRemote) {
            val dot = song.filename.lastIndexOf('.')
            if (dot >= 0) song.filename.substring(dot + 1).uppercase(Locale.US) else "MP3"
        } else {
            val f = File(song.filepath)
            if (f.extension.isNotBlank()) f.extension.uppercase(Locale.US) else "MP3"
        }

        val localFile = if (!isRemote) File(song.filepath) else null
        val fileSize = localFile?.length() ?: song.size

        if (localFile != null && localFile.exists() && localFile.canRead()) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(localFile.absolutePath)

                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                    if (it.isNotBlank()) title = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                    if (it.isNotBlank()) artist = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let {
                    if (it.isNotBlank()) albumArtist = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                    if (it.isNotBlank()) album = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let {
                    trackNumber = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let {
                    discNumber = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let {
                    year = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let {
                    genre = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.let {
                    composer = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                    mimeType = it
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let {
                    val br = it.toLongOrNull()
                    if (br != null && br > 0) {
                        bitrate = "${br / 1000} kbps"
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.let {
                        val sr = it.toIntOrNull()
                        if (sr != null && sr > 0) {
                            sampleRate = String.format(Locale.US, "%,d Hz (%.1f kHz)", sr, sr / 1000.0)
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.let {
                        if (it.isNotBlank() && it != "0") {
                            bitDepth = "$it-bit"
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

        if (albumArtist.isBlank() || albumArtist == "Unknown") {
            albumArtist = artist
        }
        if (bitrate.isBlank() || bitrate == "Unknown") {
            bitrate = if (ext == "FLAC") "1411 kbps (Lossless)" else "320 kbps (CBR)"
        }
        if (sampleRate.isBlank()) {
            sampleRate = "44,100 Hz (44.1 kHz)"
        }
        if (bitDepth.isBlank()) {
            bitDepth = if (ext == "FLAC" || ext == "WAV") "24-bit" else "16-bit"
        }
        if (mimeType.isBlank()) {
            mimeType = when (ext) {
                "FLAC" -> "audio/flac"
                "M4A", "AAC" -> "audio/mp4"
                "OGG" -> "audio/ogg"
                "WAV" -> "audio/wav"
                else -> "audio/mpeg"
            }
        }

        val formatLabel = when (ext) {
            "FLAC" -> "FLAC (Free Lossless Audio Codec)"
            "MP3" -> "MP3 (MPEG-1 Audio Layer III)"
            "M4A", "AAC" -> "AAC (Advanced Audio Coding)"
            "OGG" -> "Ogg Vorbis / Opus"
            "WAV" -> "WAV (Linear PCM)"
            else -> "$ext Audio File"
        }

        val formattedSize = if (song.sizeFormatted.isNotBlank()) {
            song.sizeFormatted
        } else if (fileSize > 0) {
            String.format(Locale.US, "%.2f MB", fileSize / (1024.0 * 1024.0))
        } else {
            "Unknown"
        }

        return AudioTechnicalDetails(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            trackNumber = trackNumber.ifBlank { "—" },
            discNumber = discNumber.ifBlank { "1" },
            year = year.ifBlank { "—" },
            genre = genre.ifBlank { "Music" },
            composer = composer.ifBlank { "—" },
            mimeType = mimeType,
            formatLabel = formatLabel,
            containerFormat = ext,
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            durationFormatted = song.durationFormatted.ifBlank { "00:00" },
            sizeFormatted = formattedSize,
            sizeBytes = fileSize,
            filePath = path,
            isRemote = isRemote
        )
    }

    fun getEmbeddedArtwork(song: Song): Bitmap? {
        if (song.filepath.isBlank()) return null
        val f = File(song.filepath)
        if (!f.exists() || !f.canRead()) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(f.absolutePath)
            val picture = retriever.embeddedPicture
            if (picture != null) {
                BitmapFactory.decodeByteArray(picture, 0, picture.size)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
