package com.vktracks.player

import com.vktracks.player.data.api.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun testStandardLrcParsing() {
        val lrc = """
            [ar: Queen]
            [ti: Bohemian Rhapsody]
            [00:00.15] Is this the real life?
            [00:07.13] Caught in a landslide
            [00:14.77] Open your eyes
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)

        assertEquals(150L, lines[0].beginMs)
        assertEquals(7130L, lines[0].endMs)
        assertEquals("Is this the real life?", lines[0].text)

        assertEquals(7130L, lines[1].beginMs)
        assertEquals(14770L, lines[1].endMs)
        assertEquals("Caught in a landslide", lines[1].text)

        assertEquals(14770L, lines[2].beginMs)
        assertEquals(19770L, lines[2].endMs)
        assertEquals("Open your eyes", lines[2].text)
    }

    @Test
    fun testMultiTimestampOnSingleLine() {
        val lrc = """
            [00:10.00][00:30.00] Repeated chorus line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].beginMs)
        assertEquals("Repeated chorus line", lines[0].text)
        assertEquals(30000L, lines[1].beginMs)
        assertEquals("Repeated chorus line", lines[1].text)
    }

    @Test
    fun testEmptyAndBlankLrc() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   ").isEmpty())
        assertTrue(LrcParser.parse("[ar: Instrumental]").isEmpty())
    }

    @Test
    fun testBuildLyricsHelperAndPrecision() {
        val inst = LrcParser.buildLyrics("text", "[00:01.29] line", isInstrumental = true)
        assertTrue(inst.isInstrumental)
        assertTrue(inst.lines.isEmpty())

        val parsed = LrcParser.parse("[00:00.29] Precision check")
        assertEquals(290L, parsed[0].beginMs)
    }
}
