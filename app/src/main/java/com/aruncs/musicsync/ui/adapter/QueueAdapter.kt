package com.aruncs.musicsync.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.player.PlayableItem

class QueueAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var items: List<PlayableItem> = emptyList()
    private var currentIndex: Int = -1

    fun submitQueue(queue: List<PlayableItem>, activeIndex: Int) {
        this.items = queue
        this.currentIndex = activeIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_track, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIndex: TextView = itemView.findViewById(R.id.tv_queue_index)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_queue_title)
        private val tvArtist: TextView = itemView.findViewById(R.id.tv_queue_artist)
        private val ivNowPlaying: ImageView = itemView.findViewById(R.id.iv_queue_now_playing)

        fun bind(item: PlayableItem, position: Int) {
            val song = item.song
            val isActive = position == currentIndex

            tvIndex.text = "${position + 1}"
            tvTitle.text = song.title.ifBlank { song.filename }
            tvArtist.text = song.artist.ifBlank { "Unknown Artist" }

            if (isActive) {
                tvTitle.setTextColor(ContextCompat.getColor(itemView.context, R.color.yellow_primary))
                tvIndex.setTextColor(ContextCompat.getColor(itemView.context, R.color.yellow_primary))
                ivNowPlaying.visibility = View.VISIBLE
            } else {
                tvTitle.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
                tvIndex.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_muted))
                ivNowPlaying.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onItemClick(position)
            }
        }
    }
}
