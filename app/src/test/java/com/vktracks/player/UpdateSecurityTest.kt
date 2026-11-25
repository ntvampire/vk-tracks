package com.vktracks.player

import com.vktracks.player.data.updater.UpdateSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSanitizeVersionNamePreventsPathTraversal() {
        val safe = UpdateSecurity.sanitizeVersionName("../../etc/1.0.67")
        assertFalse(safe.contains("/"))
        assertFalse(safe.contains(".."))
        assertEquals("update", UpdateSecurity.sanitizeVersionName("..."))
        assertEquals("1.0.67", UpdateSecurity.sanitizeVersionName("1.0.67"))
    }

    @Test
    fun testComputeSha256MatchesKnownDigest() {
        val file = File(tempFolder.root, "test.bin")
        file.writeText("vk-tracks")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expected = md.digest("vk-tracks".toByteArray()).joinToString("") { "%02x".format(it) }
        val actual = UpdateSecurity.computeSha256(file)
        assertEquals(64, actual.length)
        assertEquals(expected, actual)
    }
}
