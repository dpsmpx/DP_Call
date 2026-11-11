package com.dpcall.app.webrtc

enum class CallState {
    IDLE,
    CREATING_OFFER,
    WAITING_FOR_ANSWER,
    CREATING_ANSWER,
    CONNECTING,
    CONNECTED,
    ENDED,
    FAILED
}
