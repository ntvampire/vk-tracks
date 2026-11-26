package com.vktracks.player.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.vktracks.player.audio.EqualizerPresets

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
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_PRESET_ID = "eq_preset_id"
        private const val KEY_EQ_BAND_GAINS = "eq_band_gains"

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

    var isEqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

    var eqPresetId: String
        get() = prefs.getString(KEY_EQ_PRESET_ID, EqualizerPresets.FLAT_PRESET_ID) ?: EqualizerPresets.FLAT_PRESET_ID
        set(value) = prefs.edit().putString(KEY_EQ_PRESET_ID, value).apply()

    var eqBandGains: FloatArray
        get() {
            val raw = prefs.getString(KEY_EQ_BAND_GAINS, null) ?: return FloatArray(EqualizerPresets.BAND_COUNT)
            val parts = raw.split(",")
            return FloatArray(EqualizerPresets.BAND_COUNT) { idx ->
                parts.getOrNull(idx)?.toFloatOrNull()
                    ?.coerceIn(EqualizerPresets.MIN_GAIN_DB, EqualizerPresets.MAX_GAIN_DB) ?: 0f
            }
        }
        set(value) {
            val serialized = (0 until EqualizerPresets.BAND_COUNT).joinToString(",") { idx ->
                value.getOrElse(idx) { 0f }
                    .coerceIn(EqualizerPresets.MIN_GAIN_DB, EqualizerPresets.MAX_GAIN_DB)
                    .toString()
            }
            prefs.edit().putString(KEY_EQ_BAND_GAINS, serialized).apply()
        }
}
