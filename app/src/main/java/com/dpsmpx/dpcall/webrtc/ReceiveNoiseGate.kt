package com.dpsmpx.dpcall.webrtc

import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Шумоподавление входящего звука (то, что мы слышим от собеседника).
 *
 * Плавный экспандер: отслеживает уровень фонового шума и приглушает паузы между
 * фразами, не трогая саму речь. Никогда не усиливает сигнал, поэтому не может
 * вызвать перегруз или эхо. Вызывается из аудиопотока WebRTC каждые 10 мс и
 * не выделяет память.
 *
 * Буфер — float-сэмплы в диапазоне int16. При частоте выше 16 кГц WebRTC может
 * передавать данные разбитыми на полосы; одинаковое усиление для всех сэмплов
 * корректно в обоих случаях.
 */
class ReceiveNoiseGate : ExternalAudioProcessingFactory.AudioProcessing {

    private var noiseFloorDb = INITIAL_FLOOR_DB
    private var gain = 1f
    private var holdFrames = 0

    override fun initialize(sampleRateHz: Int, numChannels: Int) = resetState()

    override fun reset(newRate: Int) = resetState()

    private fun resetState() {
        noiseFloorDb = INITIAL_FLOOR_DB
        gain = 1f
        holdFrames = 0
    }

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        buffer.order(ByteOrder.nativeOrder())
        val count = min(buffer.capacity() / 4, max(1, numBands) * numFrames)
        if (count <= 0) return

        var sum = 0.0
        for (i in 0 until count) {
            val s = buffer.getFloat(i * 4)
            sum += s * s
        }
        val rms = sqrt(sum / count) / 32768.0
        val levelDb = if (rms > 1e-7) (20.0 * log10(rms)).toFloat() else -140f

        // Оценка шумового фона: быстро вниз, медленно вверх.
        if (levelDb > -100f) {
            noiseFloorDb = if (levelDb < noiseFloorDb) {
                noiseFloorDb + (levelDb - noiseFloorDb) * 0.5f
            } else {
                noiseFloorDb + FLOOR_RISE_DB_PER_FRAME
            }
            noiseFloorDb = noiseFloorDb.coerceIn(MIN_FLOOR_DB, MAX_FLOOR_DB)
        }

        val isSpeech = levelDb > noiseFloorDb + OPEN_MARGIN_DB
        if (isSpeech) holdFrames = HOLD_FRAMES else if (holdFrames > 0) holdFrames--

        val target = if (holdFrames > 0) 1f else CLOSED_GAIN
        val newGain = if (target > gain) {
            target // открываемся мгновенно, чтобы не съедать начало слова
        } else {
            gain + (target - gain) * RELEASE_COEFF
        }

        if (gain > 0.999f && newGain > 0.999f) {
            gain = 1f
            return
        }

        val step = (newGain - gain) / count
        var g = gain
        for (i in 0 until count) {
            g += step
            val idx = i * 4
            buffer.putFloat(idx, buffer.getFloat(idx) * g)
        }
        gain = newGain
    }

    private companion object {
        const val INITIAL_FLOOR_DB = -60f
        const val MIN_FLOOR_DB = -85f
        const val MAX_FLOOR_DB = -38f
        const val FLOOR_RISE_DB_PER_FRAME = 0.02f // ~2 дБ/с
        const val OPEN_MARGIN_DB = 7f
        const val HOLD_FRAMES = 25 // 250 мс после речи
        const val RELEASE_COEFF = 0.12f
        val CLOSED_GAIN = 10.0.pow(-14.0 / 20.0).toFloat() // −14 дБ в паузах
    }
}
