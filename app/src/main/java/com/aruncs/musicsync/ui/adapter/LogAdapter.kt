package com.aruncs.musicsync.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aruncs.musicsync.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val time: String,
    val tag: String,
    val message: String
)

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val logs = mutableListOf<LogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addLog(raw: String) {
        val now = timeFormat.format(Date())
        val tag: String
        val msg: String

        if (raw.startsWith("[")) {
            val endBracket = raw.indexOf("]")
            if (endBracket != -1) {
                tag = raw.substring(0, endBracket + 1)
                msg = raw.substring(endBracket + 1).trim()
            } else {
                tag = "[LOG]"
                msg = raw
            }
        } else {
            tag = "[LOG]"
            msg = raw
        }

        try {
            var insertedIndex = -1
            synchronized(logs) {
                logs.add(LogEntry(now, tag, msg))
                if (logs.size > 1000) {
                    logs.removeAt(0)
                }
                insertedIndex = logs.size - 1
            }
            if (insertedIndex >= 0) {
                try {
                    notifyDataSetChanged()
                } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}
    }

    fun clear() {
        try {
            synchronized(logs) {
                logs.clear()
            }
            notifyDataSetChanged()
        } catch (ignored: Throwable) {}
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tv_log_time)
        private val tvTag: TextView = itemView.findViewById(R.id.tv_log_tag)
        private val tvMsg: TextView = itemView.findViewById(R.id.tv_log_message)

        fun bind(entry: LogEntry) {
            tvTime.text = entry.time
            tvTag.text = entry.tag
            tvMsg.text = entry.message

            val tagUpper = entry.tag.uppercase(Locale.US)
            val tagColor = when {
                tagUpper.contains("ERROR") || tagUpper.contains("FAIL") -> ContextCompat.getColor(itemView.context, R.color.status_offline)
                tagUpper.contains("OK") || tagUpper.contains("DONE") || tagUpper.contains("SUCCESS") -> ContextCompat.getColor(itemView.context, R.color.status_online)
                tagUpper.contains("WARN") -> Color.parseColor("#F59E0B")
                else -> ContextCompat.getColor(itemView.context, R.color.yellow_primary)
            }
            tvTag.setTextColor(tagColor)
        }
    }
}
