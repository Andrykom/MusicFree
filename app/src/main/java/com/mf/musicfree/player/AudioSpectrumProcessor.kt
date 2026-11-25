package com.mf.musicfree.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sqrt

data class AudioSpectrum(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    val energy: Float = 0f
)

/**
 * Потокобезопасный монитор текущих частотных полос (без аллокаций и блокировок).
 * Может безопасно принимать данные от нескольких дек ExoPlayer (включая кроссфейд).
 */
class AudioSpectrumMonitor {
    @Volatile var bass: Float = 0f
        private set
    @Volatile var mid: Float = 0f
        private set
    @Volatile var high: Float = 0f
        private set
    @Volatile var energy: Float = 0f
        private set

    fun update(b: Float, m: Float, h: Float, e: Float) {
        // Быстрая атака, плавный спад — даёт «живой» отклик на бит
        bass = if (b > bass) bass * 0.35f + b * 0.65f else bass * 0.84f + b * 0.16f
        mid = if (m > mid) mid * 0.4f + m * 0.6f else mid * 0.86f + m * 0.14f
        high = if (h > high) high * 0.4f + h * 0.6f else high * 0.86f + h * 0.14f
        energy = if (e > energy) energy * 0.35f + e * 0.65f else energy * 0.85f + e * 0.15f
    }

    fun reset() {
        bass = 0f
        mid = 0f
        high = 0f
        energy = 0f
    }

    fun snapshot(): AudioSpectrum = AudioSpectrum(bass, mid, high, energy)
}

@OptIn(UnstableApi::class)
class AudioSpectrumProcessor(
    private val monitor: AudioSpectrumMonitor
) : BaseAudioProcessor() {

    private var sampleRate = 44100
    private var channelCount = 2
    private var lpBass = 0f
    private var lpMid = 0f
    private var alphaBass = 0.03f
    private var alphaMid = 0.25f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)

        // Коэффициенты однополюсных фильтров (с учётом децимации x2)
        val effRate = sampleRate / 2f
        alphaBass = (1f / (1f + effRate / (2f * PI.toFloat() * 190f))).coerceIn(0.01f, 0.9f)
        alphaMid = (1f / (1f + effRate / (2f * PI.toFloat() * 2400f))).coerceIn(0.05f, 0.95f)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val copy = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channelCount * 2
        val stepBytes = frameBytes * 2 // читаем каждый 2-й фрейм для экономии CPU

        var bassSum = 0.0
        var midSum = 0.0
        var highSum = 0.0
        var count = 0

        var pos = copy.position()
        val limit = copy.limit()
        while (pos + 2 <= limit) {
            val s = copy.getShort(pos) / 32768f
            lpBass += alphaBass * (s - lpBass)
            lpMid += alphaMid * (s - lpMid)

            val b = lpBass
            val m = lpMid - lpBass
            val h = s - lpMid

            bassSum += b * b
            midSum += m * m
            highSum += h * h
            count++
            pos += stepBytes
        }

        if (count > 0) {
            val bassRms = (sqrt(bassSum / count).toFloat() * 3.4f).coerceIn(0f, 1f)
            val midRms = (sqrt(midSum / count).toFloat() * 4.2f).coerceIn(0f, 1f)
            val highRms = (sqrt(highSum / count).toFloat() * 5.2f).coerceIn(0f, 1f)
            val totalEnergy = (bassRms * 0.5f + midRms * 0.3f + highRms * 0.2f).coerceIn(0f, 1f)
            monitor.update(bassRms, midRms, highRms, totalEnergy)
        }

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        lpBass = 0f
        lpMid = 0f
    }

    override fun onReset() {
        lpBass = 0f
        lpMid = 0f
    }
}