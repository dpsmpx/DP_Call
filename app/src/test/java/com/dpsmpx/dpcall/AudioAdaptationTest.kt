package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.webrtc.BitrateController
import com.dpsmpx.dpcall.webrtc.HearQuality
import com.dpsmpx.dpcall.webrtc.HearingEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioAdaptationTest {

    @Test
    fun cleanChannelKeepsFullBitrate() {
        val c = BitrateController()
        repeat(20) { assertNull(c.onSample(0.0, 80.0)) }
        assertEquals(48_000, c.bitrate)
    }

    @Test
    fun moderateLossStepsDownAfterTwoSamples() {
        val c = BitrateController()
        assertNull(c.onSample(7.0, 100.0))
        assertEquals(32_000, c.onSample(7.0, 100.0))
        assertNull(c.onSample(7.0, 100.0))
        assertEquals(24_000, c.onSample(7.0, 100.0))
    }

    @Test
    fun severeLossStepsDownAtOnceAndBottomsOut() {
        val c = BitrateController()
        assertEquals(32_000, c.onSample(30.0, null))
        assertEquals(24_000, c.onSample(30.0, null))
        assertEquals(16_000, c.onSample(30.0, null))
        assertNull(c.onSample(30.0, null))
        assertEquals(16_000, c.bitrate)
    }

    @Test
    fun highRttCountsAsBad() {
        val c = BitrateController()
        c.onSample(0.0, 900.0)
        assertEquals(32_000, c.onSample(0.0, 900.0))
    }

    @Test
    fun recoversSlowlyAndOneStepAtATime() {
        val c = BitrateController()
        c.onSample(30.0, null)
        c.onSample(30.0, null)
        assertEquals(24_000, c.bitrate)
        repeat(4) { assertNull(c.onSample(0.0, 50.0)) }
        assertEquals(32_000, c.onSample(0.0, 50.0))
        // Один плохой замер сбрасывает накопленное «хорошо».
        repeat(4) { c.onSample(0.0, 50.0) }
        c.onSample(6.0, 50.0)
        repeat(4) { assertNull(c.onSample(0.0, 50.0)) }
        assertEquals(48_000, c.onSample(0.0, 50.0))
    }

    @Test
    fun hearingFromConcealment() {
        assertEquals(HearQuality.GOOD, HearingEstimate.of(0.5, true))
        assertEquals(HearQuality.FAIR, HearingEstimate.of(5.0, true))
        assertEquals(HearQuality.POOR, HearingEstimate.of(20.0, true))
        assertEquals(HearQuality.POOR, HearingEstimate.of(null, false))
        assertEquals(HearQuality.UNKNOWN, HearingEstimate.of(null, true))
    }
}
