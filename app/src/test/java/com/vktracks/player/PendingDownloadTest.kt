package com.vktracks.player

import com.vktracks.player.data.local.toPendingDownload
import com.vktracks.player.data.local.toTrack
import com.vktracks.player.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDownloadTest {

    @Test
    fun pendingDownloadRoundTripPreservesMetadataAndSubfolder() {
        val original = Track(
            id = 555L,
            ownerId = -200L,
            title = "Night Drive",
            artist = "Synth Artist",
            duration = 215,
            streamUrl = "https://cdn.vk.com/audio/555.mp3",
            coverUrl = "https://cdn.vk.com/cover/555.jpg",
            albumTitle = "Outrun",
            isExplicit = true,
            lyricsId = 777L,
            accessKey = "key123",
            isLiked = true
        )

        val entity = original.toPendingDownload(subfolder = "Workout", lastError = "timeout")
        assertEquals("-200_555", entity.id)
        assertEquals("Workout", entity.subfolder)
        assertEquals("timeout", entity.lastError)
        assertEquals(0, entity.retryCount)

        val restored = entity.toTrack()
        assertEquals(original.id, restored.id)
        assertEquals(original.ownerId, restored.ownerId)
        assertEquals(original.fullId, restored.fullId)
        assertEquals(original.title, restored.title)
        assertEquals(original.artist, restored.artist)
        assertEquals(original.duration, restored.duration)
        assertEquals(original.streamUrl, restored.streamUrl)
        assertEquals(original.coverUrl, restored.coverUrl)
        assertEquals(original.albumTitle, restored.albumTitle)
        assertTrue(restored.isExplicit)
        assertEquals(777L, restored.lyricsId)
        assertEquals("key123", restored.accessKey)
        assertTrue(restored.isLiked)
        assertFalse(restored.isLocal)
        assertFalse(restored.isRestricted)
    }
}
