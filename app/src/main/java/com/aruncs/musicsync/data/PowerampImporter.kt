package com.aruncs.musicsync.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.aruncs.musicsync.model.Song
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

data class AbsentSong(
    val playlistName: String,
    val filename: String,
    val readableName: String,
    val originalPath: String
)

data class PowerampImportReport(
    val totalPlaylists: Int,
    val totalTracks: Int,
    val matchedCount: Int,
    val absentCount: Int,
    val likedCount: Int,
    val absentSongs: List<AbsentSong>
)

class PowerampImporter(
    private val context: Context,
    private val playlistManager: PlaylistManager
) {

    companion object {
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

        fun cleanStringForMatching(text: String?): String {
            if (text.isNullOrBlank()) return ""
            var s = text.lowercase(Locale.US)
            s = s.replace(
                Regex(
                    """\s*[\(\[][^\)\]]*(?:128|192|256|320|flac|kbps|audio|video|lyrics|official|remaster|hd|hq)[^\)\]]*[\)\]]""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            s = s.replace(Regex("""\.(?:mp3|flac|m4a|wav|ogg|opus|aac)$""", RegexOption.IGNORE_CASE), "")
            s = s.replace(Regex("""[\-_.\(\)\[\]'"~]+"""), " ")
            return s.split(Regex("""\s+""")).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    /**
     * Extracts or copies the SQLite database file from a content Uri (direct file or ZIP archive).
     */
    private fun resolveDatabaseFile(uri: Uri): File {
        val tempDbFile = File(context.cacheDir, "poweramp_${System.currentTimeMillis()}.db")

        context.contentResolver.openInputStream(uri).use { rawStream ->
            if (rawStream == null) throw IllegalArgumentException("Cannot open stream for Uri: $uri")

            // Read the first 16 bytes to detect ZIP or direct SQLite
            val header = ByteArray(16)
            val bytesRead = rawStream.read(header)
            if (bytesRead < 4) {
                throw IllegalArgumentException("File is too small to be a valid database or archive")
            }

            val isZip = header[0] == ZIP_MAGIC[0] &&
                    header[1] == ZIP_MAGIC[1] &&
                    header[2] == ZIP_MAGIC[2] &&
                    header[3] == ZIP_MAGIC[3]

            if (isZip) {
                // Reopen the input stream to read ZIP from start
                context.contentResolver.openInputStream(uri).use { zipStream ->
                    if (zipStream == null) throw IllegalArgumentException("Failed to reopen zip stream")
                    val zis = ZipInputStream(zipStream)
                    var foundEntry = false
                    var entry = zis.nextEntry

                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && (name == "lists-export" || name.endsWith("/lists-export") || name.contains("lists-export"))) {
                            FileOutputStream(tempDbFile).use { out ->
                                zis.copyTo(out)
                            }
                            foundEntry = true
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    if (!foundEntry) {
                        throw IllegalArgumentException("ZIP archive does not contain a Poweramp 'lists-export' database")
                    }
                }
            } else {
                // Copy header + remaining stream to file
                FileOutputStream(tempDbFile).use { out ->
                    out.write(header, 0, bytesRead)
                    rawStream.copyTo(out)
                }
            }
        }

        return tempDbFile
    }

    /**
     * Imports playlists & ratings from the specified Poweramp backup / database Uri,
     * matches tracks against local library songs, updates PlaylistManager, and returns the report.
     */
    fun importFromUri(uri: Uri, localSongs: List<Song>): PowerampImportReport {
        val dbFile = resolveDatabaseFile(uri)
        var db: SQLiteDatabase? = null

        try {
            db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

            // Index local songs for fast multi-tier matching
            val byFilepath = mutableMapOf<String, Song>()
            val byFilename = mutableMapOf<String, Song>()
            val byNameNoExt = mutableMapOf<String, Song>()
            val byCleanName = mutableMapOf<String, Song>()

            for (song in localSongs) {
                val fp = song.filepath.lowercase(Locale.US)
                val fn = song.filename.lowercase(Locale.US)
                val noExt = File(fn).nameWithoutExtension

                byFilepath[fp] = song
                byFilename[fn] = song
                if (!byNameNoExt.containsKey(noExt)) {
                    byNameNoExt[noExt] = song
                }

                val cleanFn = cleanStringForMatching(fn)
                if (cleanFn.isNotBlank() && !byCleanName.containsKey(cleanFn)) {
                    byCleanName[cleanFn] = song
                }

                val cleanTitle = cleanStringForMatching(song.title)
                if (cleanTitle.isNotBlank() && !byCleanName.containsKey(cleanTitle)) {
                    byCleanName[cleanTitle] = song
                }
            }

            fun matchSong(trackPath: String, readableName: String?): Song? {
                val pathLower = trackPath.lowercase(Locale.US)
                val fn = File(trackPath).name.lowercase(Locale.US)
                val fnNoExt = File(fn).nameWithoutExtension

                // Tier 1: exact path
                byFilepath[pathLower]?.let { return it }

                // Tier 2: exact filename
                byFilename[fn]?.let { return it }

                // Tier 3: filename without extension
                byNameNoExt[fnNoExt]?.let { return it }

                // Tier 4: clean filename
                val cleanFn = cleanStringForMatching(fn)
                if (cleanFn.isNotBlank()) {
                    byCleanName[cleanFn]?.let { return it }
                }

                // Tier 5: clean readable name
                val cleanRn = cleanStringForMatching(readableName)
                if (cleanRn.isNotBlank()) {
                    byCleanName[cleanRn]?.let { return it }
                }

                return null
            }

            var totalPlaylists = 0
            var totalTracks = 0
            var totalMatched = 0
            var totalAbsent = 0
            val absentSongs = mutableListOf<AbsentSong>()

            // 1. Process Playlists
            val cursorPl = db.rawQuery("SELECT _id, name FROM playlists ORDER BY _id ASC", null)
            cursorPl.use { plCursor ->
                totalPlaylists = plCursor.count
                val idCol = plCursor.getColumnIndexOrThrow("_id")
                val nameCol = plCursor.getColumnIndexOrThrow("name")

                while (plCursor.moveToNext()) {
                    val plId = plCursor.getLong(idCol)
                    val plName = plCursor.getString(nameCol)?.trim() ?: "Untitled"

                    val matchedFilepaths = mutableListOf<String>()

                    val cursorTracks = db.rawQuery(
                        "SELECT _id, path, readable_name FROM tracks WHERE playlist_id = ? ORDER BY _id ASC",
                        arrayOf(plId.toString())
                    )

                    cursorTracks.use { tCursor ->
                        totalTracks += tCursor.count
                        val pathCol = tCursor.getColumnIndexOrThrow("path")
                        val rnameCol = tCursor.getColumnIndexOrThrow("readable_name")

                        while (tCursor.moveToNext()) {
                            val trackPath = tCursor.getString(pathCol) ?: ""
                            val rname = tCursor.getString(rnameCol) ?: File(trackPath).name

                            val matched = matchSong(trackPath, rname)
                            if (matched != null) {
                                matchedFilepaths.add(matched.filepath)
                                totalMatched++
                            } else {
                                totalAbsent++
                                absentSongs.add(
                                    AbsentSong(
                                        playlistName = plName,
                                        filename = File(trackPath).name,
                                        readableName = rname,
                                        originalPath = trackPath
                                    )
                                )
                            }
                        }
                    }

                    if (matchedFilepaths.isNotEmpty()) {
                        playlistManager.syncRemotePlaylist(plName, matchedFilepaths)
                    }
                }
            }

            // 2. Process Liked / Rated Songs (rating > 0)
            var likedCount = 0
            val likedMatchedFilepaths = mutableListOf<String>()
            val cursorRated = db.rawQuery("SELECT _id, path, readable_name FROM tracks WHERE rating > 0 ORDER BY _id ASC", null)
            cursorRated.use { rCursor ->
                val pathCol = rCursor.getColumnIndexOrThrow("path")
                val rnameCol = rCursor.getColumnIndexOrThrow("readable_name")

                while (rCursor.moveToNext()) {
                    val trackPath = rCursor.getString(pathCol) ?: ""
                    val rname = rCursor.getString(rnameCol) ?: File(trackPath).name

                    val matched = matchSong(trackPath, rname)
                    if (matched != null) {
                        likedMatchedFilepaths.add(matched.filepath)
                        likedCount++
                        // Ensure song is also marked liked in preferences
                        if (!playlistManager.isLiked(matched)) {
                            playlistManager.toggleLike(matched)
                        }
                    }
                }
            }

            if (likedMatchedFilepaths.isNotEmpty()) {
                playlistManager.syncRemotePlaylist(PlaylistManager.LIKED_MUSIC_NAME, likedMatchedFilepaths)
            }

            return PowerampImportReport(
                totalPlaylists = totalPlaylists,
                totalTracks = totalTracks,
                matchedCount = totalMatched,
                absentCount = totalAbsent,
                likedCount = likedCount,
                absentSongs = absentSongs
            )

        } finally {
            try {
                db?.close()
            } catch (_: Exception) {}
            if (dbFile.exists()) {
                dbFile.delete()
            }
        }
    }
}
