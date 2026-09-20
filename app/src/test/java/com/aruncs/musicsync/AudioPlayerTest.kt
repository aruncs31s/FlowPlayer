package com.aruncs.musicsync

import com.aruncs.musicsync.model.Song
import com.aruncs.musicsync.player.AudioPlayer
import com.aruncs.musicsync.player.PlayableItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioPlayerTest {

    @Test
    fun testQueueManagement() {
        val song1 = Song(id = 1L, title = "T1", artist = "A1", album = "", filepath = "/1.mp3", filename = "1.mp3", size = 0, sizeFormatted = "", mtime = 0.0, mtimeStr = "", durationSec = 0.0, durationFormatted = "", bitrateKbps = "", searchableText = "")
        val song2 = Song(id = 2L, title = "T2", artist = "A2", album = "", filepath = "/2.mp3", filename = "2.mp3", size = 0, sizeFormatted = "", mtime = 0.0, mtimeStr = "", durationSec = 0.0, durationFormatted = "", bitrateKbps = "", searchableText = "")

        val items = listOf(PlayableItem(song1, null), PlayableItem(song2, null))
        assertEquals(2, items.size)
        assertEquals("T1", items[0].song.title)
        assertEquals("T2", items[1].song.title)
    }
}
