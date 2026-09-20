package com.aruncs.musicsync.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.aruncs.musicsync.R

object AboutDialogHelper {
    fun show(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_about, null)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_close_about)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
