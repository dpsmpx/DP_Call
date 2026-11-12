package com.dpsmpx.dpcall.webrtc

enum class CallState {
    IDLE,
    /** Получен Offer, ждём решения пользователя (принять / отклонить). */
    RINGING,
    CREATING_OFFER,
    WAITING_FOR_ANSWER,
    CREATING_ANSWER,
    CONNECTING,
    CONNECTED,
    ENDED,
    FAILED;

    /** Идёт звонок или его подготовка (нужен foreground-сервис и уведомление). */
    val isActive: Boolean
        get() = this != IDLE && this != ENDED && this != FAILED
}
