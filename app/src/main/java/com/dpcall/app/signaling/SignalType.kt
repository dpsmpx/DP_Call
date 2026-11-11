package com.dpcall.app.signaling

enum class SignalType(val code: Char) {
    OFFER('O'),
    ANSWER('A');

    companion object {
        fun fromCode(c: Char): SignalType? = entries.find { it.code == c }
    }
}
