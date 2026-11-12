package com.dpcall.app.signaling

object SignalingHelper {

    fun createOffer(
        sdp: String,
        candidates: List<String> = emptyList(),
        myIdentity: String? = null,
        callId: String? = null,
        secret: ByteArray? = null
    ): String {
        val payload = SdpPayload(
            sdp = sdp,
            candidates = candidates,
            identity = myIdentity,
            callId = callId
        )
        return SignalCodec.encode(SignalType.OFFER, payload, secret)
    }

    fun createAnswer(
        sdp: String,
        candidates: List<String> = emptyList(),
        myIdentity: String? = null,
        callId: String? = null,
        secret: ByteArray? = null
    ): String {
        val payload = SdpPayload(
            sdp = sdp,
            candidates = candidates,
            identity = myIdentity,
            callId = callId
        )
        return SignalCodec.encode(SignalType.ANSWER, payload, secret)
    }

    fun parse(raw: String): DecodedSignal {
        return SignalCodec.decode(raw).getOrThrow()
    }

    fun tryParse(raw: String): Result<DecodedSignal> = SignalCodec.decode(raw)
}
