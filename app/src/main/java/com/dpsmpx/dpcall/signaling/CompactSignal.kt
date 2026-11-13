package com.dpsmpx.dpcall.signaling

import com.dpsmpx.dpcall.signaling.relay.RelayCrypto.hexToBytes
import com.dpsmpx.dpcall.signaling.relay.RelayCrypto.toHex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** DPCALL2: lossless binary fields + DEFLATE with an immutable SDP dictionary. */
internal object CompactSignal {
    private const val MAX_BYTES = 256 * 1024
    // Part of the wire format: never change this dictionary without a new format version.
    private val dictionary = listOf(
        "v=0", "o=-  IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1",
        "a=extmap-allow-mixed", "a=msid-semantic: WMS", "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 102 0 8 13 110 126",
        "c=IN IP4 0.0.0.0", "a=rtcp:9 IN IP4 0.0.0.0", "a=ice-ufrag:", "a=ice-pwd:", "a=ice-options:trickle renomination",
        "a=fingerprint:sha-256 ", "a=setup:actpass", "a=setup:active", "a=mid:0", "a=sendrecv", "a=rtcp-mux", "a=rtcp-rsize",
        "a=rtpmap:111 opus/48000/2", "a=rtcp-fb:111 transport-cc", "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2", "a=fmtp:63 111/111", "a=rtpmap:9 G722/8000", "a=rtpmap:0 PCMU/8000", "a=rtpmap:8 PCMA/8000",
        "a=ssrc: cname:", "a=msid:", "a=candidate:", " 1 udp  192.168.  typ host generation 0 ufrag  network-cost 999",
        " typ srflx raddr  rport ", " typ relay raddr  rport ", "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
        "a=mid:1", "a=sctp-port:5000", "a=max-message-size:262144", "a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level",
        "a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time", "a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"
    ).joinToString("\r\n", postfix = "\r\n").toByteArray(Charsets.UTF_8)

    fun pack(p: SdpPayload): ByteArray {
        require(p.candidates.size <= SignalCodec.MAX_CANDIDATES)
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out ->
            fun field(value: String?) {
                if (value == null) { out.writeInt(-1); return }
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeLong(p.ts)
            field(p.sdp)
            out.writeInt(p.candidates.size)
            p.candidates.forEach(::field)
            field(p.identity)
            field(p.callId)
            val sig = p.sig?.hexToBytes()
            require(p.sig == null || sig?.size == 64)
            out.writeBoolean(sig != null)
            if (sig != null) out.write(sig)
        }
        require(raw.size() <= MAX_BYTES)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setDictionary(dictionary)
            deflater.setInput(raw.toByteArray())
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally { deflater.end() }
    }

    fun unpack(compressed: ByteArray): SdpPayload {
        val inflater = Inflater(true)
        val raw = try {
            inflater.setDictionary(dictionary)
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                require(n > 0 && out.size() + n <= MAX_BYTES) { "Invalid or oversized compact signal" }
                out.write(buffer, 0, n)
            }
            require(inflater.remaining == 0) { "Trailing compact data" }
            out.toByteArray()
        } finally { inflater.end() }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            fun field(): String? {
                val size = input.readInt()
                if (size == -1) return null
                require(size in 0..input.available())
                return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
            }
            val ts = input.readLong()
            val sdp = requireNotNull(field())
            val count = input.readInt()
            require(count in 0..SignalCodec.MAX_CANDIDATES)
            val candidates = List(count) { requireNotNull(field()) }
            val identity = field()
            val callId = field()
            val sig = if (input.readBoolean()) ByteArray(64).also(input::readFully).toHex() else null
            require(input.available() == 0)
            SdpPayload(sdp, candidates, identity, ts, callId, sig)
        }
    }
}
