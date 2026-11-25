package com.vktracks.player.data.api

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class VkAuthManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createSecurePrefs(appContext)

    private fun createSecurePrefs(ctx: Context): SharedPreferences {
        fun buildEncrypted(): SharedPreferences {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        return try {
            // Удаляем старый незашифрованный fallback-файл, если он оставался от предыдущих версий
            ctx.deleteSharedPreferences("vk_auth_fallback")
            buildEncrypted()
        } catch (e: Exception) {
            Log.e("VkAuthManager", "EncryptedSharedPreferences init failed, resetting secure prefs", e)
            try {
                ctx.deleteSharedPreferences(SECURE_PREFS_NAME)
                buildEncrypted()
            } catch (retryError: Exception) {
                Log.e("VkAuthManager", "Keystore unavailable, using non-persistent memory storage", retryError)
                InMemorySharedPreferences()
            }
        }
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
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            Log.w("VkAuthManager", "Failed to clear WebView session data", e)
        }
        _authState.value = false
    }

    companion object {
        const val VK_MUSIC_APP_ID = "6767438" // Официальный клиент VK Музыка (Boom)
        const val VK_ANDROID_APP_ID = "2274003" // Официальный клиент VK Android
        const val REDIRECT_URI = "https://oauth.vk.com/blank.html"
        private const val SECURE_PREFS_NAME = "vk_auth_secure"

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

        fun parseRedirectUrl(url: String, requireVkBlankHost: Boolean = false): Pair<String, Long>? {
            if (requireVkBlankHost) {
                val parsed = try { Uri.parse(url) } catch (_: Exception) { null } ?: return null
                if (!parsed.scheme.equals("https", ignoreCase = true) ||
                    !parsed.host.equals("oauth.vk.com", ignoreCase = true) ||
                    parsed.path != "/blank.html"
                ) {
                    return null
                }
            }

            val fragment = when {
                url.contains("#") -> url.substringAfter("#", "")
                url.contains("?") -> url.substringAfter("?", "")
                else -> ""
            }
            if (fragment.isEmpty()) return null

            var token: String? = null
            var userId: Long? = null

            for (param in fragment.split("&")) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = Uri.decode(parts[0])
                    val value = Uri.decode(parts[1])
                    when (key) {
                        "access_token" -> token = value
                        "user_id" -> userId = value.toLongOrNull()
                    }
                }
            }

            return if (!token.isNullOrEmpty() && userId != null && userId > 0) {
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

private class InMemorySharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { if (key != null) pending[key] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true; pending.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) map.clear()
            for ((k, v) in pending) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
