package com.dpsmpx.dpcall.signaling.relay

import kotlinx.serialization.Serializable

/**
 * Сообщение сигнального канала (содержимое зашифрованного конверта).
 *
 * @property t тип, см. константы
 * @property id идентификатор звонка — общий для всех сообщений одного вызова
 * @property signal строка DPCALL1 (для [OFFER] и [ANSWER])
 * @property ts время отправки, мс — устаревшие сообщения отбрасываются
 */
@Serializable
data class CallMessage(
    val t: String,
    val id: String,
    val signal: String? = null,
    val ts: Long = System.currentTimeMillis()
) {
    companion object {
        const val OFFER = "offer"
        const val ANSWER = "answer"
        /** Телефон адресата звонит. */
        const val RINGING = "ringing"
        const val REJECT = "reject"
        const val BUSY = "busy"
        /** Вызывающий передумал до ответа. */
        const val CANCEL = "cancel"
        const val BYE = "bye"
        /** ICE-кандидаты, найденные после отправки offer/answer (по одному в строке). */
        const val CANDIDATES = "candidates"
        /** Просьба к вызывающему пересогласовать соединение (сменилась сеть). */
        const val RESTART = "restart"
        /** Новый Offer / Answer при пересогласовании. */
        const val RE_OFFER = "reoffer"
        const val RE_ANSWER = "reanswer"
    }
}
