package com.aruncs.musicsync.model

data class SyncDiff(
    val songsToPull: List<Song>,
    val songsToPush: List<Song>,
    val alreadySynced: List<Song> = emptyList()
) {
    val toDownload: List<Song> get() = songsToPull
    val toUpload: List<Song> get() = songsToPush
}
