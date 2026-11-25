package com.vktracks.player

import com.vktracks.player.data.api.TrackMetadataMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetadataMatcherTest {

    @Test
    fun testIsGenericFiltersUntitledAndUnknown() {
        assertTrue(TrackMetadataMatcher.isGeneric("Unknown Artist", "My Song"))
        assertTrue(TrackMetadataMatcher.isGeneric("Неизвестный исполнитель", "Track 1"))
        assertTrue(TrackMetadataMatcher.isGeneric("Daft Punk", "Untitled"))
        assertTrue(TrackMetadataMatcher.isGeneric("Daft Punk", "Audio 05"))
        assertFalse(TrackMetadataMatcher.isGeneric("Daft Punk", "Around the World"))
    }

    @Test
    fun testTitleExactMatchIncludingParenthesesFix() {
        assertTrue(TrackMetadataMatcher.isTitleExactMatch("Around the World", "Around the World"))
        assertTrue(TrackMetadataMatcher.isTitleExactMatch("Around the World (Radio Edit)", "Around the World"))
        // Проверка исправленного сравнения normClean.startsWith("$normFound(")
        assertTrue(TrackMetadataMatcher.isTitleExactMatch("Song", "Song(Remix)"))
        assertTrue(TrackMetadataMatcher.isTitleExactMatch("Song", "Song Remix"))
        assertFalse(TrackMetadataMatcher.isTitleExactMatch("Another Song", "Song"))
    }

    @Test
    fun testArtistExactMatchAndDuets() {
        val trackArtists = TrackMetadataMatcher.splitArtists("Daft Punk feat. Pharrell Williams")
        assertTrue(TrackMetadataMatcher.isArtistExactMatch("Daft Punk", "Daft Punk", trackArtists))
        assertTrue(TrackMetadataMatcher.isArtistExactMatch("Pharrell Williams", "Daft Punk", trackArtists))
        assertFalse(TrackMetadataMatcher.isArtistExactMatch("Cover Band", "Daft Punk", trackArtists))
    }

    @Test
    fun testCleanTrackTitleAndJunkKeywords() {
        val cleaned = TrackMetadataMatcher.cleanTrackTitle(
            "My Track [vk.com/music] (Official Video) [2024]",
            stripEditionTags = true
        )
        assertEquals("My Track", cleaned)
        assertTrue(TrackMetadataMatcher.containsJunkKeyword("best of karaoke hits 2024"))
        assertFalse(TrackMetadataMatcher.containsJunkKeyword("daft punk discovery"))
    }
}
