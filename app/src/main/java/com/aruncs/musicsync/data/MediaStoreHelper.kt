package com.aruncs.musicsync.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.aruncs.musicsync.model.Song
import java.io.File

object MediaStoreHelper {

    @Volatile
    private var cachedSongs: List<Song>? = null
    @Volatile
    private var lastCacheTime: Long = 0L
    private const val CACHE_TTL_MS = 30_000L // 30 seconds cache

    fun invalidateCache() {
        cachedSongs = null
        lastCacheTime = 0L
    }

    fun getDeviceSongCount(context: Context): Int {
        val cached = cachedSongs
        if (cached != null && (System.currentTimeMillis() - lastCacheTime < CACHE_TTL_MS)) {
            return cached.size
        }
        try {
            val projection = arrayOf(MediaStore.Audio.Media._ID)
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null
            )?.use { c ->
                return c.count
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cached?.size ?: 0
    }

    @Synchronized
    fun getAllDeviceSongs(context: Context, forceRefresh: Boolean = false): List<Song> {
        val cached = cachedSongs
        if (!forceRefresh && cached != null && (System.currentTimeMillis() - lastCacheTime < CACHE_TTL_MS)) {
            return cached
        }

        val songList = mutableListOf<Song>()
        val seenPaths = HashSet<String>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (c.moveToNext()) {
                    val path = c.getString(dataCol) ?: continue
                    if (seenPaths.contains(path)) continue

                    val file = File(path)
                    if (!file.exists() || !file.isFile) continue

                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol)
                    val artist = c.getString(artistCol)
                    val album = c.getString(albumCol)
                    val durationMs = c.getLong(durationCol)
                    val sizeBytes = c.getLong(sizeCol)

                    // Skip tiny sound effects (< 20KB or < 3 seconds)
                    if (sizeBytes < 20 * 1024 && durationMs < 3000) continue

                    val song = Song.fromFile(
                        id = id,
                        file = file,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs
                    )

                    songList.add(song)
                    seenPaths.add(path)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Direct directory scan fallback for /sdcard/Music in case MediaStore index is delayed
        try {
            val musicDir = AppPreferences(context).musicStorageDirectory
            scanDirectoryFallback(musicDir, seenPaths, songList)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sort descending by modification time
        songList.sortByDescending { it.mtime }
        cachedSongs = songList
        lastCacheTime = System.currentTimeMillis()
        return songList
    }

    private fun scanDirectoryFallback(dir: File, seenPaths: MutableSet<String>, songList: MutableList<Song>) {
        if (!dir.exists() || !dir.isDirectory) return

        val validExts = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")
        val files = dir.listFiles() ?: return

        for (f in files) {
            if (f.isDirectory) {
                scanDirectoryFallback(f, seenPaths, songList)
            } else if (f.isFile && validExts.contains(f.extension.lowercase())) {
                if (!seenPaths.contains(f.absolutePath)) {
                    val s = Song.fromFile(
                        id = System.currentTimeMillis() + songList.size,
                        file = f,
                        title = f.nameWithoutExtension,
                        artist = "Unknown",
                        album = dir.name,
                        durationMs = 0L
                    )
                    songList.add(s)
                    seenPaths.add(f.absolutePath)
                }
            }
        }
    }

    fun moveToTrash(context: Context, song: Song): Boolean {
        try {
            val srcFile = File(song.filepath)
            if (!srcFile.exists()) return false

            val trashDir = File(srcFile.parentFile, ".trash")
            if (!trashDir.exists()) trashDir.mkdirs()

            val destFile = File(trashDir, srcFile.name)
            val moved = srcFile.renameTo(destFile)

            try {
                val uri: Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                context.contentResolver.delete(uri, null, null)
            } catch (ignored: Exception) {}

            MediaScannerHelper.scanFile(context, song.filepath)
            invalidateCache()
            return moved
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun deleteSong(context: Context, song: Song): Boolean {
        var fileDeleted = false
        try {
            val file = File(song.filepath)
            if (file.exists()) {
                fileDeleted = file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val uri: Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        MediaScannerHelper.scanFile(context, song.filepath)
        invalidateCache()
        return fileDeleted
    }
}
