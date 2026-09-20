package com.aruncs.musicsync.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Native Android Audio Transcoder & Downconverter.
 * Uses Android hardware/software MediaCodec, MediaExtractor, and MediaMuxer (API 24+).
 * Requires zero native C++ binaries, adds 0 MB to APK footprint.
 * Downconverts high-bitrate or lossless audio (FLAC, 320k MP3) to high-efficiency AAC (.m4a)
 * at customizable bitrates (128 kbps, 192 kbps, 256 kbps).
 */
object AudioTranscoderHelper {

    private const val TAG = "AudioTranscoder"
    private const val TIMEOUT_US = 10000L

    fun detectBitrate(file: File): Int? {
        if (!file.exists() || !file.isFile) return null

        val ext = file.extension.lowercase()
        if (ext in listOf("flac", "wav", "alac")) {
            return 999 // Lossless treated as maximum quality
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            brStr?.toIntOrNull()?.let { it / 1000 }
        } catch (e: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (ignored: Throwable) {}
        }
    }

    fun needsDownconversion(sourceFile: File, targetBitrateKbps: Int?): Boolean {
        if (targetBitrateKbps == null || targetBitrateKbps <= 0) return false
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() <= 0L) return false
        val currentBr = try { detectBitrate(sourceFile) } catch (e: Throwable) { null } ?: return false
        return currentBr > targetBitrateKbps
    }

    /**
     * Transcode source audio file to AAC (.m4a) at targetBitrateKbps.
     * Returns the transcoded File on success, or sourceFile on failure/fallback.
     */
    @Synchronized
    fun transcodeAudio(
        sourceFile: File,
        targetBitrateKbps: Int,
        cacheDir: File? = null,
        destinationFile: File? = null
    ): File {
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() <= 0L) return sourceFile

        val targetParent = destinationFile?.parentFile
            ?: cacheDir
            ?: sourceFile.parentFile
            ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        try { targetParent.mkdirs() } catch (ignored: Throwable) {}

        val targetFile = destinationFile ?: File(
            targetParent,
            "transcoded_${System.currentTimeMillis()}_${sourceFile.nameWithoutExtension}_${targetBitrateKbps}k.m4a"
        )

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                Log.w(TAG, "No audio track found in ${sourceFile.name}, returning original")
                return sourceFile
            }

            extractor.selectTrack(audioTrackIndex)

            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            // 1. Configure Decoder
            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // 2. Configure Encoder (AAC audio/mp4a-latm)
            val outputMime = MediaFormat.MIMETYPE_AUDIO_AAC
            val outputFormat = MediaFormat.createAudioFormat(outputMime, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateKbps * 1000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

            encoder = MediaCodec.createEncoderByType(outputMime)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // 3. Configure Muxer
            if (targetFile.exists()) targetFile.delete()
            targetFile.parentFile?.mkdirs()
            muxer = MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxerTrackIndex = -1
            muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                // Feed Extractor -> Decoder
                if (!extractorDone) {
                    val inputBufIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIdx)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputBufIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                extractorDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputBufIdx, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain Decoder -> Feed Encoder
                if (!decoderDone) {
                    val decOutIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (decOutIdx >= 0) {
                        val decBuf = decoder.getOutputBuffer(decOutIdx)
                        if (decBuf != null && bufferInfo.size > 0) {
                            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                val encInBuf = encoder.getInputBuffer(encInIdx)
                                if (encInBuf != null) {
                                    encInBuf.clear()
                                    decBuf.position(bufferInfo.offset)
                                    decBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    encInBuf.put(decBuf)
                                    encoder.queueInputBuffer(
                                        encInIdx, 0, bufferInfo.size, bufferInfo.presentationTimeUs, 0
                                    )
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(
                                    encInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                            }
                            decoderDone = true
                        }
                    }
                }

                // Drain Encoder -> Muxer
                val encOutIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw RuntimeException("Format changed twice in muxer")
                    val newFormat = encoder.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encOutIdx >= 0) {
                    val encOutBuf = encoder.getOutputBuffer(encOutIdx)
                    if (encOutBuf != null && muxerStarted && bufferInfo.size > 0) {
                        encOutBuf.position(bufferInfo.offset)
                        encOutBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, encOutBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                }
            }

            Log.i(TAG, "Successfully downconverted ${sourceFile.name} -> ${targetFile.name} (${targetFile.length()} bytes)")
            return targetFile
        } catch (e: Throwable) {
            Log.e(TAG, "Transcoding error for ${sourceFile.name}: ${e.message}, falling back to original", e)
            try {
                if (targetFile.exists()) targetFile.delete()
            } catch (ignored: Throwable) {}
            return sourceFile
        } finally {
            try { decoder?.stop() } catch (ignored: Throwable) {}
            try { decoder?.release() } catch (ignored: Throwable) {}
            try { encoder?.stop() } catch (ignored: Throwable) {}
            try { encoder?.release() } catch (ignored: Throwable) {}
            try { extractor?.release() } catch (ignored: Throwable) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (ignored: Throwable) {}
            }
            try { muxer?.release() } catch (ignored: Throwable) {}
        }
    }
}
