package com.vktracks.player

import com.vktracks.player.audio.EqualizerAudioProcessor
import com.vktracks.player.audio.EqualizerPresets
import com.vktracks.player.audio.PeakingBiquadFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {

    @Test
    fun allPresetsHaveTenValidBandsAndFindByIdWorks() {
        for (preset in EqualizerPresets.PRESETS) {
            assertEquals(EqualizerPresets.BAND_COUNT, preset.gainsDb.size)
            assertTrue(preset.gainsDb.all { it in EqualizerPresets.MIN_GAIN_DB..EqualizerPresets.MAX_GAIN_DB })
            assertNotNull(EqualizerPresets.findById(preset.id))
        }
        assertEquals("Пользовательский", EqualizerPresets.displayNameForId(EqualizerPresets.CUSTOM_PRESET_ID))
    }

    @Test
    fun peakingBiquadFilterBoostsTargetFrequencyCleanly() {
        val sampleRate = 44100
        val freq = 1000f
        val filter = PeakingBiquadFilter().apply {
            configure(sampleRate, freq, gainDb = 6f)
        }

        // Генерируем синусоиду 1 кГц и сравниваем RMS после прогрева фильтра
        val totalSamples = 2048
        var inputSq = 0.0
        var outputSq = 0.0
        for (i in 0 until totalSamples) {
            val sample = sin(2.0 * PI * freq * i / sampleRate) * 10000.0
            val filtered = filter.process(sample)
            if (i >= 512) {
                inputSq += sample * sample
                outputSq += filtered * filtered
            }
        }
        val ratio = sqrt(outputSq / inputSq)
        // +6 дБ соответствует росту амплитуды ~2.0x (с запасом 1.7x..2.2x)
        assertTrue("Expected boosted gain ~2.0x, got $ratio", ratio in 1.7..2.2)
    }

    @Test
    fun equalizerProcessorClampAndSettingsDoNotThrow() {
        val processor = EqualizerAudioProcessor()
        processor.updateSettings(
            enabled = true,
            gainsDb = floatArrayOf(20f, -25f, 0f, 3f, 6f, -4f, 1f, 2f, 5f, 8f)
        )
        // Класс корректно инициализируется и принимает настройки в чистой JVM
        assertNotNull(processor)
    }
}
