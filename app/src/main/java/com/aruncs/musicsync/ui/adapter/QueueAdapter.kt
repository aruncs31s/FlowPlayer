package com.aruncs.musicsync.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.player.PlayableItem

class QueueAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var items: List<PlayableItem> = emptyList()
    private var startIndexOffset: Int = 0
    private var currentIndex: Int = -1

    private var colorYellow: Int = 0
    private var colorTextPrimary: Int = 0
    private var colorTextMuted: Int = 0

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val ctx = recyclerView.context
        colorYellow = ContextCompat.getColor(ctx, R.color.yellow_primary)
        colorTextPrimary = ContextCompat.getColor(ctx, R.color.text_primary)
        colorTextMuted = ContextCompat.getColor(ctx, R.color.text_muted)
    }

    fun submitQueue(queue: List<PlayableItem>, activeIndex: Int) {
        val start = activeIndex.coerceAtLeast(0)
        val end = minOf(queue.size, start + 50)
        val newItems = if (queue.isNotEmpty() && start < queue.size) queue.subList(start, end) else emptyList()

        val oldItems = this.items
        this.items = newItems
        this.startIndexOffset = start
        this.currentIndex = activeIndex

        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].song.id == newItems[newItemPosition].song.id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].song == newItems[newItemPosition].song
        }).dispatchUpdatesTo(this)
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

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in items.indices) {
                    onItemClick(startIndexOffset + pos)
                }
            }
        }

        fun bind(item: PlayableItem, position: Int) {
            val actualIndex = startIndexOffset + position
            val song = item.song
            val isActive = actualIndex == currentIndex

            tvIndex.text = "${actualIndex + 1}"
            tvTitle.text = song.title.ifBlank { song.filename }
            tvArtist.text = song.artist.ifBlank { "Unknown Artist" }

            if (isActive) {
                tvTitle.setTextColor(colorYellow)
                tvIndex.setTextColor(colorYellow)
                ivNowPlaying.visibility = View.VISIBLE
            } else {
                tvTitle.setTextColor(colorTextPrimary)
                tvIndex.setTextColor(colorTextMuted)
                ivNowPlaying.visibility = View.GONE
            }
        }
    }
}