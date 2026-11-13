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
 * Плавный экспандер с мягким порогом: отслеживает уровень фонового шума и приглушает
 * паузы между фразами до −20 дБ, не трогая саму речь. Чтобы не было «дыхания» —
 * слышимого подъёма и спада шума:
 * - усиление зависит от превышения над шумом плавно (в полосе 3–9 дБ), а не скачком;
 * - после речи 300 мс держится открытым, затем закрывается не быстрее ~1,5 дБ за 10 мс;
 * - пока идёт речь, оценка шума почти не растёт и не начинает «съедать» тихие слоги.
 *
 * Никогда не усиливает сигнал, поэтому не может вызвать перегруз или эхо. Вызывается
 * из аудиопотока WebRTC каждые 10 мс и не выделяет память.
 *
 * Буфер — float-сэмплы в диапазоне int16. При частоте выше 16 кГц WebRTC может
 * передавать данные разбитыми на полосы; одинаковое усиление для всех сэмплов
 * корректно в обоих случаях.
 */
class ReceiveNoiseGate : ExternalAudioProcessingFactory.AudioProcessing {

    private var noiseFloorDb = INITIAL_FLOOR_DB
    private var gainDb = 0f
    private var holdFrames = 0

    override fun initialize(sampleRateHz: Int, numChannels: Int) = resetState()

    override fun reset(newRate: Int) = resetState()

    private fun resetState() {
        noiseFloorDb = INITIAL_FLOOR_DB
        gainDb = 0f
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
        val snr = levelDb - noiseFloorDb

        // Оценка шумового фона: быстро вниз; вверх — медленно, а во время речи почти никак.
        if (levelDb > -100f) {
            noiseFloorDb = when {
                levelDb < noiseFloorDb -> noiseFloorDb + (levelDb - noiseFloorDb) * 0.5f
                snr < KNEE_HIGH_DB -> noiseFloorDb + FLOOR_RISE_DB_PER_FRAME
                else -> noiseFloorDb + FLOOR_RISE_SPEECH_DB_PER_FRAME
            }.coerceIn(MIN_FLOOR_DB, MAX_FLOOR_DB)
        }

        if (snr >= KNEE_HIGH_DB) holdFrames = HOLD_FRAMES else if (holdFrames > 0) holdFrames--

        val targetDb = when {
            holdFrames > 0 -> 0f
            snr <= KNEE_LOW_DB -> CLOSED_DB
            else -> CLOSED_DB * (KNEE_HIGH_DB - snr) / (KNEE_HIGH_DB - KNEE_LOW_DB)
        }
        val newGainDb = if (targetDb >= gainDb) {
            targetDb // открываемся мгновенно, чтобы не съедать начало слова
        } else {
            gainDb + ((targetDb - gainDb) * RELEASE_COEFF).coerceAtLeast(-MAX_RELEASE_DB_PER_FRAME)
        }

        if (gainDb > -0.01f && newGainDb > -0.01f) {
            gainDb = 0f
            return
        }

        val from = dbToGain(gainDb)
        val to = dbToGain(newGainDb)
        val step = (to - from) / count
        var g = from
        for (i in 0 until count) {
            g += step
            val idx = i * 4
            buffer.putFloat(idx, buffer.getFloat(idx) * g)
        }
        gainDb = newGainDb
    }

    private fun dbToGain(db: Float): Float = if (db >= 0f) 1f else 10.0.pow(db / 20.0).toFloat()

    companion object {
        private const val INITIAL_FLOOR_DB = -60f
        private const val MIN_FLOOR_DB = -85f
        private const val MAX_FLOOR_DB = -38f
        private const val FLOOR_RISE_DB_PER_FRAME = 0.02f // ~2 дБ/с в паузах
        private const val FLOOR_RISE_SPEECH_DB_PER_FRAME = 0.002f // ~0,2 дБ/с во время речи
        /** Ниже этого превышения над шумом — пауза, выше [KNEE_HIGH_DB] — речь. */
        private const val KNEE_LOW_DB = 3f
        private const val KNEE_HIGH_DB = 9f
        private const val HOLD_FRAMES = 30 // 300 мс после речи
        private const val RELEASE_COEFF = 0.08f
        const val MAX_RELEASE_DB_PER_FRAME = 1.5f
        /** Приглушение пауз. */
        const val CLOSED_DB = -20f
    }
}
