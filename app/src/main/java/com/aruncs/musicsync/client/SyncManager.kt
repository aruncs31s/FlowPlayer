package com.aruncs.musicsync.client

import android.content.Context
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.MediaScannerHelper
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.model.SyncDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class SyncManager(private val context: Context) {

    private val apiClient = DesktopApiClient()
    private val prefs = AppPreferences(context)

    fun calculateDiff(localSongs: List<Song>, desktopSongs: List<Song>): SyncDiff {
        val localNames = HashSet<String>()
        val localKeys = HashSet<String>()

        for (s in localSongs) {
            localNames.add(s.filename.lowercase(Locale.US))
            localKeys.add(normalizeKey(s.title, s.artist))
        }

        val desktopNames = HashSet<String>()
        val desktopKeys = HashSet<String>()

        for (s in desktopSongs) {
            desktopNames.add(s.filename.lowercase(Locale.US))
            desktopKeys.add(normalizeKey(s.title, s.artist))
        }

        val toPull = desktopSongs.filter { s ->
            val nameMatch = localNames.contains(s.filename.lowercase(Locale.US))
            val keyMatch = localKeys.contains(normalizeKey(s.title, s.artist))
            !nameMatch && !keyMatch
        }

        val toPush = localSongs.filter { s ->
            val nameMatch = desktopNames.contains(s.filename.lowercase(Locale.US))
            val keyMatch = desktopKeys.contains(normalizeKey(s.title, s.artist))
            !nameMatch && !keyMatch
        }

        val synced = localSongs.filter { s ->
            localNames.contains(s.filename.lowercase(Locale.US)) ||
            localKeys.contains(normalizeKey(s.title, s.artist))
        }

        return SyncDiff(songsToPull = toPull, songsToPush = toPush, alreadySynced = synced)
    }

    suspend fun compareWithDesktop(
        desktopIp: String,
        desktopPort: Int
    ): SyncDiff = withContext(Dispatchers.IO) {
        val localSongs = MediaStoreHelper.getAllDeviceSongs(context)
        val desktopSongs = apiClient.fetchSongs(desktopIp, desktopPort)
        calculateDiff(localSongs, desktopSongs)
    }

    private fun normalizeKey(title: String, artist: String): String {
        val t = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        val a = artist.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        return "$t::$a"
    }

    suspend fun pullSongs(
        desktopIp: String,
        desktopPort: Int,
        songsToPull: List<Song>,
        onProgress: (current: Int, total: Int, song: Song, percent: Int, message: String) -> Unit,
        onLog: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val destDir = prefs.musicStorageDirectory
        var successCount = 0
        val downloadedPaths = mutableListOf<String>()

        onLog("[SYNC-PULL] Starting pull of ${songsToPull.size} missing track(s) from Desktop...")

        val quality = prefs.downloadQuality

        for ((index, song) in songsToPull.withIndex()) {
            val trackNum = index + 1
            val total = songsToPull.size
            val destFile = File(destDir, song.filename)

            onProgress(trackNum, total, song, ((trackNum - 1) * 100) / total, "Downloading: ${song.filename}")
            onLog("[SYNC-PULL] ($trackNum/$total) Downloading: ${song.filename} (${song.sizeFormatted})")

            try {
                val success = apiClient.downloadSong(desktopIp, desktopPort, song.filepath, destFile, bitrate = quality) { bytesRead, totalBytes ->
                    val filePct = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
                    val overallPct = (((trackNum - 1) * 100) + filePct) / total
                    onProgress(trackNum, total, song, overallPct, "Downloading: ${song.filename} ($filePct%)")
                }

                if (success && destFile.exists()) {
                    downloadedPaths.add(destFile.absolutePath)
                    successCount++
                    onLog("[SYNC-PULL] ($trackNum/$total) OK: Saved '${destFile.name}'")
                }
            } catch (e: Exception) {
                onLog("[ERROR] ($trackNum/$total) Failed to download '${song.filename}': ${e.message}")
            }
        }

        // Trigger media scanner on all newly downloaded files
        if (downloadedPaths.isNotEmpty()) {
            onLog("[SCANNER] Notifying Android MediaScanner for ${downloadedPaths.size} new track(s)...")
            MediaScannerHelper.scanFiles(context, downloadedPaths)
        }

        prefs.lastSyncTimestamp = System.currentTimeMillis()
        onLog("[SYNC-PULL] Complete: Successfully pulled $successCount of ${songsToPull.size} track(s).")
        successCount
    }

    suspend fun pushSongs(
        desktopIp: String,
        desktopPort: Int,
        songsToPush: List<Song>,
        onProgress: (current: Int, total: Int, song: Song, percent: Int, message: String) -> Unit,
        onLog: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        onLog("[SYNC-PUSH] Starting push of ${songsToPush.size} local track(s) to Desktop...")

        for ((index, song) in songsToPush.withIndex()) {
            val trackNum = index + 1
            val total = songsToPush.size
            val localFile = File(song.filepath)

            if (!localFile.exists()) {
                onLog("[WARN] ($trackNum/$total) Local file not found: ${song.filepath}")
                continue
            }

            val overallPct = ((trackNum - 1) * 100) / total
            onProgress(trackNum, total, song, overallPct, "Uploading: ${localFile.name}")
            onLog("[SYNC-PUSH] ($trackNum/$total) Uploading: ${localFile.name} (${song.sizeFormatted})")

            try {
                val res = apiClient.uploadSong(desktopIp, desktopPort, localFile)
                if (res.optString("status") == "success") {
                    successCount++
                    onLog("[SYNC-PUSH] ($trackNum/$total) OK: Uploaded '${localFile.name}'")
                } else {
                    onLog("[WARN] ($trackNum/$total) Response: ${res.optString("message")}")
                }
            } catch (e: Exception) {
                onLog("[ERROR] ($trackNum/$total) Failed to upload '${localFile.name}': ${e.message}")
            }
        }

        prefs.lastSyncTimestamp = System.currentTimeMillis()
        onLog("[SYNC-PUSH] Complete: Successfully pushed $successCount of ${songsToPush.size} track(s) to Desktop.")
        successCount
    }

    suspend fun pullSongs(
        desktopIp: String,
        desktopPort: Int,
        songsToPull: List<Song>,
        onProgress: (current: Int, total: Int, filename: String) -> Unit
    ): Int = pullSongs(
        desktopIp,
        desktopPort,
        songsToPull,
        onProgress = { current, total, song, _, _ -> onProgress(current, total, song.filename) },
        onLog = {}
    )

    suspend fun pushSongs(
        desktopIp: String,
        desktopPort: Int,
        songsToPush: List<Song>,
        onProgress: (current: Int, total: Int, filename: String) -> Unit
    ): Int = pushSongs(
        desktopIp,
        desktopPort,
        songsToPush,
        onProgress = { current, total, song, _, _ -> onProgress(current, total, song.filename) },
        onLog = {}
    )
}
