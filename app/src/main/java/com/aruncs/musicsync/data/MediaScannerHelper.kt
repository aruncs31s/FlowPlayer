package com.aruncs.musicsync.data

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

object MediaScannerHelper {

    fun scanFile(context: Context, filepath: String, callback: ((String, String?) -> Unit)? = null) {
        val file = File(filepath)
        if (!file.exists()) return

        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(file.absolutePath),
            null
        ) { path, uri ->
            callback?.invoke(path, uri?.toString())
        }
    }

    fun scanFiles(context: Context, filepaths: List<String>, onComplete: (() -> Unit)? = null) {
        if (filepaths.isEmpty()) {
            onComplete?.invoke()
            return
        }

        val existing = filepaths.filter { File(it).exists() }.toTypedArray()
        if (existing.isEmpty()) {
            onComplete?.invoke()
            return
        }

        var scannedCount = 0
        MediaScannerConnection.scanFile(
            context.applicationContext,
            existing,
            null
        ) { _, _ ->
            scannedCount++
            if (scannedCount >= existing.size) {
                onComplete?.invoke()
            }
        }
    }
}
