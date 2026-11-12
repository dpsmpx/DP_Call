package com.dpcall.app.signaling.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Постоянные соединения с несколькими независимыми релеями Nostr.
 *
 * Подписывается на события для нашего «почтового ящика» и публикует исходящие
 * во все подключённые релеи сразу: отказ или блокировка части релеев не мешает.
 * Потерянные соединения восстанавливаются с нарастающей задержкой.
 *
 * Колбэки вызываются из служебного потока пула.
 */
class RelayPool(
    private val urls: List<String>,
    private val inboxTag: String,
    private val kind: Int,
    private val onEvent: (NostrEvent) -> Unit,
    private val onStatus: (connected: Int, total: Int) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(40, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dpcall-relays").apply { isDaemon = true }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val links = urls.map { Link(it) }
    private val seenIds = LinkedHashSet<String>()

    @Volatile
    private var running = false

    fun start() = executor.execute {
        if (running) return@execute
        running = true
        links.forEach { it.connect() }
    }

    fun stop() = executor.execute {
        running = false
        links.forEach { it.close() }
        notifyStatus()
    }

    /** Сеть появилась или сменилась — переподключаем оборванное без ожидания. */
    fun reconnectNow() = executor.execute {
        if (!running) return@execute
        // Соединение, которое уже устанавливается, не трогаем — иначе оно обрывается на полпути.
        links.forEach { if (!it.open && !it.isConnecting) it.connect(resetBackoff = true) }
    }

    /** Отправляет событие во все подключённые релеи. @return сколько релеев его получили. */
    fun publish(event: NostrEvent): Int {
        val message = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(event.toJson())
        }.toString()
        return links.count { it.send(message) }
    }

    val connectedCount: Int get() = links.count { it.open }

    private fun notifyStatus() = onStatus(connectedCount, links.size)

    private fun handleMessage(url: String, text: String) {
        val message = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return
        val type = (message.firstOrNull() as? JsonPrimitive)?.content ?: return
        if (type != "EVENT") {
            // OK с отказом, NOTICE, CLOSED, AUTH — полезно видеть в журнале.
            if (type != "EOSE" && !(type == "OK" && message.getOrNull(2)?.toString() == "true")) {
                log("$url: ${text.take(200)}")
            }
            return
        }
        if (message.size < 3) return
        val event = NostrEvent.fromJson(message[2]) ?: return
        if (event.kind != kind || event.firstTag("p") != inboxTag) return
        // Одно и то же событие приходит от нескольких релеев.
        if (!seenIds.add(event.id)) return
        if (seenIds.size > 500) seenIds.iterator().let { it.next(); it.remove() }
        if (!event.isValid()) return
        onEvent(event)
    }

    private inner class Link(val url: String) : WebSocketListener() {
        @Volatile
        private var socket: WebSocket? = null
        private var connecting = false
        private var retry: ScheduledFuture<*>? = null
        private var backoffSec = 2L

        @Volatile
        var open = false
            private set

        fun connect(resetBackoff: Boolean = false) {
            if (resetBackoff) backoffSec = 2L
            retry?.cancel(false)
            retry = null
            socket?.cancel()
            open = false
            connecting = true
            socket = try {
                client.newWebSocket(Request.Builder().url(url).build(), this)
            } catch (e: Exception) {
                scheduleRetry()
                null
            }
        }

        val isConnecting: Boolean get() = connecting

        fun close() {
            connecting = false
            retry?.cancel(false)
            retry = null
            socket?.close(1000, null)
            socket = null
            open = false
        }

        fun send(message: String): Boolean = open && (socket?.send(message) ?: false)

        private fun scheduleRetry() {
            if (!running || retry != null) return
            val delay = backoffSec
            backoffSec = (backoffSec * 2).coerceAtMost(60)
            retry = executor.schedule({
                retry = null
                if (running) connect()
            }, delay, TimeUnit.SECONDS)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            executor.execute {
                if (webSocket !== socket) return@execute
                connecting = false
                open = true
                backoffSec = 2L
                val request = buildJsonArray {
                    add(JsonPrimitive("REQ"))
                    add(JsonPrimitive("dpcall"))
                    add(buildJsonObject {
                        putJsonArray("kinds") { add(JsonPrimitive(kind)) }
                        putJsonArray("#p") { add(JsonPrimitive(inboxTag)) }
                        put("since", System.currentTimeMillis() / 1000 - 60)
                    })
                }
                webSocket.send(request.toString())
                notifyStatus()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            executor.execute { if (webSocket === socket) handleMessage(url, text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onLost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("$url: ${response?.code ?: ""} $t".take(200))
            onLost(webSocket)
        }

        private fun onLost(webSocket: WebSocket) {
            executor.execute {
                if (webSocket !== socket) return@execute
                connecting = false
                socket = null
                open = false
                notifyStatus()
                scheduleRetry()
            }
        }
    }

    companion object {
        /**
         * Публичные релеи без регистрации, независимые операторы.
         * Каждый проверен: пересылает наши эфемерные события между двумя
         * соединениями (см. LiveRelayTest). Политика публичных релеев может
         * меняться — при правке списка перезапустите этот тест.
         */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.snort.social",
            "wss://nostr.oxtr.dev",
            "wss://relay.nostr.net",
            "wss://nostr.sathoarder.com"
        )
    }
}
