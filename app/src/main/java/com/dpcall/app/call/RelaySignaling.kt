package com.dpcall.app.call

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dpcall.app.data.IdentityRepository
import com.dpcall.app.signaling.relay.CallMessage
import com.dpcall.app.signaling.relay.RelayMessenger
import com.dpcall.app.signaling.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RelayStatus(val connected: Int = 0, val total: Int = RelayPool.DEFAULT_RELAYS.size) {
    val online: Boolean get() = connected > 0
}

/**
 * Сигнальный канал на уровне процесса: держит соединения с релеями, пока жив
 * процесс (приложение открыто или работает [ListenService]), и передаёт
 * входящие сообщения в [CallManager].
 */
@SuppressLint("StaticFieldLeak") // хранится только applicationContext
object RelaySignaling {
    private const val TAG = "RelaySignaling"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Volatile
    private var messenger: RelayMessenger? = null

    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            try {
                val keys = IdentityRepository(app).loadKeys()
                val created = RelayMessenger(
                    keys = keys,
                    onMessage = { from, message -> main.post { CallManager.onRelayMessage(from, message) } },
                    onStatus = { connected, total -> _status.value = RelayStatus(connected, total) },
                    log = { Log.d(TAG, it) }
                )
                messenger = created
                created.start()
                watchNetwork(app)
            } catch (e: Throwable) {
                Log.e(TAG, "Relay signaling unavailable", e)
                started = false
            }
        }
    }

    private fun watchNetwork(app: Context) {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    messenger?.reconnectNow()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Network callback unavailable", e)
        }
    }

    /** Проверяет соединения (например, когда приложение вернулось на экран). */
    fun reconnectNow() {
        messenger?.reconnectNow()
    }

    /** @return сколько релеев приняли сообщение (0 — доставить сейчас нельзя). */
    fun send(toCode: String, message: CallMessage): Int = try {
        messenger?.send(toCode, message) ?: 0
    } catch (e: Exception) {
        Log.w(TAG, "send failed", e)
        0
    }
}
