package com.aruncs.musicsync.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.model.Playlist

class PlaylistsAdapter(
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onPlayClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit,
    private val onSyncClick: ((Playlist) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistsAdapter.PlaylistViewHolder>() {

    private var playlists: List<Playlist> = emptyList()

    fun submitList(list: List<Playlist>) {
        this.playlists = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_playlist_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_playlist_count)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btn_playlist_play)
        private val btnSync: ImageButton = itemView.findViewById(R.id.btn_playlist_sync)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_playlist_delete)

        fun bind(playlist: Playlist) {
            tvName.text = playlist.name
            tvCount.text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "track" else "tracks"}"

            itemView.setOnClickListener {
                onPlaylistClick(playlist)
            }

            btnPlay.setOnClickListener {
                onPlayClick(playlist)
            }

            if (playlist.isRemote) {
                btnSync.visibility = View.VISIBLE
                btnSync.setOnClickListener {
                    onSyncClick?.invoke(playlist)
                }
                btnDelete.visibility = View.GONE
            } else {
                btnSync.visibility = View.GONE
                if (playlist.id == -1L) {
                    btnDelete.visibility = View.GONE
                } else {
                    btnDelete.visibility = View.VISIBLE
                    btnDelete.setOnClickListener {
                        onDeleteClick(playlist)
                    }
                }
            }
        }
    }
}
