package com.vktracks.player.data.api

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VkAuthManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "vk_auth_secure",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("vk_auth_fallback", Context.MODE_PRIVATE)
    }

    private val _authState = MutableStateFlow(isAuthorized())
    val authState: StateFlow<Boolean> = _authState.asStateFlow()

    fun isAuthorized(): Boolean {
        return !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, 0)
    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)
    fun getUserPhoto(): String? = prefs.getString(KEY_USER_PHOTO, null)

    fun saveAuthData(token: String, userId: Long, name: String? = null, photo: String? = null) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_USER_ID, userId)
            .apply {
                if (!name.isNullOrEmpty()) putString(KEY_USER_NAME, name)
                if (!photo.isNullOrEmpty()) putString(KEY_USER_PHOTO, photo)
            }
            .apply()
        _authState.value = true
    }

    fun logout() {
        prefs.edit().clear().apply()
        _authState.value = false
    }

    companion object {
        const val VK_MUSIC_APP_ID = "6767438" // Официальный клиент VK Музыка (Boom)
        const val KATE_APP_ID = "2685278" // Альтернативный клиент Kate Mobile
        const val REDIRECT_URI = "https://oauth.vk.com/blank.html"

        fun getOAuthUrl(clientId: String = VK_MUSIC_APP_ID): String {
            return Uri.parse("https://oauth.vk.com/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("display", "mobile")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", "65544") // 65536 (offline) + 8 (audio)
                .appendQueryParameter("response_type", "token")
                .appendQueryParameter("v", "5.131")
                .appendQueryParameter("revoke", "1")
                .build().toString()
        }

        fun parseRedirectUrl(url: String): Pair<String, Long>? {
            val fragment = when {
                url.contains("#") -> url.substringAfter("#", "")
                url.contains("?") -> url.substringAfter("?", "")
                else -> ""
            }
            if (fragment.isEmpty()) return null

            var token: String? = null
            var userId: Long? = null

            for (param in fragment.split("&")) {
                val parts = param.split("=")
                if (parts.size == 2) {
                    when (parts[0]) {
                        "access_token" -> token = parts[1]
                        "user_id" -> userId = parts[1].toLongOrNull()
                    }
                }
            }

            return if (!token.isNullOrEmpty() && userId != null) {
                Pair(token, userId)
            } else {
                null
            }
        }

        private const val KEY_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PHOTO = "user_photo"
    }
}
