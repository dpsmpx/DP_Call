package com.dpsmpx.dpcall.tor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.util.AppLog
import org.dpcall.IPtProxy.Controller
import org.dpcall.IPtProxy.IPtProxy
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Встроенный Tor (сборка tor): запускает [TorService] в процессе приложения и даёт
 * SOCKS-прокси для соединений с релеями. Готовым считается, когда Tor построил цепочку.
 *
 * Пока Tor не готов, [proxy] возвращает null — и вызывающий код не должен идти в сеть
 * напрямую: иначе релеи увидят настоящий IP-адрес.
 */
object TorProxy {
    private const val TAG = "TorProxy"
    /** Сколько ждать первой цепочки, прежде чем сообщить о неудаче (ожидание продолжается). */
    private const val START_TIMEOUT_MS = 120_000L
    /** Через мосты (особенно snowflake и meek) цепочка строится дольше. */
    private const val START_TIMEOUT_BRIDGES_MS = 240_000L
    private const val BUILTIN_BRIDGES_ASSET = "builtin_bridges.txt"

    const val available = true

    private val _state = MutableStateFlow(TorState.OFF)
    val state: StateFlow<TorState> = _state.asStateFlow()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dpcall-tor").apply { isDaemon = true }
    }

    @Volatile
    private var service: TorService? = null
    @Volatile
    private var socksPort = -1
    private var connection: ServiceConnection? = null
    private var poll: ScheduledFuture<*>? = null
    private var startedAt = 0L
    private var timeoutMs = START_TIMEOUT_MS
    /** Способы маскировки мостов (Lyrebird, Snowflake), запущенные в процессе. */
    private var transports: Controller? = null
    private val runningTransports = mutableListOf<String>()

    @Synchronized
    fun start(context: Context) {
        if (connection != null) return
        val app = context.applicationContext
        _state.value = TorState.STARTING
        startedAt = System.currentTimeMillis()
        val bridges = try { configureBridges(app) } catch (e: Exception) {
            AppLog.add(TAG, "bridge configuration failed")
            stopTransports()
            _state.value = TorState.FAILED
            return
        }
        timeoutMs = if (bridges > 0) START_TIMEOUT_BRIDGES_MS else START_TIMEOUT_MS
        AppLog.add(TAG, "starting, bridges=$bridges")
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? TorService.LocalBinder)?.service
                schedulePoll(1)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                socksPort = -1
                if (connection != null) _state.value = TorState.FAILED
            }
        }
        connection = conn
        val bound = try {
            app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "bind failed", e)
            false
        }
        if (!bound) {
            connection = null
            stopTransports()
            _state.value = TorState.FAILED
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val conn = connection
        connection = null
        poll?.cancel(false)
        poll = null
        try {
            if (conn != null) context.applicationContext.unbindService(conn)
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        service = null
        socksPort = -1
        stopTransports()
        _state.value = TorState.OFF
        AppLog.add(TAG, "stopped")
    }

    fun proxy(): Proxy? {
        val port = socksPort
        return if (_state.value == TorState.READY && port > 0) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        } else {
            null
        }
    }

    /** Опрос готовности: порт SOCKS открыт и цепочка построена. Обращения к Tor — не на главном потоке. */
    private fun schedulePoll(delaySec: Long) {
        poll?.cancel(false)
        poll = executor.schedule(::check, delaySec, TimeUnit.SECONDS)
    }

    private fun check() {
        if (connection == null) return
        val tor = service
        val port = try {
            tor?.socksPort ?: -1
        } catch (e: Exception) {
            -1
        }
        val circuit = try {
            tor?.getInfo("status/circuit-established")
        } catch (e: Exception) {
            null
        }
        val ready = port > 0 && circuit?.trim() == "1"
        val previous = _state.value
        // Цепочка пропала (например, сменилась сеть) — отсчёт ожидания начинается заново.
        if (previous == TorState.READY && !ready) startedAt = System.currentTimeMillis()
        when {
            ready -> {
                socksPort = port
                _state.value = TorState.READY
            }
            System.currentTimeMillis() - startedAt > timeoutMs -> _state.value = TorState.FAILED
            else -> _state.value = TorState.STARTING
        }
        if (_state.value != previous) AppLog.add(TAG, "state ${_state.value}")
        // Пока не готов — часто, потом изредка: цепочка может пропасть при смене сети.
        schedulePoll(if (ready) 30 else 1)
    }

    /**
     * Мосты: в России прямые подключения к Tor заблокированы. Запускает нужные способы
     * маскировки (obfs4, webtunnel, snowflake, meek) и пишет torrc, который TorService
     * прочитает при старте. @return сколько мостов задано.
     */
    private fun configureBridges(context: Context): Int {
        val settings = AppSettings.get(context)
        val bridges = when (settings.torBridgesMode.value) {
            TorBridges.Mode.BUILTIN -> try {
                TorBridges.parse(context.assets.open(BUILTIN_BRIDGES_ASSET).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                emptyList()
            }
            TorBridges.Mode.CUSTOM -> TorBridges.parse(settings.torCustomBridges)
            TorBridges.Mode.NONE -> emptyList()
        }
        stopTransports()
        require(settings.torBridgesMode.value == TorBridges.Mode.NONE || bridges.isNotEmpty()) { "No valid bridges" }
        val ports = mutableMapOf<String, Long>()
        if (bridges.isNotEmpty()) {
            val controller = transports ?: IPtProxy.newController(
                File(context.filesDir, "pt").apply { mkdirs() }.absolutePath, false, false, "ERROR", null
            ).also { transports = it }
            for (transport in TorBridges.transportsOf(bridges)) {
                try {
                    controller.start(transport, "")
                    val port = controller.port(transport)
                    if (port > 0) {
                        ports[transport] = port
                        runningTransports += transport
                    }
                } catch (e: Exception) {
                    AppLog.add(TAG, "transport $transport failed: $e")
                }
            }
        }
        require(bridges.isEmpty() || ports.isNotEmpty()) { "No bridge transport started" }
        TorService.getTorrc(context).apply { parentFile?.mkdirs() }.writeText(TorBridges.torrc(bridges, ports))
        return if (ports.isEmpty()) 0 else bridges.count { ports.containsKey(it.substringBefore(' ')) }
    }

    private fun stopTransports() {
        val controller = transports ?: return
        runningTransports.forEach { transport ->
            try {
                controller.stop(transport)
            } catch (e: Exception) {
                AppLog.add(TAG, "transport $transport stop: $e")
            }
        }
        runningTransports.clear()
    }
}
