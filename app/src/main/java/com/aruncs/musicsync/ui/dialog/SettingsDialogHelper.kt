package com.aruncs.musicsync.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.aruncs.musicsync.R
import com.aruncs.musicsync.data.AppPreferences

object SettingsDialogHelper {
    fun show(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)
        val rgDownload = dialogView.findViewById<RadioGroup>(R.id.rg_download_quality)
        val rgStream = dialogView.findViewById<RadioGroup>(R.id.rg_stream_quality)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_settings)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_close_settings)

        val prefs = AppPreferences(context)

        // Select current download quality
        when (prefs.downloadQuality) {
            "320" -> rgDownload.check(R.id.rb_download_320)
            "192" -> rgDownload.check(R.id.rb_download_192)
            "128" -> rgDownload.check(R.id.rb_download_128)
            else -> rgDownload.check(R.id.rb_download_original)
        }

        // Select current stream quality
        when (prefs.streamQuality) {
            "320" -> rgStream.check(R.id.rb_stream_320)
            "192" -> rgStream.check(R.id.rb_stream_192)
            "128" -> rgStream.check(R.id.rb_stream_128)
            else -> rgStream.check(R.id.rb_stream_original)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSave.setOnClickListener {
            prefs.downloadQuality = when (rgDownload.checkedRadioButtonId) {
                R.id.rb_download_320 -> "320"
                R.id.rb_download_192 -> "192"
                R.id.rb_download_128 -> "128"
                else -> "original"
            }

            prefs.streamQuality = when (rgStream.checkedRadioButtonId) {
                R.id.rb_stream_320 -> "320"
                R.id.rb_stream_192 -> "192"
                R.id.rb_stream_128 -> "128"
                else -> "original"
            }

            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
