package com.dpcall.app

import com.dpcall.app.domain.IdentityGenerator
import com.dpcall.app.signaling.SdpPayload
import com.dpcall.app.signaling.SignalCodec
import com.dpcall.app.signaling.SignalType
import com.dpcall.app.util.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

class SignalSecurityTest {

    private val alice = IdentityGenerator.generate()
    private val eve = IdentityGenerator.generate()
    private val payload = SdpPayload(
        sdp = "v=0\r\na=fingerprint:sha-256 AA:BB\r\n",
        candidates = listOf("0|0|candidate:1 1 udp 1 192.0.2.1 5000 typ host"),
        identity = alice.code,
        callId = "call-1"
    )

    private fun pack(type: Char, json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
        return "DPCALL1.$type." + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }

    @Test
    fun signedSignalVerifies() {
        val decoded = SignalCodec.decode(SignalCodec.encode(SignalType.OFFER, payload, alice.secret)).getOrThrow()
        assertTrue(decoded.verified)
        assertEquals(alice.code, decoded.payload.identity)
    }

    @Test
    fun unsignedSignalIsNotVerified() {
        assertFalse(SignalCodec.decode(SignalCodec.encode(SignalType.OFFER, payload)).getOrThrow().verified)
    }

    /** Подделка имени: Ева подписывает своим ключом, но указывает код Алисы. */
    @Test
    fun signatureByAnotherKeyIsRejected() {
        val forged = SignalCodec.encode(SignalType.OFFER, payload, eve.secret)
        assertFalse(SignalCodec.decode(forged).getOrThrow().verified)
    }

    @Test
    fun anyChangedFieldBreaksTheSignature() {
        val signed = SignalCodec.decode(SignalCodec.encode(SignalType.OFFER, payload, alice.secret)).getOrThrow().payload
        val json = Json { encodeDefaults = true }
        val variants = listOf(
            signed.copy(sdp = signed.sdp.replace("AA:BB", "CC:DD")), // подмена отпечатка DTLS
            signed.copy(candidates = signed.candidates + "0|0|candidate:2 1 udp 1 198.51.100.7 6000 typ host"),
            signed.copy(candidates = emptyList()),
            signed.copy(callId = "call-2"),
            signed.copy(ts = signed.ts + 1),
            signed.copy(identity = eve.code)
        )
        variants.forEach { tampered ->
            assertFalse(SignalCodec.decode(pack('O', json.encodeToString(tampered))).getOrThrow().verified)
        }
        // Приглашение нельзя выдать за ответ: тип входит в подпись.
        assertFalse(SignalCodec.decode(pack('A', json.encodeToString(signed))).getOrThrow().verified)
        // Контроль: без изменений подпись сходится.
        assertTrue(SignalCodec.decode(pack('O', json.encodeToString(signed))).getOrThrow().verified)
    }

    /** Маленькая строка, распаковывающаяся в сотни мегабайт, должна отклоняться, а не ронять приложение. */
    @Test
    fun gzipBombIsRejected() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            val chunk = ByteArray(1024 * 1024)
            repeat(200) { gz.write(chunk) }
        }
        val bomb = "DPCALL1.O." + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
        assertTrue(bomb.length < SignalCodec.MAX_SIGNAL_CHARS * 8)
        assertTrue(SignalCodec.decode(bomb.take(SignalCodec.MAX_SIGNAL_CHARS)).isFailure)
        val smallBomb = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gz -> repeat(8) { gz.write(ByteArray(1024 * 1024)) } }
        }
        val packed = "DPCALL1.O." + Base64.getUrlEncoder().withoutPadding().encodeToString(smallBomb.toByteArray())
        assertTrue(packed.length < SignalCodec.MAX_SIGNAL_CHARS)
        assertTrue(SignalCodec.decode(packed).isFailure)
    }

    @Test
    fun oversizedInputAndTooManyCandidatesAreRejected() {
        assertTrue(SignalCodec.decode("DPCALL1.O." + "A".repeat(SignalCodec.MAX_SIGNAL_CHARS)).isFailure)
        val many = payload.copy(candidates = List(SignalCodec.MAX_CANDIDATES + 1) { "0|0|c$it" })
        assertTrue(SignalCodec.decode(SignalCodec.encode(SignalType.OFFER, many, alice.secret)).isFailure)
    }

    @Test
    fun logScrubberRemovesAddresses() {
        val line = "Failed to connect to relay.example/203.0.113.9:443 from /10.61.202.185 (port 41382), v6 2001:db8:0:1::5"
        val clean = AppLog.scrub(line)
        assertFalse(clean.contains("203.0.113.9"))
        assertFalse(clean.contains("10.61.202.185"))
        assertFalse(clean.contains("2001:db8"))
        assertTrue(clean.contains("relay.example"))
        // Время и обычный текст не задеваются.
        assertEquals("09:38:04.511 state=CONNECTED rtt=221ms", AppLog.scrub("09:38:04.511 state=CONNECTED rtt=221ms"))
    }
}
