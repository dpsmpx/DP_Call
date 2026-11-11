package com.dpcall.app.signaling

import kotlinx.serialization.Serializable

/**
 * Данные, которые упаковываются в DPCALL1-строку.
 */
@Serializable
data class SdpPayload(
    val sdp: String,
    val candidates: List<String> = emptyList(),
    val identity: String? = null,
    val ts: Long = System.currentTimeMillis()
)
