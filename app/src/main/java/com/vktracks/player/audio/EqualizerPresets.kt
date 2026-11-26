package com.vktracks.player.audio

data class EqualizerPreset(
    val id: String,
    val name: String,
    val gainsDb: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqualizerPreset) return false
        return id == other.id && name == other.name && gainsDb.contentEquals(other.gainsDb)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + gainsDb.contentHashCode()
        return result
    }
}

object EqualizerPresets {
    const val BAND_COUNT = 10
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val CUSTOM_PRESET_ID = "custom"
    const val FLAT_PRESET_ID = "flat"

    val BAND_FREQUENCIES_HZ = floatArrayOf(
        32f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )

    val BAND_LABELS = listOf(
        "32", "63", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"
    )

    val PRESETS: List<EqualizerPreset> = listOf(
        EqualizerPreset(
            id = FLAT_PRESET_ID,
            name = "По умолчанию",
            gainsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        ),
        EqualizerPreset(
            id = "bass_boost",
            name = "Бас",
            gainsDb = floatArrayOf(6f, 5f, 4f, 3f, 2f, 0f, 0f, 0f, 0f, 0f)
        ),
        EqualizerPreset(
            id = "vocal_boost",
            name = "Вокал",
            gainsDb = floatArrayOf(-2f, -3f, -3f, 2f, 5f, 5f, 4f, 3f, 0f, -2f)
        ),
        EqualizerPreset(
            id = "rock",
            name = "Рок",
            gainsDb = floatArrayOf(5f, 2f, -3f, -6f, -3f, 3f, 6f, 8f, 8f, 8f)
        ),
        EqualizerPreset(
            id = "pop",
            name = "Поп",
            gainsDb = floatArrayOf(-1f, 0f, 0f, 1f, 4f, 3f, 1f, 0f, -1f, 1f)
        ),
        EqualizerPreset(
            id = "dance",
            name = "Танцевальная",
            gainsDb = floatArrayOf(8f, 6f, 2f, 0f, 0f, -4f, -6f, -6f, 0f, 0f)
        ),
        EqualizerPreset(
            id = "jazz",
            name = "Джаз",
            gainsDb = floatArrayOf(3f, 3f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 4f)
        ),
        EqualizerPreset(
            id = "classical",
            name = "Классика",
            gainsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -6f, -6f, -6f, -7f)
        ),
        EqualizerPreset(
            id = "treble",
            name = "Высокие",
            gainsDb = floatArrayOf(4f, 4f, 4f, 2f, 0f, 3f, 9f, 10f, 10f, 10f)
        )
    )

    fun findById(id: String): EqualizerPreset? = PRESETS.firstOrNull { it.id == id }

    fun displayNameForId(id: String): String {
        if (id == CUSTOM_PRESET_ID) return "Пользовательский"
        return findById(id)?.name ?: "По умолчанию"
    }
}
