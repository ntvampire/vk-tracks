package com.vktracks.player

import com.vktracks.player.data.model.AudioPaginationHelper
import com.vktracks.player.data.model.AudiosPage
import com.vktracks.player.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPaginationTest {

    private fun track(id: Long) = Track(
        id = id,
        ownerId = 1L,
        title = "Song $id",
        artist = "Artist",
        duration = 180,
        streamUrl = "https://example.com/$id.mp3"
    )

    @Test
    fun mergePageUsesRawCountForOffsetAndDeduplicates() {
        val t1 = track(1L)
        val t2 = track(2L)
        val t3 = track(3L)

        // Первая страница: 100 сырых записей от VK API, из них часть удалена, в списке t1 и t2, всего 250
        val first = AudioPaginationHelper.mergePage(
            existing = emptyList(),
            currentOffset = 0,
            page = AudiosPage(tracks = listOf(t1, t2), totalCount = 250, rawCount = 100),
            pageSize = 100
        )
        assertEquals(listOf(t1, t2), first.tracks)
        assertEquals(100, first.nextOffset)
        assertTrue(first.hasMore)

        // Вторая страница содержит дубликат t2 и новый трек t3
        val second = AudioPaginationHelper.mergePage(
            existing = first.tracks,
            currentOffset = first.nextOffset,
            page = AudiosPage(tracks = listOf(t2, t3), totalCount = 250, rawCount = 100),
            pageSize = 100
        )
        assertEquals(listOf(t1, t2, t3), second.tracks)
        assertEquals(listOf(t3), second.newlyAdded)
        assertEquals(200, second.nextOffset)
        assertTrue(second.hasMore)
    }

    @Test
    fun mergePageStopsWhenTotalReachedOrShortNextPageOrEmptyRawCount() {
        val existing = listOf(track(1L))

        val reachedTotal = AudioPaginationHelper.mergePage(
            existing = existing,
            currentOffset = 200,
            page = AudiosPage(tracks = listOf(track(2L)), totalCount = 250, rawCount = 50),
            pageSize = 100
        )
        assertFalse(reachedTotal.hasMore)
        assertEquals(250, reachedTotal.nextOffset)

        val shortSubsequentPage = AudioPaginationHelper.mergePage(
            existing = existing,
            currentOffset = 100,
            page = AudiosPage(tracks = listOf(track(2L)), totalCount = 0, rawCount = 40),
            pageSize = 100
        )
        assertFalse(shortSubsequentPage.hasMore)

        val zeroRaw = AudioPaginationHelper.mergePage(
            existing = existing,
            currentOffset = 100,
            page = AudiosPage(tracks = emptyList(), totalCount = 300, rawCount = 0),
            pageSize = 100
        )
        assertFalse(zeroRaw.hasMore)
        assertEquals(100, zeroRaw.nextOffset)
    }
}
