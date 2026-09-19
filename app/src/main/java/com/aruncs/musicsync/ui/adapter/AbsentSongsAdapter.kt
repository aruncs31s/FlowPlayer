package com.aruncs.musicsync.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import com.aruncs.musicsync.data.AbsentSong
import com.aruncs.musicsync.model.Song
import java.util.Locale

/**
 * Adapter for displaying absent songs in the Poweramp import report dialog.
 * Each item includes a "Resolve" button that lazily searches localSongs for matches.
 *
 * @param localSongs  The current on-device song library to search against.
 * @param onUseMatch  Callback when the user picks a match: (absentSong, matchedSong) -> Unit
 */
class AbsentSongsAdapter(
    private val localSongs: List<Song>,
    private val onUseMatch: (absent: AbsentSong, match: Song) -> Unit
) : RecyclerView.Adapter<AbsentSongsAdapter.AbsentSongViewHolder>() {

    private var allItems: List<AbsentSong> = emptyList()
    private var displayedItems: List<AbsentSong> = emptyList()

    fun submitList(items: List<AbsentSong>) {
        allItems = items
        displayedItems = items
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.lowercase(Locale.US).trim()
        displayedItems = if (q.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.readableName.lowercase(Locale.US).contains(q) ||
                it.filename.lowercase(Locale.US).contains(q) ||
                it.originalPath.lowercase(Locale.US).contains(q) ||
                it.playlistName.lowercase(Locale.US).contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun getDisplayedCount(): Int = displayedItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbsentSongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_absent_song, parent, false)
        return AbsentSongViewHolder(view)
    }

    override fun onBindViewHolder(holder: AbsentSongViewHolder, position: Int) {
        holder.bind(displayedItems[position], localSongs, onUseMatch)
    }

    override fun getItemCount(): Int = displayedItems.size

    class AbsentSongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_absent_title)
        private val tvPlaylist: TextView = itemView.findViewById(R.id.tv_absent_playlist)
        private val tvPath: TextView = itemView.findViewById(R.id.tv_absent_path)
        private val btnResolve: TextView = itemView.findViewById(R.id.btn_absent_resolve)
        private val layoutResolvePanel: LinearLayout = itemView.findViewById(R.id.layout_resolve_panel)
        private val tvResolveStatus: TextView = itemView.findViewById(R.id.tv_resolve_status)
        private val containerMatches: LinearLayout = itemView.findViewById(R.id.container_resolve_matches)

        private var isPanelOpen = false

        fun bind(
            item: AbsentSong,
            localSongs: List<Song>,
            onUseMatch: (AbsentSong, Song) -> Unit
        ) {
            tvTitle.text = if (item.readableName.isNotBlank()) item.readableName else item.filename
            tvPlaylist.text = item.playlistName
            tvPath.text = item.originalPath.ifBlank { "—" }

            // Reset panel state on rebind
            isPanelOpen = false
            layoutResolvePanel.visibility = View.GONE
            btnResolve.text = "🔍 Resolve"

            btnResolve.setOnClickListener {
                if (isPanelOpen) {
                    // Collapse
                    layoutResolvePanel.visibility = View.GONE
                    btnResolve.text = "🔍 Resolve"
                    isPanelOpen = false
                } else {
                    // Expand and search
                    layoutResolvePanel.visibility = View.VISIBLE
                    btnResolve.text = "▲ Close"
                    isPanelOpen = true
                    doResolveSearch(item, localSongs, onUseMatch)
                }
            }
        }

        private fun doResolveSearch(
            item: AbsentSong,
            localSongs: List<Song>,
            onUseMatch: (AbsentSong, Song) -> Unit
        ) {
            tvResolveStatus.text = "Searching library..."
            containerMatches.removeAllViews()

            val query = item.readableName.ifBlank { item.filename }
            val matches = fuzzySearch(query, localSongs, maxResults = 8)

            if (matches.isEmpty()) {
                tvResolveStatus.text = "No matches found in local library."
                return
            }

            tvResolveStatus.text = "${matches.size} match${if (matches.size != 1) "es" else ""} found:"

            val inflater = LayoutInflater.from(itemView.context)
            matches.forEach { song ->
                val matchView = inflater.inflate(R.layout.item_resolve_match, containerMatches, false)

                val tvMatchTitle: TextView = matchView.findViewById(R.id.tv_match_title)
                val tvMatchArtist: TextView = matchView.findViewById(R.id.tv_match_artist)
                val btnUse: TextView = matchView.findViewById(R.id.btn_use_match)

                tvMatchTitle.text = song.title.ifBlank { song.filename }
                tvMatchArtist.text = song.artist.ifBlank { "Unknown Artist" }

                btnUse.setOnClickListener {
                    onUseMatch(item, song)
                    // Mark as resolved in UI
                    btnUse.text = "✓ Used"
                    btnUse.isEnabled = false
                    btnResolve.text = "✓ Resolved"
                    tvTitle.paintFlags = tvTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }

                containerMatches.addView(matchView)
            }
        }

        // ── Fuzzy matching ─────────────────────────────────────────────────────

        private fun fuzzySearch(query: String, songs: List<Song>, maxResults: Int = 8): List<Song> {
            if (query.isBlank() || songs.isEmpty()) return emptyList()

            val qLower = query.lowercase(Locale.US)
            val cleanQ = cleanString(query)
            val qWords = cleanQ.split(Regex("\\s+")).filter { it.isNotEmpty() }

            data class Scored(val song: Song, val score: Int)

            val scored = songs.mapNotNull { s ->
                val title = s.title.lowercase(Locale.US)
                val fn = s.filename.lowercase(Locale.US)
                val cleanTitle = cleanString(s.title)
                val cleanFn = cleanString(s.filename)
                var score = 0

                // Substring / prefix matches
                when {
                    title == qLower || fn == qLower -> score += 100
                    title.startsWith(qLower) || fn.startsWith(qLower) -> score += 60
                    title.contains(qLower) || fn.contains(qLower) -> score += 30
                }

                // Clean-string matches
                when {
                    cleanTitle == cleanQ -> score += 70
                    cleanTitle.startsWith(cleanQ) -> score += 35
                    cleanQ.isNotEmpty() && cleanTitle.contains(cleanQ) -> score += 20
                }
                when {
                    cleanFn == cleanQ -> score += 55
                    cleanFn.startsWith(cleanQ) -> score += 28
                    cleanQ.isNotEmpty() && cleanFn.contains(cleanQ) -> score += 15
                }

                // Word-level overlap
                if (qWords.isNotEmpty()) {
                    val allWords = (cleanTitle.split(Regex("\\s+")) +
                                   cleanFn.split(Regex("\\s+"))).filter { it.isNotEmpty() }.toSet()
                    val common = qWords.count { it in allWords }
                    score += (80 * common / qWords.size)
                }

                if (score > 0) Scored(s, score) else null
            }

            return scored.sortedByDescending { it.score }.take(maxResults).map { it.song }
        }

        private fun cleanString(text: String): String {
            var s = text.lowercase(Locale.US)
            // Strip quality markers inside brackets
            s = s.replace(Regex("""\s*[\(\[][^\)\]]*(?:128|192|256|320|flac|kbps|audio|video|lyrics|official|remaster|hd|hq)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
            // Strip audio extensions
            s = s.replace(Regex("""\.(mp3|flac|m4a|wav|ogg|opus|aac)$""", RegexOption.IGNORE_CASE), "")
            // Normalise punctuation
            s = s.replace(Regex("""[-_.()[\]'"~]+"""), " ")
            return s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trim()
        }
    }
}
