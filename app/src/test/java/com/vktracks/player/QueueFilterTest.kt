package com.vktracks.player

import com.vktracks.player.data.model.Track
import com.vktracks.player.data.model.TrackRestriction
import com.vktracks.player.service.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueFilterTest {

    private fun track(
        id: Long,
        streamUrl: String = "https://example.com/$id.mp3",
        duration: Int = 180,
        localFilePath: String? = null,
        restriction: TrackRestriction = TrackRestriction.NONE
    ) = Track(
        id = id,
        ownerId = 1L,
        title = "Track $id",
        artist = "Artist $id",
        duration = duration,
        streamUrl = streamUrl,
        localFilePath = localFilePath,
        restriction = restriction
    )

    @Test
    fun isTrackPlayableAllowsRemoteLocalAndRadioTracks() {
        val remote = track(id = 1L)
        val local = track(id = 2L, streamUrl = "", localFilePath = "/storage/emulated/0/Music/2.mp3")
        val radio = track(id = 3L, streamUrl = "https://radio.example.com/live", duration = 0)

        assertTrue(PlaybackService.isTrackPlayable(remote))
        assertTrue(PlaybackService.isTrackPlayable(local))
        assertTrue(PlaybackService.isTrackPlayable(radio))
    }

    @Test
    fun isTrackPlayableRejectsRestrictedAndEmptyStreamTracks() {
        val geoRestricted = track(id = 10L, streamUrl = "", restriction = TrackRestriction.GEO)
        val labelRestrictedWithUrl = track(id = 11L, restriction = TrackRestriction.LABEL_DECISION)
        val emptyUrl = track(id = 12L, streamUrl = "")
        val emptyUrlZeroDuration = track(id = 13L, streamUrl = "", duration = 0)

        assertFalse(PlaybackService.isTrackPlayable(geoRestricted))
        assertFalse(PlaybackService.isTrackPlayable(labelRestrictedWithUrl))
        assertFalse(PlaybackService.isTrackPlayable(emptyUrl))
        assertFalse(PlaybackService.isTrackPlayable(emptyUrlZeroDuration))
    }

    @Test
    fun filterPlayableTracksRemovesUnavailableWhilePreservingOrder() {
        val t1 = track(id = 1L)
        val restricted = track(id = 2L, streamUrl = "", restriction = TrackRestriction.ARTIST_DECISION)
        val local = track(id = 3L, streamUrl = "", localFilePath = "/storage/3.mp3")
        val empty = track(id = 4L, streamUrl = "")
        val radio = track(id = 5L, streamUrl = "https://radio.example.com/stream", duration = 0)

        val filtered = PlaybackService.filterPlayableTracks(listOf(t1, restricted, local, empty, radio))

        assertEquals(listOf(t1, local, radio), filtered)
    }
}
