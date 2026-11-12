package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.signaling.SdpPayload
import com.dpsmpx.dpcall.signaling.SignalCodec
import com.dpsmpx.dpcall.signaling.SignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCodecTest {

    private val payload = SdpPayload(
        sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n" + "a=candidate:1 1 udp 1 10.0.0.1 5000 typ host\r\n".repeat(20),
        candidates = listOf("0|0|candidate:1 1 udp 1 10.0.0.1 5000 typ host"),
        identity = "ABCDEFGHJKMNPQRSTVWXYZ0123"
    )

    @Test
    fun roundTrip() {
        val encoded = SignalCodec.encode(SignalType.OFFER, payload)
        assertTrue(encoded.startsWith("DPCALL1.O."))
        val decoded = SignalCodec.decode(encoded).getOrThrow()
        assertEquals(SignalType.OFFER, decoded.type)
        assertEquals(payload, decoded.payload)
    }

    @Test
    fun decodesPaddedAndWrappedInput() {
        val encoded = SignalCodec.encode(SignalType.ANSWER, payload)
        val wrapped = encoded.chunked(40).joinToString("\n") + "=="
        assertEquals(payload, SignalCodec.decode(wrapped).getOrThrow().payload)
    }

    @Test
    fun extractsSignalFromSurroundingText() {
        val encoded = SignalCodec.encode(SignalType.OFFER, payload)
        assertEquals(encoded, SignalCodec.extract("Позвони мне:\n$encoded\nжду!"))
        assertEquals(encoded, SignalCodec.extract("call me $encoded thanks"))
    }

    @Test
    fun extractsSignalBrokenByLineWraps() {
        val encoded = SignalCodec.encode(SignalType.OFFER, payload)
        val wrapped = encoded.chunked(64).joinToString("\n")
        assertEquals(encoded, SignalCodec.extract(wrapped))
    }

    @Test
    fun extractReturnsNullForPlainText() {
        assertNull(SignalCodec.extract("просто сообщение без кода"))
    }

    @Test
    fun rejectsGarbage() {
        assertTrue(SignalCodec.decode("DPCALL1.O.!!!!").isFailure)
        assertTrue(SignalCodec.decode("DPCALL2.O.abcd").isFailure)
        assertTrue(SignalCodec.decode("hello").isFailure)
    }
}
