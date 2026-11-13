package com.dpsmpx.dpcall.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.dpsmpx.dpcall.BuildConfig
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.signaling.relay.RelayPool
import com.dpsmpx.dpcall.webrtc.IceServerConfig
import com.dpsmpx.dpcall.webrtc.PathProbe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.dpsmpx.dpcall.tor.TorProxy
import java.net.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Подробная проверка сети по кнопке: какие релеи отвечают и не замирают ли соединения с
 * ними, какие STUN-серверы отвечают, доступен ли TURN и сайт из «белых списков».
 * Результат — текст для отправки вручную; IP-адреса в него не попадают.
 */
object NetworkTest {

    enum class RelayResult { OK, FROZE, NO_CONNECTION, INCONCLUSIVE }

    data class Line(val label: String, val result: String, val good: Boolean?)

    /** Сколько данных запросить у релея: заведомо больше порога замирания (~16 КБ). */
    private const val RELAY_TARGET_BYTES = 30_000L
    private const val RELAY_STALL_MS = 5_000L
    private const val RELAY_TOTAL_MS = 15_000L
    private const val STUN_TIMEOUT_MS = 3_000

    /**
     * Итог проверки релея. Замирание: соединение было, данные шли и встали, не дойдя до
     * конца ответа. Маленький ответ не проверяет порог замирания: результат неизвестен.
     */
    fun classifyRelay(connected: Boolean, bytes: Long, completed: Boolean): RelayResult = when {
        !connected -> RelayResult.NO_CONNECTION
        bytes >= RELAY_TARGET_BYTES -> RelayResult.OK
        completed || bytes == 0L -> RelayResult.INCONCLUSIVE
        else -> RelayResult.FROZE
    }

