package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.webrtc.ReceiveNoiseGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ReceiveNoiseGateTest {

    private val frames = 480 // 10 мс при 48 кГц
    private val random = Random(42)

    private fun frame(generator: (Int) -> Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) buffer.putFloat(i * 4, generator(i))
        return buffer
    }

    private fun rms(buffer: ByteBuffer): Double {
        var sum = 0.0
        for (i in 0 until frames) {
            val s = buffer.getFloat(i * 4)
            sum += s * s
        }
        return sqrt(sum / frames)
    }

    private fun noise(amplitude: Float) = frame { (random.nextFloat() * 2f - 1f) * amplitude }
    private fun speech(amplitude: Float) = frame { i -> (sin(2 * PI * 300 * i / 48000.0) * amplitude).toFloat() }

    @Test
    fun attenuatesSteadyBackgroundNoise() {
        val gate = ReceiveNoiseGate().apply { initialize(48000, 1) }
        var input = 0.0
        var output = 0.0
        repeat(300) { // 3 секунды ровного шума
            val buffer = noise(60f)
            input = rms(buffer)
            gate.process(1, frames, buffer)
            output = rms(buffer)
        }
        assertTrue("noise should be reduced by >16 dB, got ${output / input}", output < input * 0.16)
    }

    @Test
    fun passesSpeechUnchangedAndOpensInstantly() {
        val gate = ReceiveNoiseGate().apply { initialize(48000, 1) }
        repeat(300) { gate.process(1, frames, noise(60f)) }

        // Первый же кадр речи: к концу кадра усиление должно дойти до единицы.
        val first = speech(6000f)
        val reference = speech(6000f)
        gate.process(1, frames, first)
        assertEquals(reference.getFloat((frames - 1) * 4), first.getFloat((frames - 1) * 4), 1f)

        // Дальше речь проходит бит-в-бит.
        repeat(50) {
            val buffer = speech(6000f)
            val before = rms(buffer)
            gate.process(1, frames, buffer)
            assertEquals(before, rms(buffer), 1e-3)
        }
    }

    @Test
    fun holdsOpenThroughShortPauses() {
        val gate = ReceiveNoiseGate().apply { initialize(48000, 1) }
        repeat(300) { gate.process(1, frames, noise(60f)) }
        repeat(30) { gate.process(1, frames, speech(6000f)) }
        // Пауза 100 мс между словами не должна приглушаться.
        repeat(10) {
            val buffer = noise(60f)
            val before = rms(buffer)
            gate.process(1, frames, buffer)
            assertEquals(before, rms(buffer), 1e-3)
        }
    }

    @Test
    fun neverAmplifiesAndHandlesSilence() {
        val gate = ReceiveNoiseGate().apply { initialize(48000, 1) }
        repeat(200) { n ->
            val buffer = if (n % 3 == 0) frame { 0f } else noise(random.nextFloat() * 20000f)
            val before = rms(buffer)
            gate.process(3, frames / 3, buffer)
            val after = rms(buffer)
            assertTrue(after <= before + 1e-3)
            assertTrue(!after.isNaN())
        }
    }

    /** Долгая тихая речь на фоне шума: оценка шума не должна дорасти до речи и начать её глушить. */
    @Test
    fun longQuietSpeechIsNotEatenAway() {
        val gate = ReceiveNoiseGate().apply { initialize(48000, 1) }
        repeat(300) { gate.process(1, frames, noise(60f)) }
        // 10 секунд речи всего на ~12 дБ громче шума.
        repeat(1000) { n ->
            val buffer = frame { i -> (sin(2 * PI * 300 * i / 48000.0) * 300).toFloat() + (random.nextFloat() * 2f - 1f) * 60f }
            val before = rms(buffer)
            gate.process(1, frames, buffer)
            if (n > 10) assertEquals("frame $n", before, rms(buffer), before * 1e-3)
        }
    }

    /** Закрывается плавно: без резких провалов громкости между кадрами («дыхания»). */
    @Test
    fun closesSmoothly() {
        val gate = ReceiveNoiseGate().apply { initialize(48000, 1) }
        repeat(300) { gate.process(1, frames, noise(60f)) }
        repeat(30) { gate.process(1, frames, speech(6000f)) }
        val ratios = mutableListOf<Double>()
        repeat(150) {
            val buffer = frame { 1000f }
            gate.process(1, frames, buffer)
            // Постоянный сигнал — отношение последнего сэмпла к исходному и есть усиление.
            ratios += (buffer.getFloat((frames - 1) * 4) / 1000f).toDouble()
        }
        val maxDropDb = ratios.zipWithNext { a, b -> 20 * kotlin.math.log10(b / a) }.minOrNull() ?: 0.0
        assertTrue("drop per frame ${maxDropDb} dB", maxDropDb >= -ReceiveNoiseGate.MAX_RELEASE_DB_PER_FRAME - 0.01)
    }
}
