package com.aruncs.musicsync.ui.dialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aruncs.musicsync.R
import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioInfoHelper

object AudioInfoDialogHelper {

    fun show(context: Context, song: Song, isRemote: Boolean = false, streamUrl: String? = null) {
        val effectiveStreamUrl = if (isRemote) streamUrl else null
        val details = AudioInfoHelper.extract(song, effectiveStreamUrl)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_audio_info, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_info_title)
        val tvArtist = dialogView.findViewById<TextView>(R.id.tv_info_artist)
        val tvAlbumArtist = dialogView.findViewById<TextView>(R.id.tv_info_album_artist)
        val tvAlbum = dialogView.findViewById<TextView>(R.id.tv_info_album)
        val tvTrackDisc = dialogView.findViewById<TextView>(R.id.tv_info_track_disc)
        val tvYearGenre = dialogView.findViewById<TextView>(R.id.tv_info_year_genre)

        val tvFormat = dialogView.findViewById<TextView>(R.id.tv_info_format)
        val tvBitrate = dialogView.findViewById<TextView>(R.id.tv_info_bitrate)
        val tvSampleBits = dialogView.findViewById<TextView>(R.id.tv_info_sample_bits)
        val tvChannels = dialogView.findViewById<TextView>(R.id.tv_info_channels)
        val tvSize = dialogView.findViewById<TextView>(R.id.tv_info_size)

        val tvSourceBadge = dialogView.findViewById<TextView>(R.id.tv_info_source_badge)
        val tvFilePath = dialogView.findViewById<TextView>(R.id.tv_info_file_path)
        val btnCopyPath = dialogView.findViewById<TextView>(R.id.btn_copy_file_path)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btn_audio_info_close)
        val btnDone = dialogView.findViewById<TextView>(R.id.btn_audio_info_done)

        tvTitle.text = details.title
        tvArtist.text = details.artist
        tvAlbumArtist.text = details.albumArtist
        tvAlbum.text = details.album
        tvTrackDisc.text = "Track ${details.trackNumber} / Disc ${details.discNumber}"
        tvYearGenre.text = "${details.year} • ${details.genre}"

        tvFormat.text = details.formatLabel
        tvBitrate.text = details.bitrateKbps
        tvSampleBits.text = "${details.sampleRateHz} • ${details.bitDepth}"
        tvChannels.text = "${details.channels} • ${details.durationFormatted}"
        tvSize.text = details.sizeFormatted

        if (details.isRemote) {
            tvSourceBadge.text = "OVER-IP STREAM"
            tvSourceBadge.setBackgroundResource(R.drawable.bg_badge_yellow)
            tvSourceBadge.setTextColor(ContextCompat.getColor(context, R.color.yellow_primary))
        } else {
            tvSourceBadge.text = "LOCAL STORAGE"
            tvSourceBadge.setBackgroundResource(R.drawable.bg_badge_green)
            tvSourceBadge.setTextColor(ContextCompat.getColor(context, R.color.status_online))
        }

        tvFilePath.text = details.filePath

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCopyPath.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("File Path", details.filePath)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDone.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
