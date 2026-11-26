package com.vktracks.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Коэффициенты и состояние одного полосового фильтра RBJ Peaking EQ.
 * Вынесено в чистый Kotlin для переносимости и unit-тестирования.
 */
internal class PeakingBiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(sampleRate: Int, centerFreqHz: Float, gainDb: Float, q: Float = 1.41f) {
        if (sampleRate <= 0 || centerFreqHz >= sampleRate * 0.49f || abs(gainDb) < 0.05f) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            return
        }
        val a = 10.0.pow(gainDb.toDouble() / 40.0)
        val omega = 2.0 * PI * centerFreqHz.toDouble() / sampleRate.toDouble()
        val sinW = sin(omega)
        val cosW = cos(omega)
        val alpha = sinW / (2.0 * q.toDouble())

        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun process(sample: Double): Double {
        val y = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = y
        return y
    }

    fun resetState() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}

/**
 * Встроенный 10-полосный графический эквалайзер для Media3 / ExoPlayer.
 * Работает на всех устройствах (динамики, Bluetooth, USB-ЦАП) без зависимости от AudioFX HAL.
 */
class EqualizerAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var bandGainsDb: FloatArray = FloatArray(EqualizerPresets.BAND_COUNT)

    @Volatile
    private var configVersion: Long = 0L

    private var appliedVersion: Long = -1L
    private var activeMask: BooleanArray = BooleanArray(EqualizerPresets.BAND_COUNT)
    private var hasAnyActiveBand: Boolean = false
    private var preampScale: Double = 1.0
    private var filters: Array<Array<PeakingBiquadFilter>> = emptyArray()

    fun updateSettings(enabled: Boolean, gainsDb: FloatArray) {
        val copy = FloatArray(EqualizerPresets.BAND_COUNT) { idx ->
            gainsDb.getOrElse(idx) { 0f }.coerceIn(EqualizerPresets.MIN_GAIN_DB, EqualizerPresets.MAX_GAIN_DB)
        }
        this.enabled = enabled
        this.bandGainsDb = copy
        this.configVersion++
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0) {
            return AudioFormat.NOT_SET
        }
        val chCount = inputAudioFormat.channelCount.coerceAtMost(MAX_CHANNELS)
        filters = Array(chCount) {
            Array(EqualizerPresets.BAND_COUNT) { PeakingBiquadFilter() }
        }
        appliedVersion = -1L
        rebuildCoefficientsIfNeeded(inputAudioFormat.sampleRate, chCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val chCount = inputAudioFormat.channelCount.coerceAtMost(MAX_CHANNELS)
        rebuildCoefficientsIfNeeded(inputAudioFormat.sampleRate, chCount)

        val outBuffer = replaceOutputBuffer(remaining)
        if (!enabled || !hasAnyActiveBand || chCount <= 0) {
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        val frameCount = remaining / (2 * inputAudioFormat.channelCount)
        val totalChannels = inputAudioFormat.channelCount
        val gains = bandGainsDb
        val scale = preampScale

        for (frame in 0 until frameCount) {
            for (ch in 0 until totalChannels) {
                var sample = inputBuffer.getShort().toDouble() * scale
                if (ch < chCount) {
                    val chFilters = filters[ch]
                    for (band in 0 until EqualizerPresets.BAND_COUNT) {
                        if (activeMask[band]) {
                            sample = chFilters[band].process(sample)
                        }
                    }
                }
                val clamped = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                outBuffer.putShort(clamped)
            }
        }
        // Копируем возможный неполный хвост без краша
        while (inputBuffer.hasRemaining()) {
            outBuffer.put(inputBuffer.get())
        }
        outBuffer.flip()
    }

    override fun onFlush() {
        for (chFilters in filters) {
            for (filter in chFilters) {
                filter.resetState()
            }
        }
    }

    override fun onReset() {
        filters = emptyArray()
        appliedVersion = -1L
    }

    private fun rebuildCoefficientsIfNeeded(sampleRate: Int, channelCount: Int) {
        val targetVersion = configVersion
        if (appliedVersion == targetVersion) return

        val gains = bandGainsDb
        var anyActive = false
        var maxBoostDb = 0f
        val newMask = BooleanArray(EqualizerPresets.BAND_COUNT)

        for (band in 0 until EqualizerPresets.BAND_COUNT) {
            val gain = gains[band]
            val active = abs(gain) >= 0.05f
            newMask[band] = active
            if (active) {
                anyActive = true
                if (gain > maxBoostDb) maxBoostDb = gain
            }
            val freq = EqualizerPresets.BAND_FREQUENCIES_HZ[band]
            for (ch in 0 until minOf(channelCount, filters.size)) {
                filters[ch][band].configure(sampleRate, freq, gain)
            }
        }

        activeMask = newMask
        hasAnyActiveBand = anyActive
        // Мягкий auto-headroom снижает риск клиппинга при подъёме частот
        preampScale = if (maxBoostDb > 0f) {
            10.0.pow((-maxBoostDb * 0.5) / 20.0)
        } else {
            1.0
        }
        appliedVersion = targetVersion
    }

    companion object {
        private const val MAX_CHANNELS = 8
    }
}
