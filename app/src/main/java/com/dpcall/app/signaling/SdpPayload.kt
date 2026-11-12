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
    val ts: Long = System.currentTimeMillis(),
    /** Идентификатор звонка: связывает приглашение и ответ, пришедшие разными путями. */
    val callId: String? = null,
    /**
     * Подпись Шнорра (hex) ключом отправителя [identity] над остальными полями.
     * Обязательна при ручной передаче: только она доказывает, от кого сигнал.
     */
    val sig: String? = null
)
