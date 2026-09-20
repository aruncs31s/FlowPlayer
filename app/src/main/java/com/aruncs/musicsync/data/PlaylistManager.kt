package com.aruncs.musicsync.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aruncs.musicsync.model.Playlist
import com.aruncs.musicsync.model.Song
import org.json.JSONArray
import java.io.File
import java.util.Locale

class PlaylistManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("music_sync_playlists", Context.MODE_PRIVATE)

    companion object {
        const val LIKED_MUSIC_NAME = "Liked Music"
        private const val KEY_LOCAL_PLAYLISTS = "local_playlists_json"
        private const val KEY_LIKED_TRACKS = "liked_tracks_set"
    }

    // ==================== LIKED SONGS SYSTEM ====================

    @Synchronized
    fun getLikedFilepaths(): Set<String> {
        return prefs.getStringSet(KEY_LIKED_TRACKS, emptySet()) ?: emptySet()
    }

    @Synchronized
    fun isLiked(song: Song): Boolean {
        val liked = getLikedFilepaths()
        val fn = song.filename.lowercase(Locale.US)
        return liked.contains(song.filepath) || liked.contains(fn)
    }

    @Synchronized
    fun toggleLike(song: Song): Boolean {
        val liked = getLikedFilepaths().toMutableSet()
        val fn = song.filename.lowercase(Locale.US)
        val isCurrentlyLiked = liked.contains(song.filepath) || liked.contains(fn)

        val nowLiked = if (isCurrentlyLiked) {
            liked.remove(song.filepath)
            liked.remove(fn)
            false
        } else {
            liked.add(song.filepath)
            liked.add(fn)
            true
        }

        prefs.edit().putStringSet(KEY_LIKED_TRACKS, liked).apply()

        // Also update or ensure "Liked Music" playlist reflects this
        syncLikedMusicPlaylist()
        return nowLiked
    }

    @Synchronized
    fun getLikedSongs(allSongs: List<Song>): List<Song> {
        val liked = getLikedFilepaths()
        if (liked.isEmpty()) return emptyList()

        return allSongs.filter { s ->
            liked.contains(s.filepath) || liked.contains(s.filename.lowercase(Locale.US))
        }
    }

    private fun syncLikedMusicPlaylist() {
        val liked = getLikedFilepaths()
        val list = getLocalPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.name.equals(LIKED_MUSIC_NAME, ignoreCase = true) }

        if (idx != -1) {
            val updated = list[idx].copy(
                trackFilepaths = liked.toList(),
                trackCount = liked.size / 2 // Accounts for both filepath and filename
            )
            list[idx] = updated
            savePlaylists(list)
        }
    }

    // ==================== PLAYLIST CRUD & SYNC ====================

    @Synchronized
    fun getLocalPlaylists(): List<Playlist> {
        val json = prefs.getString(KEY_LOCAL_PLAYLISTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Playlist.fromJSONObject(obj, isRemote = false))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getPlaylist(id: Long): Playlist? {
        return getLocalPlaylists().firstOrNull { it.id == id }
    }

    @Synchronized
    fun createPlaylist(name: String): Playlist {
        val list = getLocalPlaylists().toMutableList()
        val newPlaylist = Playlist(
            id = System.currentTimeMillis(),
            name = name.trim(),
            trackCount = 0,
            isRemote = false,
            trackFilepaths = emptyList()
        )
        list.add(0, newPlaylist)
        savePlaylists(list)
        return newPlaylist
    }

    @Synchronized
    fun syncRemotePlaylist(name: String, trackFilepaths: List<String>): Playlist {
        val list = getLocalPlaylists().toMutableList()
        val cleanName = name.trim()
        val idx = list.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }

        val result: Playlist
        if (idx != -1) {
            // Merge with existing playlist without duplicate filepaths
            val existing = list[idx]
            val merged = (existing.trackFilepaths + trackFilepaths).distinct()
            result = existing.copy(
                trackFilepaths = merged,
                trackCount = merged.size
            )
            list[idx] = result
        } else {
            // Create new local playlist
            result = Playlist(
                id = System.currentTimeMillis(),
                name = cleanName,
                trackCount = trackFilepaths.size,
                isRemote = false,
                trackFilepaths = trackFilepaths
            )
            list.add(0, result)
        }

        savePlaylists(list)
        return result
    }

    @Synchronized
    fun deletePlaylist(id: Long): Boolean {
        val list = getLocalPlaylists().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) {
            savePlaylists(list)
        }
        return removed
    }

    @Synchronized
    fun addTrack(playlistId: Long, filepath: String): Boolean {
        val list = getLocalPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx == -1) return false

        val current = list[idx]
        if (current.trackFilepaths.contains(filepath)) {
            return false // Already exists in playlist
        }

        val updatedTracks = current.trackFilepaths + filepath
        val updated = current.copy(
            trackFilepaths = updatedTracks,
            trackCount = updatedTracks.size
        )
        list[idx] = updated
        savePlaylists(list)
        return true
    }

    @Synchronized
    fun removeTrack(playlistId: Long, filepath: String): Boolean {
        val list = getLocalPlaylists().toMutableList()
        val idx = list.indexOfFirst { it.id == playlistId }
        if (idx == -1) return false

        val current = list[idx]
        val updatedTracks = current.trackFilepaths.filter { it != filepath }
        val updated = current.copy(
            trackFilepaths = updatedTracks,
            trackCount = updatedTracks.size
        )
        list[idx] = updated
        savePlaylists(list)
        return true
    }

    fun getPlaylistSongs(playlist: Playlist, allSongs: List<Song>): List<Song> {
        val songMap = allSongs.associateBy { it.filepath }
        val songFilenameMap = allSongs.associateBy { it.filename.lowercase(Locale.US) }

        return playlist.trackFilepaths.mapNotNull { path ->
            songMap[path] ?: songFilenameMap[File(path).name.lowercase(Locale.US)]
        }
    }

    fun exportM3u8Playlist(playlist: Playlist, baseDir: File? = null): File? {
        return try {
            val appPrefs = AppPreferences(context)
            val musicDir = baseDir ?: appPrefs.musicStorageDirectory
            val playlistDir = File(musicDir, "Playlists")
            try {
                if (!playlistDir.exists()) playlistDir.mkdirs()
            } catch (ignored: Throwable) {}

            val safeName = playlist.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val m3uFile = File(playlistDir, "$safeName.m3u8")

            val allSongs = MediaStoreHelper.getAllDeviceSongs(context)
            val playlistSongs = getPlaylistSongs(playlist, allSongs)

            val content = buildString {
                append("#EXTM3U\n")
                append("#PLAYLIST:${playlist.name}\n")
                for (s in playlistSongs) {
                    val durationSec = s.durationSec.toInt()
                    append("#EXTINF:$durationSec,${s.artist} - ${s.title}\n")
                    if (s.filepath.startsWith(musicDir.absolutePath)) {
                        append(s.filepath.removePrefix(musicDir.absolutePath).removePrefix("/"))
                    } else {
                        append(s.filepath)
                    }
                    append("\n")
                }
            }

            val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            if (hasAllFilesAccess) {
                m3uFile.bufferedWriter(Charsets.UTF_8).use { out ->
                    out.write(content)
                }
                MediaScannerHelper.scanFile(context, m3uFile.absolutePath)
                m3uFile
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.m3u8")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Playlists/")
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                } else {
                    m3uFile.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
                }
                MediaScannerHelper.scanFile(context, m3uFile.absolutePath)
                m3uFile
            } else {
                m3uFile.bufferedWriter(Charsets.UTF_8).use { out ->
                    out.write(content)
                }
                MediaScannerHelper.scanFile(context, m3uFile.absolutePath)
                m3uFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun savePlaylists(list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJSONObject()) }
        prefs.edit().putString(KEY_LOCAL_PLAYLISTS, arr.toString()).apply()
    }
}
