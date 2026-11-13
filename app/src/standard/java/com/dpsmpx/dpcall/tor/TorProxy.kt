package com.dpsmpx.dpcall.tor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Proxy

/** Обычная сборка: Tor не встроен. Интерфейс совпадает со сборкой tor. */
object TorProxy {
    const val available = false
    val state: StateFlow<TorState> = MutableStateFlow(TorState.OFF)
    fun start(context: Context) {}
    fun stop(context: Context) {}
    fun proxy(): Proxy? = null
}
