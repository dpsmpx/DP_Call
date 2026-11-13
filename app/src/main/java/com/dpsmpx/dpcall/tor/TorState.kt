package com.dpsmpx.dpcall.tor

/** Состояние встроенного Tor (только в сборке tor; в обычной всегда [OFF]). */
enum class TorState { OFF, STARTING, READY, FAILED }
