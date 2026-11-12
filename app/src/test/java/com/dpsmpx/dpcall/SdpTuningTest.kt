package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.webrtc.SdpTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTuningTest {

    private fun sdp(fmtp: String?) = buildString {
        append("v=0\r\n")
        append("m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9\r\n")
        append("a=rtpmap:111 opus/48000/2\r\n")
        if (fmtp != null) append("a=fmtp:111 $fmtp\r\n")
        append("a=rtpmap:63 red/48000/2\r\n")
        append("a=fmtp:63 111/111\r\n")
        append("a=rtpmap:9 G722/8000\r\n")
    }

    private fun opusParams(sdp: String): Map<String, String> =
        sdp.split("\r\n").first { it.startsWith("a=fmtp:111 ") }
            .removePrefix("a=fmtp:111 ").split(';')
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun mergesIntoExistingFmtp() {
        val tuned = SdpTuning.tuneOpus(sdp("minptime=10;useinbandfec=0"))
        val params = opusParams(tuned)
        assertEquals("10", params["minptime"])
        assertEquals("1", params["useinbandfec"])
        assertEquals("48000", params["maxaveragebitrate"])
        assertEquals("0", params["usedtx"])
        // Остальные кодеки не тронуты.
        assertTrue(tuned.contains("a=fmtp:63 111/111\r\n"))
        assertEquals(sdp("x").split("\r\n").size, tuned.split("\r\n").size)
    }

    @Test
    fun addsFmtpWhenMissing() {
        val tuned = SdpTuning.tuneOpus(sdp(null))
        val lines = tuned.split("\r\n")
        val rtpmap = lines.indexOf("a=rtpmap:111 opus/48000/2")
        assertTrue(lines[rtpmap + 1].startsWith("a=fmtp:111 "))
        assertEquals("1", opusParams(tuned)["useinbandfec"])
    }

    @Test
    fun leavesSdpWithoutOpusUntouched() {
        val plain = "v=0\r\nm=audio 9 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\n"
        assertEquals(plain, SdpTuning.tuneOpus(plain))
    }
}
