package com.aruncs.musicsync

import android.content.Context
import android.content.SharedPreferences
import com.aruncs.musicsync.client.SyncManager
import com.aruncs.musicsync.model.Song
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock

class SyncManagerTest {

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val sharedPrefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPrefs)
        syncManager = SyncManager(context)
    }

    @Test
    fun testCalculateDiff() {
        val localSong = Song(
            id = 1L,
            title = "Song A",
            artist = "Artist 1",
            album = "Album 1",
            filepath = "/music/song_a.mp3",
            filename = "song_a.mp3",
            size = 1000L,
            sizeFormatted = "1 KB",
            mtime = 0.0,
            mtimeStr = "",
            durationSec = 180.0,
            durationFormatted = "03:00",
            bitrateKbps = "320",
            searchableText = "Song A Artist 1"
        )

        val remoteSong = Song(
            id = 2L,
            title = "Song B",
            artist = "Artist 2",
            album = "Album 2",
            filepath = "/music/song_b.mp3",
            filename = "song_b.mp3",
            size = 1200L,
            sizeFormatted = "1.2 KB",
            mtime = 0.0,
            mtimeStr = "",
            durationSec = 200.0,
            durationFormatted = "03:20",
            bitrateKbps = "320",
            searchableText = "Song B Artist 2"
        )

        val diff = syncManager.calculateDiff(listOf(localSong), listOf(localSong, remoteSong))

        assertEquals(1, diff.songsToPull.size)
        assertEquals("song_b.mp3", diff.songsToPull[0].filename)
        assertEquals(0, diff.songsToPush.size)
        assertEquals(1, diff.alreadySynced.size)
    }
}
