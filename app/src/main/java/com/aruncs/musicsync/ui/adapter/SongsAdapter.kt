package com.aruncs.musicsync.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.model.Song
import java.util.Locale

class SongsAdapter(
    private val onPlayClick: (Song) -> Unit,
    private val onDownloadClick: ((Song) -> Unit)? = null,
    private val onDeleteClick: ((Song) -> Unit)? = null,
    private val onAddToQueueClick: ((Song) -> Unit)? = null,
    private val onLikeClick: ((Song) -> Unit)? = null,
    private val onMoreOptionsClick: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<SongsAdapter.SongViewHolder>() {

    private var allSongs: List<Song> = emptyList()
    private var displayedSongs: List<Song> = emptyList()
    private var activeSong: Song? = null
    private var isPlaying: Boolean = false
    var isRemoteMode: Boolean = false
        private set
    private var downloadedFilenames: Set<String> = emptySet()
    private var likedFilepaths: Set<String> = emptySet()
    var downloadFilter: Int = FILTER_ALL
        private set
    private var currentSearchQuery: String = ""

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_DOWNLOADED = 1
        const val FILTER_NOT_DOWNLOADED = 2
        const val FILTER_LIKED = 3
    }

    fun submitList(songs: List<Song>) {
        this.allSongs = songs
        applyFilter()
    }

    fun getDisplayedSongs(): List<Song> = displayedSongs

    fun setRemoteMode(remote: Boolean, localFilenames: Set<String> = emptySet()) {
        this.isRemoteMode = remote
        this.downloadedFilenames = localFilenames
        applyFilter()
    }

    fun setLikedSet(liked: Set<String>) {
        this.likedFilepaths = liked
        notifyDataSetChanged()
    }

    fun setDownloadFilter(filter: Int) {
        this.downloadFilter = filter
        applyFilter()
    }

    fun filter(query: String) {
        this.currentSearchQuery = query
        applyFilter()
    }

    fun setActiveSong(song: Song?, isPlaying: Boolean) {
        this.activeSong = song
        this.isPlaying = isPlaying
        notifyDataSetChanged()
    }

    private fun applyFilter() {
        var filtered = allSongs

        // 1. Search Query
        if (currentSearchQuery.isNotBlank()) {
            val q = currentSearchQuery.lowercase(Locale.US)
            filtered = filtered.filter { song ->
                song.title.lowercase(Locale.US).contains(q) ||
                song.artist.lowercase(Locale.US).contains(q) ||
                song.album.lowercase(Locale.US).contains(q) ||
                song.filename.lowercase(Locale.US).contains(q)
            }
        }

        // 2. Download / Liked Status Filter
        when (downloadFilter) {
            FILTER_DOWNLOADED -> {
                if (isRemoteMode) {
                    filtered = filtered.filter { downloadedFilenames.contains(it.filename.lowercase(Locale.US)) }
                }
            }
            FILTER_NOT_DOWNLOADED -> {
                if (isRemoteMode) {
                    filtered = filtered.filter { !downloadedFilenames.contains(it.filename.lowercase(Locale.US)) }
                }
            }
            FILTER_LIKED -> {
                filtered = filtered.filter {
                    likedFilepaths.contains(it.filepath) || likedFilepaths.contains(it.filename.lowercase(Locale.US))
                }
            }
        }

        this.displayedSongs = filtered
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(displayedSongs[position])
    }

    override fun getItemCount(): Int = displayedSongs.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_song_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_song_subtitle)
        private val tvDuration: TextView = itemView.findViewById(R.id.tv_song_duration)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_song_meta)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btn_song_play)
        private val ivDownloadedBadge: ImageView = itemView.findViewById(R.id.iv_downloaded_badge)
        private val btnDownload: ImageButton = itemView.findViewById(R.id.btn_song_download)
        private val btnLike: ImageButton = itemView.findViewById(R.id.btn_song_like)
        private val btnQueue: ImageButton = itemView.findViewById(R.id.btn_song_queue)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_song_delete)

        fun bind(song: Song) {
            tvTitle.text = song.title.ifBlank { song.filename }
            tvSubtitle.text = "${song.artist.ifBlank { "Unknown Artist" }} • ${song.album.ifBlank { "Unknown Album" }}"
            tvDuration.text = song.durationFormatted
            tvMeta.text = song.sizeFormatted

            val isActive = activeSong?.id == song.id || (activeSong?.filename == song.filename && activeSong?.title == song.title)
            if (isActive) {
                tvTitle.setTextColor(ContextCompat.getColor(itemView.context, R.color.yellow_primary))
                btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                btnPlay.setBackgroundResource(R.drawable.bg_button_yellow)
                btnPlay.setColorFilter(ContextCompat.getColor(itemView.context, R.color.black))
            } else {
                tvTitle.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                btnPlay.setImageResource(R.drawable.ic_play)
                btnPlay.setBackgroundResource(R.drawable.bg_button_dark)
                btnPlay.setColorFilter(ContextCompat.getColor(itemView.context, R.color.text_primary))
            }

            // Liked State
            val isLiked = likedFilepaths.contains(song.filepath) || likedFilepaths.contains(song.filename.lowercase(Locale.US))
            if (isLiked) {
                btnLike.setImageResource(R.drawable.ic_favorite)
                btnLike.setColorFilter(Color.parseColor("#FFFF0055"))
            } else {
                btnLike.setImageResource(R.drawable.ic_favorite_border)
                btnLike.setColorFilter(ContextCompat.getColor(itemView.context, R.color.text_muted))
            }
            btnLike.setOnClickListener {
                onLikeClick?.invoke(song)
            }

            if (isRemoteMode) {
                val alreadyDownloaded = downloadedFilenames.contains(song.filename.lowercase(Locale.US))
                if (alreadyDownloaded) {
                    ivDownloadedBadge.visibility = View.VISIBLE
                    btnDownload.visibility = View.GONE
                } else {
                    ivDownloadedBadge.visibility = View.GONE
                    btnDownload.visibility = View.VISIBLE
                    btnDownload.isEnabled = true
                    btnDownload.alpha = 1.0f
                    btnDownload.setColorFilter(ContextCompat.getColor(itemView.context, R.color.yellow_primary))
                    btnDownload.setOnClickListener {
                        onDownloadClick?.invoke(song)
                    }
                }
            } else {
                ivDownloadedBadge.visibility = View.GONE
                btnDownload.visibility = View.GONE
            }

            btnQueue.visibility = View.VISIBLE
            btnQueue.setOnClickListener {
                onAddToQueueClick?.invoke(song)
            }
            btnQueue.setOnLongClickListener {
                onMoreOptionsClick?.invoke(song)
                true
            }

            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                onDeleteClick?.invoke(song)
            }

            val clickListener = View.OnClickListener {
                onPlayClick(song)
            }
            btnPlay.setOnClickListener(clickListener)
            itemView.setOnClickListener(clickListener)
            itemView.setOnLongClickListener {
                onMoreOptionsClick?.invoke(song)
                true
            }
        }
    }
}