    /** Запрос STUN Binding (RFC 5389): заголовок без атрибутов. */
    fun stunRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == 12)
        return byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + transactionId
    }

    /** Ответ — успешный Binding Response на наш запрос. */
    fun isStunResponse(data: ByteArray, length: Int, transactionId: ByteArray): Boolean =
        length >= 20 && data[0] == 0x01.toByte() && data[1] == 0x01.toByte() &&
            data[4] == 0x21.toByte() && data[5] == 0x12.toByte() && data[6] == 0xA4.toByte() && data[7] == 0x42.toByte() &&
            data.copyOfRange(8, 20).contentEquals(transactionId)

    /** Проводит все проверки (несколько десятков секунд), вызывать не с главного потока. */
    fun run(context: Context, onLine: (Line) -> Unit): String {
        if (com.dpsmpx.dpcall.carrier.CarrierTransport.active) return "Room mode: leave the room before testing public network services."
        val app = context.applicationContext
        val lines = mutableListOf<Line>()
        fun emit(line: Line) {
            lines += line
            onLine(line)
        }

        val settings = AppSettings.get(app)
        val selected = settings.relays.value
        val relays = (selected + if (selected == RelayPool.DEFAULT_RELAYS) RelayPool.SPARE_RELAYS else emptyList()).distinct()
        val viaTor = TorProxy.available && settings.torSignaling.value
        val proxy = if (viaTor) TorProxy.proxy() else null
        relays.forEach { url ->
            val (result, kb) = if (viaTor && proxy == null) RelayResult.NO_CONNECTION to 0L else testRelay(url, proxy)
            emit(
                Line(
                    url.removePrefix("wss://"),
                    when (result) {
                        RelayResult.OK -> "ok"
                        RelayResult.FROZE -> "froze after ~$kb KB"
                        RelayResult.NO_CONNECTION -> if (viaTor && proxy == null) "Tor not ready" else "no connection"
                        RelayResult.INCONCLUSIVE -> "small or empty response; freeze test inconclusive"
                    },
                    if (result == RelayResult.INCONCLUSIVE) null else result == RelayResult.OK
                )
            )
        }

        IceServerConfig.STUN_SERVERS.forEach { url ->
            val ms = testStun(url)
            emit(Line(url, ms?.let { "ok, $it ms" } ?: "no answer", ms != null))
        }

        val turnHost = IceServerConfig.TURN_HOST
        listOf(443, 80).forEach { port ->
            val ok = tcpReachable(turnHost, port)
            emit(Line("TURN $turnHost:$port (TCP)", if (ok) "reachable" else "unreachable", ok))
        }
        settings.turn.takeIf { it.isValid }?.let { own ->
            val host = com.dpsmpx.dpcall.webrtc.PathCheck.hostOf(own.url)
            val port = own.url.substringAfter(host).removePrefix("]").removePrefix(":").substringBefore('?').toIntOrNull()
                ?: if (own.url.startsWith("turns:")) 5349 else 3478
            val ok = tcpReachable(host, port)
            emit(Line("own TURN (TCP $port)", if (ok) "reachable" else "unreachable", ok))
        }
        PathProbe.result.value?.let { path ->
            emit(Line("ICE direct path (STUN)", if (path.direct) "yes" else "no", path.direct))
            emit(Line("ICE TURN relay", if (path.turn) "yes" else "no", path.turn))
        }

        val whitelisted = tcpReachable("ya.ru", 443)
        emit(Line("ya.ru (whitelisted site)", if (whitelisted) "reachable" else "unreachable", null))

        return buildReport(app, lines)
    }

    private fun buildReport(context: Context, lines: List<Line>): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:00 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return buildString {
            appendLine("DP Call network test")
            appendLine("App: $version (${if (BuildConfig.TOR) "tor" else "standard"}), Android ${Build.VERSION.RELEASE}")
            appendLine("Network: ${networkDescription(context)}")
            appendLine("Time: $time")
            appendLine()
            lines.forEach { appendLine("${it.label}: ${it.result}") }
        }
    }

    /** Тип сети и оператор — чтобы сравнивать блокировки у разных операторов. */
    private fun networkDescription(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val operator = try {
                    (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName
                } catch (e: Exception) {
                    null
                }
                "mobile" + (operator?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "other"
        }
    }

    /**
     * Подключается к релею и просит последние публичные записи — ответ заведомо больше
     * порога замирания. @return итог и сколько килобайт пришло.
     */
    private fun testRelay(url: String, proxy: Proxy? = null): Pair<RelayResult, Long> {
        val client = OkHttpClient.Builder()
            .connectTimeout(if (proxy == null) 10 else 30, TimeUnit.SECONDS)
            .apply { if (proxy != null) proxy(proxy) }
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val bytes = AtomicLong(0)
        val lastData = AtomicLong(System.currentTimeMillis())
        val connected = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                lastData.set(System.currentTimeMillis())
                webSocket.send("""["REQ","dpcall-test",{"kinds":[1],"limit":100}]""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastData.set(System.currentTimeMillis())
                val type = runCatching { (Json.parseToJsonElement(text) as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content }.getOrNull()
                if (type == "EOSE") { completed.set(true); done.countDown() }
                if (type == "EVENT" && bytes.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong()) >= RELAY_TARGET_BYTES) done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = done.countDown()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = done.countDown()
        })
        val started = System.currentTimeMillis()
        while (done.count > 0 && System.currentTimeMillis() - started < RELAY_TOTAL_MS) {
            // Данные перестали идти: соединение замерло (или релей не ответил вовсе).
            if (connected.get() && System.currentTimeMillis() - lastData.get() > RELAY_STALL_MS) break
            done.await(200, TimeUnit.MILLISECONDS)
        }
        socket.cancel()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        val result = classifyRelay(connected.get(), bytes.get(), completed.get())
        return result to bytes.get() / 1000
    }

    /** @return время ответа STUN-сервера в мс или null. */
    private fun testStun(url: String): Long? = try {
        val hostPort = url.removePrefix("stun:")
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toInt()
        DatagramSocket().use { socket ->
            socket.soTimeout = STUN_TIMEOUT_MS
            val id = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val request = stunRequest(id)
            val started = System.currentTimeMillis()
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName(host), port))
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (isStunResponse(buffer, packet.length, id)) System.currentTimeMillis() - started else null
        }
    } catch (e: Exception) {
        null
    }

    private fun tcpReachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 6_000); true }
    } catch (e: Exception) {
        false
    }
}
