package com.vktracks.player

import com.vktracks.player.data.downloader.FileSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSanitizerTest {

    @Test
    fun testSanitizeReplacesIllegalCharsAndControlBytes() {
        val input = "Artist: \"Test\" / Title? <2026>\u0001\n"
        val sanitized = FileSanitizer.sanitize(input)
        assertEquals("Artist- 'Test' _ Title_ [2026]", sanitized)
    }

    @Test
    fun testSanitizeBlocksDotSegmentsAndCapsLength() {
        assertEquals("Unknown", FileSanitizer.sanitize(".."))
        assertEquals("Unknown", FileSanitizer.sanitize("."))
        assertEquals("Unknown", FileSanitizer.sanitize("   "))

        val longName = "A".repeat(150)
        assertTrue(FileSanitizer.sanitize(longName).length <= 100)
    }

    @Test
    fun testSanitizeSubfolderRejectsTraversal() {
        assertNull(FileSanitizer.sanitizeSubfolder(null))
        assertNull(FileSanitizer.sanitizeSubfolder("   "))
        assertNull(FileSanitizer.sanitizeSubfolder(".."))
        assertNull(FileSanitizer.sanitizeSubfolder("."))
        assertEquals("Rock _ Mix", FileSanitizer.sanitizeSubfolder("Rock / Mix"))
    }
}
