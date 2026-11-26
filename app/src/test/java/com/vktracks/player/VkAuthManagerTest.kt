package com.vktracks.player

import com.vktracks.player.data.api.VkAuthManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VkAuthManagerTest {

    @Test
    fun parseRedirectUrlExtractsTokenAndUserIdFromFragment() {
        val url = "https://oauth.vk.com/blank.html#access_token=vk1.a.testToken123&expires_in=0&user_id=987654321"
        val parsed = VkAuthManager.parseRedirectUrl(url, requireVkBlankHost = true)

        assertEquals(Pair("vk1.a.testToken123", 987654321L), parsed)
    }

    @Test
    fun parseRedirectUrlExtractsFromQueryAndPreservesSpecialChars() {
        val url = "https://oauth.vk.com/blank.html?access_token=abc%3Ddef%2B123+xyz&user_id=42"
        val parsed = VkAuthManager.parseRedirectUrl(url, requireVkBlankHost = true)

        assertEquals(Pair("abc=def+123+xyz", 42L), parsed)
    }

    @Test
    fun parseRedirectUrlRejectsUntrustedHostOrSchemeWhenRequired() {
        val httpUrl = "http://oauth.vk.com/blank.html#access_token=tok&user_id=123"
        val evilUrl = "https://evil.example.com/blank.html#access_token=tok&user_id=123"
        val wrongPath = "https://oauth.vk.com/authorize#access_token=tok&user_id=123"

        assertNull(VkAuthManager.parseRedirectUrl(httpUrl, requireVkBlankHost = true))
        assertNull(VkAuthManager.parseRedirectUrl(evilUrl, requireVkBlankHost = true))
        assertNull(VkAuthManager.parseRedirectUrl(wrongPath, requireVkBlankHost = true))
        assertEquals(Pair("tok", 123L), VkAuthManager.parseRedirectUrl(evilUrl, requireVkBlankHost = false))
    }

    @Test
    fun parseRedirectUrlRejectsMissingTokenOrInvalidUserId() {
        assertNull(VkAuthManager.parseRedirectUrl("https://oauth.vk.com/blank.html#access_token=&user_id=10", true))
        assertNull(VkAuthManager.parseRedirectUrl("https://oauth.vk.com/blank.html#access_token=tok&user_id=0", true))
        assertNull(VkAuthManager.parseRedirectUrl("https://oauth.vk.com/blank.html#access_token=tok&user_id=-5", true))
        assertNull(VkAuthManager.parseRedirectUrl("https://oauth.vk.com/blank.html", true))
    }
}
