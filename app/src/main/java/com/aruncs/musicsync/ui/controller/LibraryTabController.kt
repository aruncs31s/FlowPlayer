package com.aruncs.musicsync.ui.controller

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aruncs.musicsync.R
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.MediaScannerHelper
import com.aruncs.musicsync.data.MediaStoreHelper
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.PlayableItem
import com.aruncs.musicsync.ui.adapter.SongsAdapter
import com.aruncs.musicsync.ui.dialog.QualityDialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale

class LibraryTabController(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val prefs: AppPreferences,
    private val apiClient: DesktopApiClient,
    private val playlistManager: PlaylistManager,
    private val audioPlayer: AudioPlayer,
    private val getTargetPeerIp: () -> String,
    private val getTargetPeerPort: () -> Int,
    private val onShowAudioInfo: (Song) -> Unit,
    private val onAddToPlaylist: (Song) -> Unit,
    private val onLocalSongsUpdated: (List<Song>) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        const val MODE_LOCAL = 0
        const val MODE_REMOTE = 1
    }

    var libraryMode: Int = MODE_LOCAL
        private set

    var localSongs: List<Song> = emptyList()
        private set(value) {
            field = value
            localSongMap = value.associateBy { it.filename.lowercase(Locale.US) }
            onLocalSongsUpdated(value)
        }
    var localSongMap: Map<String, Song> = emptyMap()
        private set

    var remoteSongs: List<Song> = emptyList()
        private set

    var isRemotePlaybackTargetActive: () -> Boolean = { false }
    var onPlayOnRemoteRequested: ((Song) -> Unit)? = null

    lateinit var songsAdapter: SongsAdapter
        private set

    // Views
    private var rvLibrarySongs: RecyclerView? = null
    private var swipeRefreshLibrary: SwipeRefreshLayout? = null
    private var etLibrarySearch: EditText? = null
    private var tvLibraryEmpty: TextView? = null
    private var badgeLibraryCount: TextView? = null
    private var btnLibraryRefresh: ImageButton? = null
    private var btnTabLocal: TextView? = null
    private var btnTabRemote: TextView? = null
    private var btnLibraryQuality: TextView? = null
    private var layoutFilterChips: LinearLayout? = null
    private var chipFilterAll: TextView? = null
    private var chipFilterDownloaded: TextView? = null
    private var chipFilterNotDownloaded: TextView? = null
    private var chipFilterLiked: TextView? = null

    fun getStreamUrl(song: Song): String {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val cleanIp = ip.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val encodedPath = URLEncoder.encode(song.filepath, "UTF-8")
        val encodedName = URLEncoder.encode(song.filename, "UTF-8")
        return "http://$cleanIp:$port/api/song/stream?filepath=$encodedPath&filename=$encodedName"
    }

    fun createPlayableItem(song: Song, isRemote: Boolean): PlayableItem {
        if (!isRemote) {
            return PlayableItem(song, streamUrl = null)
        }
        val localMatch = localSongMap[song.filename.lowercase(Locale.US)]
        return if (localMatch != null) {
            PlayableItem(song.copy(filepath = localMatch.filepath), streamUrl = null)
        } else {
            PlayableItem(song, streamUrl = getStreamUrl(song))
        }
    }

    fun init(libraryView: View) {
        rvLibrarySongs = libraryView.findViewById(R.id.rv_library_songs)
        swipeRefreshLibrary = libraryView.findViewById(R.id.swipe_refresh_library)
        etLibrarySearch = libraryView.findViewById(R.id.et_library_search)
        tvLibraryEmpty = libraryView.findViewById(R.id.tv_library_empty)
        badgeLibraryCount = libraryView.findViewById(R.id.badge_library_count)
        btnLibraryRefresh = libraryView.findViewById(R.id.btn_library_refresh)
        btnTabLocal = libraryView.findViewById(R.id.btn_tab_local)
        btnTabRemote = libraryView.findViewById(R.id.btn_tab_remote)
        btnLibraryQuality = libraryView.findViewById(R.id.btn_library_quality)
        layoutFilterChips = libraryView.findViewById(R.id.layout_filter_chips)
        chipFilterAll = libraryView.findViewById(R.id.chip_filter_all)
        chipFilterDownloaded = libraryView.findViewById(R.id.chip_filter_downloaded)
        chipFilterNotDownloaded = libraryView.findViewById(R.id.chip_filter_not_downloaded)
        chipFilterLiked = libraryView.findViewById(R.id.chip_filter_liked)

        songsAdapter = SongsAdapter(
            onPlayClick = { song ->
                if (isRemotePlaybackTargetActive()) {
                    onPlayOnRemoteRequested?.invoke(song)
                } else if (audioPlayer.currentSong?.id == song.id) {
                    audioPlayer.togglePlayPause()
                } else {
                    val displayed = songsAdapter.getDisplayedSongs()
                    val startIndex = displayed.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                    val items = displayed.map { s ->
                        createPlayableItem(s, libraryMode == MODE_REMOTE)
                    }
                    audioPlayer.setQueue(items, startIndex)
                }
            },
            onDownloadClick = { song ->
                downloadRemoteSong(song)
            },
            onDeleteClick = { song ->
                confirmDeleteSong(song)
            },
            onAddToQueueClick = { song ->
                val item = createPlayableItem(song, libraryMode == MODE_REMOTE)
                audioPlayer.addToQueue(item.song, item.streamUrl)
                Toast.makeText(activity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                onLog("[QUEUE] Added '${song.title}' to playback queue")
            },
            onLikeClick = { song ->
                toggleSongLiked(song)
            },
            onMoreOptionsClick = { song ->
                showSongOptionsDialog(song)
            }
        )
        rvLibrarySongs?.layoutManager = LinearLayoutManager(activity)
        rvLibrarySongs?.adapter = songsAdapter

        btnTabLocal?.setOnClickListener { switchLibraryMode(MODE_LOCAL) }
        btnTabRemote?.setOnClickListener { switchLibraryMode(MODE_REMOTE) }

        btnLibraryQuality?.setOnClickListener {
            QualityDialogHelper.show(activity, prefs) { label, selected ->
                btnLibraryQuality?.text = label
                onLog("[SETTINGS] Download quality set to: $selected")
            }
        }

        chipFilterAll?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_ALL) }
        chipFilterDownloaded?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_DOWNLOADED) }
        chipFilterNotDownloaded?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_NOT_DOWNLOADED) }
        chipFilterLiked?.setOnClickListener { setFilterChip(SongsAdapter.FILTER_LIKED) }

        swipeRefreshLibrary?.setOnRefreshListener {
            if (libraryMode == MODE_LOCAL) {
                loadLocalLibrary()
            } else {
                loadRemoteLibrary()
            }
        }
        btnLibraryRefresh?.setOnClickListener {
            if (libraryMode == MODE_LOCAL) {
                loadLocalLibrary()
            } else {
                loadRemoteLibrary()
            }
        }

        etLibrarySearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                songsAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    fun switchLibraryMode(mode: Int) {
        libraryMode = mode
        val muted = ContextCompat.getColor(activity, R.color.text_muted)
        val black = ContextCompat.getColor(activity, R.color.black)

        setFilterChip(SongsAdapter.FILTER_ALL)

        if (mode == MODE_LOCAL) {
            btnTabLocal?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnTabLocal?.setTextColor(black)
            btnTabRemote?.background = null
            btnTabRemote?.setTextColor(muted)

            layoutFilterChips?.visibility = View.GONE
            btnLibraryQuality?.visibility = View.GONE

            val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
            songsAdapter.setRemoteMode(false, localNames)
            songsAdapter.submitList(localSongs)
            badgeLibraryCount?.text = "${localSongs.size} tracks"
            tvLibraryEmpty?.text = activity.getString(R.string.empty_library)
            tvLibraryEmpty?.visibility = if (localSongs.isEmpty()) View.VISIBLE else View.GONE
            etLibrarySearch?.text?.clear()
        } else {
            btnTabRemote?.setBackgroundResource(R.drawable.bg_button_yellow)
            btnTabRemote?.setTextColor(black)
            btnTabLocal?.background = null
            btnTabLocal?.setTextColor(muted)

            layoutFilterChips?.visibility = View.VISIBLE
            btnLibraryQuality?.visibility = View.VISIBLE

            val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
            songsAdapter.setRemoteMode(true, localNames)

            if (remoteSongs.isEmpty()) {
                loadRemoteLibrary()
            } else {
                songsAdapter.submitList(remoteSongs)
                badgeLibraryCount?.text = "${remoteSongs.size} tracks"
                tvLibraryEmpty?.text = "No songs found on Desktop"
                tvLibraryEmpty?.visibility = if (remoteSongs.isEmpty()) View.VISIBLE else View.GONE
            }
            etLibrarySearch?.text?.clear()
        }
    }

    fun loadLocalLibrary() {
        swipeRefreshLibrary?.isRefreshing = true
        scope.launch {
            val songs = withContext(Dispatchers.IO) {
                MediaStoreHelper.getAllDeviceSongs(activity)
            }
            localSongs = songs
            val localNames = songs.map { it.filename.lowercase(Locale.US) }.toSet()

            activity.runOnUiThread {
                if (libraryMode == MODE_LOCAL) {
                    songsAdapter.setRemoteMode(false, localNames)
                    songsAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                    songsAdapter.submitList(songs)
                    badgeLibraryCount?.text = "${songs.size} tracks"
                    tvLibraryEmpty?.text = activity.getString(R.string.empty_library)
                    tvLibraryEmpty?.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                }
                swipeRefreshLibrary?.isRefreshing = false
            }
        }
    }

    fun loadRemoteLibrary() {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()

        if (ip.isEmpty() || ip == "0.0.0.0") {
            Toast.makeText(activity, "Desktop IP is not configured", Toast.LENGTH_SHORT).show()
            tvLibraryEmpty?.text = "Desktop IP not configured. Set IP on Sync tab."
            tvLibraryEmpty?.visibility = View.VISIBLE
            return
        }

        swipeRefreshLibrary?.isRefreshing = true
        tvLibraryEmpty?.visibility = View.GONE
        onLog("[CLIENT] Fetching remote songs from Desktop ($ip:$port)...")

        scope.launch {
            try {
                val songs = apiClient.fetchSongs(ip, port)
                remoteSongs = songs
                val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()

                activity.runOnUiThread {
                    if (libraryMode == MODE_REMOTE) {
                        songsAdapter.setRemoteMode(true, localNames)
                        songsAdapter.setLikedSet(playlistManager.getLikedFilepaths())
                        songsAdapter.submitList(songs)
                        badgeLibraryCount?.text = "${songs.size} tracks"
                        tvLibraryEmpty?.text = "No songs found on Desktop"
                        tvLibraryEmpty?.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                    }
                    swipeRefreshLibrary?.isRefreshing = false
                    onLog("[OK] Loaded ${songs.size} remote track(s) from Desktop")
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    swipeRefreshLibrary?.isRefreshing = false
                    if (libraryMode == MODE_REMOTE) {
                        tvLibraryEmpty?.text = "Could not reach Desktop ($ip:$port).\n${e.message}\nMake sure Desktop app is running and tap Refresh."
                        tvLibraryEmpty?.visibility = View.VISIBLE
                    }
                    onLog("[ERROR] Failed to fetch remote songs: ${e.message}")
                    Toast.makeText(activity, "Failed to connect to Desktop: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun downloadRemoteSong(song: Song) {
        val ip = getTargetPeerIp()
        val port = getTargetPeerPort()
        val destDir = prefs.musicStorageDirectory
        val destFile = File(destDir, song.filename)
        val quality = prefs.downloadQuality

        Toast.makeText(activity, "Downloading ${song.title}...", Toast.LENGTH_SHORT).show()
        onLog("[DOWNLOAD] Starting download of '${song.filename}' from $ip:$port (quality: $quality)...")

        scope.launch {
            try {
                val ok = apiClient.downloadSong(ip, port, song.filepath, destFile, bitrate = quality, context = activity)
                if (ok) {
                    withContext(Dispatchers.IO) {
                        MediaScannerHelper.scanFile(activity, destFile.absolutePath)
                        localSongs = MediaStoreHelper.getAllDeviceSongs(activity)
                    }
                    activity.runOnUiThread {
                        val localNames = localSongs.map { it.filename.lowercase(Locale.US) }.toSet()
                        songsAdapter.setRemoteMode(true, localNames)
                        Toast.makeText(activity, "Downloaded ${song.title}", Toast.LENGTH_SHORT).show()
                        onLog("[DOWNLOAD] Successfully saved '${destFile.name}' to /Music")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    onLog("[ERROR] Failed to download '${song.filename}': ${e.message}")
                }
            }
        }
    }

    private fun setFilterChip(filter: Int) {
        songsAdapter.setDownloadFilter(filter)
        val black = ContextCompat.getColor(activity, R.color.black)
        val muted = ContextCompat.getColor(activity, R.color.text_muted)

        listOf(
            chipFilterAll to SongsAdapter.FILTER_ALL,
            chipFilterDownloaded to SongsAdapter.FILTER_DOWNLOADED,
            chipFilterNotDownloaded to SongsAdapter.FILTER_NOT_DOWNLOADED,
            chipFilterLiked to SongsAdapter.FILTER_LIKED
        ).forEach { (chip, chipFilter) ->
            if (chipFilter == filter) {
                chip?.setBackgroundResource(R.drawable.bg_button_yellow)
                chip?.setTextColor(black)
            } else {
                chip?.setBackgroundResource(R.drawable.bg_button_dark)
                chip?.setTextColor(muted)
            }
        }
    }

    fun toggleSongLiked(song: Song) {
        val nowLiked = playlistManager.toggleLike(song)
        val likedSet = playlistManager.getLikedFilepaths()
        songsAdapter.setLikedSet(likedSet)

        val msg = if (nowLiked) "Added to Liked Music" else "Removed from Liked Music"
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    fun confirmDeleteSong(song: Song) {
        if (libraryMode == MODE_REMOTE) {
            AlertDialog.Builder(activity)
                .setTitle("Delete Song from Desktop")
                .setMessage("Delete \"${song.title}\" from the Desktop permanently?")
                .setPositiveButton("Delete") { _, _ ->
                    val ip = getTargetPeerIp()
                    val port = getTargetPeerPort()
                    scope.launch {
                        try {
                            apiClient.deleteSong(ip, port, song.filepath)
                            activity.runOnUiThread {
                                remoteSongs = remoteSongs.filter { it.filepath != song.filepath }
                                songsAdapter.submitList(remoteSongs)
                                badgeLibraryCount?.text = "${remoteSongs.size} tracks"
                                Toast.makeText(activity, "\"${song.title}\" deleted from Desktop", Toast.LENGTH_SHORT).show()
                                onLog("[DELETE] Removed '${song.filename}' from Desktop")
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                                onLog("[ERROR] Failed to delete '${song.filename}': ${e.message}")
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(activity)
                .setTitle("Delete Song")
                .setMessage("Choose how to delete \"${song.title}\":")
                .setPositiveButton("Move to Trash") { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        val ok = MediaStoreHelper.moveToTrash(activity, song)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                localSongs = localSongs.filter { it.id != song.id }
                                songsAdapter.submitList(localSongs)
                                badgeLibraryCount?.text = "${localSongs.size} tracks"
                                Toast.makeText(activity, "\"${song.title}\" moved to .trash", Toast.LENGTH_SHORT).show()
                                onLog("[TRASH] Moved '${song.filename}' to .trash")
                            } else {
                                Toast.makeText(activity, "Failed to move song to trash", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNeutralButton("Delete Permanently") { _, _ ->
                    scope.launch(Dispatchers.IO) {
                        val ok = MediaStoreHelper.deleteSong(activity, song)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                localSongs = localSongs.filter { it.id != song.id }
                                songsAdapter.submitList(localSongs)
                                badgeLibraryCount?.text = "${localSongs.size} tracks"
                                Toast.makeText(activity, "\"${song.title}\" deleted permanently", Toast.LENGTH_SHORT).show()
                                onLog("[DELETE] Permanently removed '${song.filename}' from device")
                            } else {
                                Toast.makeText(activity, "Failed to delete song", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun showSongOptionsDialog(song: Song) {
        val options = arrayOf(
            "Song Info / Audio Tags",
            "Play on Remote Device",
            "Play Next",
            "Add to Queue",
            "Add to Playlist...",
            "Delete Song"
        )
        AlertDialog.Builder(activity)
            .setTitle(song.title)
            .setItems(options) { _, which ->
                val item = createPlayableItem(song, libraryMode == MODE_REMOTE)
                when (which) {
                    0 -> onShowAudioInfo(song)
                    1 -> onPlayOnRemoteRequested?.invoke(song)
                    2 -> {
                        audioPlayer.addToQueueNext(item.song, item.streamUrl)
                        Toast.makeText(activity, "Will play next: ${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        audioPlayer.addToQueue(item.song, item.streamUrl)
                        Toast.makeText(activity, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    4 -> onAddToPlaylist(song)
                    5 -> confirmDeleteSong(song)
                }
            }
            .show()
    }
}
