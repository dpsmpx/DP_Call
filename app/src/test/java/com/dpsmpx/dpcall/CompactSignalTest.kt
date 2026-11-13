package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.signaling.*
import org.junit.Assert.*
import org.junit.Test

class CompactSignalTest {
    private val keys = IdentityGenerator.generate()
    private val payload = SdpPayload(
        sdp = listOf("v=0", "o=- 9123456789 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1",
            "a=extmap-allow-mixed", "a=msid-semantic: WMS unique-stream", "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 102 0 8 13 110 126",
            "c=IN IP4 0.0.0.0", "a=rtcp:9 IN IP4 0.0.0.0", "a=ice-ufrag:randomABC", "a=ice-pwd:randomPassword12345678901",
            "a=fingerprint:sha-256 " + (0..31).joinToString(":") { "%02X".format(it) }, "a=setup:actpass", "a=mid:0", "a=sendrecv", "a=rtcp-mux", "a=rtcp-rsize",
            "a=rtpmap:111 opus/48000/2", "a=fmtp:111 minptime=10;useinbandfec=1", "a=rtcp-fb:111 transport-cc", "a=rtpmap:63 red/48000/2", "a=fmtp:63 111/111",
            "a=rtpmap:9 G722/8000", "a=rtpmap:0 PCMU/8000", "a=rtpmap:8 PCMA/8000", "a=unknown-extension:preserve-me-exactly",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 0.0.0.0", "a=mid:1", "a=sctp-port:5000", "a=max-message-size:262144"
        ).joinToString("\r\n", postfix = "\r\n"),
        candidates = listOf("0|0|candidate:123 1 udp 2122260223 192.0.2.1 5000 typ host generation 0 ufrag randomABC", "0|0|candidate:456 1 udp 1686052607 198.51.100.1 6000 typ srflx raddr 192.0.2.1 rport 5000"),
        identity = keys.code, callId = "7c06b109-8209-47eb-b6ae-4ac6f9e81db8", ts = 1794600000000
    )
    @Test fun compactPreservesEveryFieldAndSignature() {
        for (type in SignalType.entries) {
            val old = SignalCodec.encode(type, payload, keys.secret)
            val compact = SignalCodec.compact(old)
            val decoded = SignalCodec.decode(compact).getOrThrow()
            assertEquals(SignalCodec.decode(old).getOrThrow(), decoded)
            assertTrue(decoded.verified)
            assertTrue("legacy=${old.length}, compact=${compact.length}", compact.length < old.length * 0.8)
            assertEquals(compact, SignalCodec.extract("DP Call 1.3.1+\n" + compact.chunked(67).joinToString("\n")))
            println("SIGNAL_SIZE legacy=${old.length} compact=${compact.length}")
        }
    }
    @Test fun compactRejectsTruncationGarbageAndWrongType() {
        val compact = SignalCodec.encode(SignalType.OFFER, payload, keys.secret, compact = true)
        assertTrue(SignalCodec.decode(compact.dropLast(8)).isFailure)
        assertFalse(SignalCodec.decode(compact.replace(".O.", ".A.")).getOrThrow().verified)
        assertTrue(SignalCodec.decode(compact.replace(".O.", ".Offer.")).isFailure)
        assertTrue(SignalCodec.decode(compact + "AAAA").isFailure)
    }
    @Test fun compactLimitsApplyToDecompressedContent() {
        val large = payload.copy(sdp = "a".repeat(300_000))
        assertTrue(runCatching { SignalCodec.encode(SignalType.OFFER, large, compact = true) }.isFailure)
    }
}
