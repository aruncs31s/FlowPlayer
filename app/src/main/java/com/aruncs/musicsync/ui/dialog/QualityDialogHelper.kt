package com.aruncs.musicsync.ui.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.aruncs.musicsync.data.AppPreferences

object QualityDialogHelper {

    fun show(
        context: Context,
        prefs: AppPreferences,
        onQualitySelected: (displayLabel: String, value: String) -> Unit
    ) {
        val options = arrayOf("Original (lossless)", "320 kbps", "256 kbps", "192 kbps", "128 kbps")
        val values = arrayOf("original", "320", "256", "192", "128")
        val current = prefs.downloadQuality
        val checkedItem = values.indexOfFirst { it == current }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("Download Quality")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selected = values[which]
                prefs.downloadQuality = selected
                val label = if (selected == "original") "HQ" else "${selected}k"
                onQualitySelected(label, selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
