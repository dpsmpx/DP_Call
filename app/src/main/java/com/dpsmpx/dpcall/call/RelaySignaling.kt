package com.dpsmpx.dpcall.call

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.data.IdentityRepository
import com.dpsmpx.dpcall.util.AppLog
import com.dpsmpx.dpcall.webrtc.PathProbe
import com.dpsmpx.dpcall.signaling.relay.CallMessage
import com.dpsmpx.dpcall.signaling.relay.RelayMessenger
import com.dpsmpx.dpcall.signaling.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.dpsmpx.dpcall.tor.TorProxy
import com.dpsmpx.dpcall.tor.TorState
import com.dpsmpx.dpcall.carrier.CarrierTransport

data class RelayStatus(
    val connected: Int = 0,
    val total: Int = RelayPool.DEFAULT_RELAYS.size,
    /** Состояние Tor, если сигнальный канал идёт через него; null — напрямую. */
    val tor: TorState? = null
) {
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
    @Volatile private var generation = 0
    private var networkWatched = false

    @Volatile
    private var messenger: RelayMessenger? = null

    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private var startJob: Job? = null
    private var torWatch: Job? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val gen = generation
        if (CarrierTransport.active) {
            carrierStatusChanged(CarrierTransport.ready)
            return
        }
        val app = context.applicationContext
        startJob = scope.launch {
            try {
                val keys = IdentityRepository(app).loadKeys()
                val relays = AppSettings.get(app).relays.value
                val viaTor = TorProxy.available && AppSettings.get(app).torSignaling.value
                synchronized(this@RelaySignaling) {
                    if (gen != generation || CarrierTransport.active) return@launch
                    _status.value = RelayStatus(0, relays.size, if (viaTor) TorState.STARTING else null)
                }
                var proxy: java.net.Proxy? = null
                if (viaTor) {
                    // Без Tor к релеям не подключаемся вовсе: иначе они увидят настоящий IP.
                    synchronized(this@RelaySignaling) {
                        if (gen != generation || CarrierTransport.active) return@launch
                        TorProxy.start(app)
                        torWatch?.cancel()
                        torWatch = launch {
                            TorProxy.state.collect { tor ->
                                synchronized(this@RelaySignaling) {
                                    if (gen == generation && !CarrierTransport.active) _status.update { it.copy(tor = tor) }
                                }
                            }
                        }
                    }
                    TorProxy.state.first { it == TorState.READY }
                    proxy = TorProxy.proxy() ?: error("Tor proxy unavailable")
                }
                val created = RelayMessenger(
                    keys = keys,
                    relays = relays,
                    // Свой список релеев пользователь задаёт сознательно — запасные не добавляем.
                    spares = if (relays == RelayPool.DEFAULT_RELAYS) RelayPool.SPARE_RELAYS else emptyList(),
                    // Постоянная связь: обрыв замечается за ~25 с вместо ~40 с.
                    pingSec = if (AppSettings.get(app).hardBackground.value) 25 else 40,
                    proxy = proxy,
                    receiveMissed = AppSettings.get(app).missedNotices.value,
                    onMessage = { from, message -> main.post {
                        if (gen == generation && !CarrierTransport.active) CallManager.onRelayMessage(from, message)
                    } },
                    onStatus = { connected, total ->
                        synchronized(this@RelaySignaling) {
                            if (gen == generation && !CarrierTransport.active) {
                                if (_status.value.connected != connected) AppLog.add(TAG, "relays connected: $connected/$total")
                                _status.update { it.copy(connected = connected, total = total) }
                                NetworkRestriction.onRelayStatus(app, connected > 0, probeAllowed = !viaTor)
                            }
                        }
                    },
                    log = { AppLog.add(TAG, it) }
                )
                synchronized(this@RelaySignaling) {
                    if (gen != generation || CarrierTransport.active) {
                        created.stop()
                        return@launch
                    }
                    messenger = created
                    created.start()
                    if (!networkWatched) {
                        networkWatched = true
                        watchNetwork(app)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // перезапуск с новыми настройками
            } catch (e: Throwable) {
                Log.e(TAG, "Relay signaling unavailable", e)
                synchronized(this@RelaySignaling) { if (gen == generation) started = false }
            }
        }
    }

    private fun watchNetwork(app: Context) {
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    messenger?.reconnectNow()
                    PathProbe.markStale()
                    val gen = generation
                    main.post { if (gen == generation) CallManager.onNetworkChanged() }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Network callback unavailable", e)
        }
    }

    /** Переподключается с новыми настройками: список релеев, Tor, частота проверок. */
    @Synchronized
    fun restart(context: Context) {
        generation++
        startJob?.cancel()
        torWatch?.cancel()
        messenger?.stop()
        messenger = null
        if (CarrierTransport.active || !AppSettings.get(context).torSignaling.value) TorProxy.stop(context)
        NetworkRestriction.onRelayStatus(context, online = false, probeAllowed = false)
        _status.value = RelayStatus()
        started = false
        start(context)
    }

    /** Проверяет соединения (например, когда приложение вернулось на экран). */
    fun reconnectNow() {
        messenger?.reconnectNow()
    }

    /** Состояние каждого релея — для диагностики. */
    fun relayStates(): List<RelayPool.RelayState> = messenger?.relayStates() ?: emptyList()

    fun carrierStatusChanged(ready: Boolean) {
        if (CarrierTransport.active) _status.value = RelayStatus(if (ready) 1 else 0, 1)
    }

    /** @return сколько релеев приняли сообщение (0 — доставить сейчас нельзя). */
    fun send(toCode: String, message: CallMessage): Int = try {
        if (CarrierTransport.active) CarrierTransport.send(toCode, message) else messenger?.send(toCode, message) ?: 0
    } catch (e: Exception) {
        Log.w(TAG, "send failed", e)
        0
    }
}
