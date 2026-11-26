package com.vktracks.player.data.settings

import android.content.Context
import android.content.SharedPreferences

class PlayerSettingsManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "player_user_settings",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_BT_AUTO_RESUME = "bt_auto_resume"
        private const val KEY_BT_AUTO_PAUSE = "bt_auto_pause"
        private const val KEY_SOFT_FADING = "soft_fading"
        private const val KEY_AUTO_RESUME_WIFI_ONLY = "auto_resume_wifi_only"

        @Volatile
        private var INSTANCE: PlayerSettingsManager? = null

        fun getInstance(context: Context): PlayerSettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlayerSettingsManager(context).also { INSTANCE = it }
            }
        }
    }

    var isBtAutoResumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BT_AUTO_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_BT_AUTO_RESUME, value).apply()

    var isBtAutoPauseEnabled: Boolean
        get() = prefs.getBoolean(KEY_BT_AUTO_PAUSE, true)
        set(value) = prefs.edit().putBoolean(KEY_BT_AUTO_PAUSE, value).apply()

    var isSoftFadingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOFT_FADING, true)
        set(value) = prefs.edit().putBoolean(KEY_SOFT_FADING, value).apply()

    var isAutoResumeWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESUME_WIFI_ONLY, value).apply()
}
