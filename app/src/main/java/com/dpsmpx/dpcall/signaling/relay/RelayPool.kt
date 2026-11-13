package com.dpsmpx.dpcall.signaling.relay

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
 * Релей, который раз за разом обрывается, отказывается принимать события или не
 * подтверждает их, временно отстраняется ([RelayHealth]); если рабочих основных
 * становится меньше [target], подключаются запасные из [spares].
 *
 * Колбэки вызываются из служебного потока пула.
 */
class RelayPool(
    private val urls: List<String>,
    private val spares: List<String> = emptyList(),
    private val target: Int = TARGET_RELAYS,
    /** Как часто проверять, живо ли соединение; реже — экономнее, чаще — быстрее замечается обрыв. */
    private val pingSec: Long = 40,
    /** SOCKS-прокси (Tor); имена релеев тогда разрешает сам прокси, без утечки DNS. */
    proxy: java.net.Proxy? = null,
    private val inboxTag: String,
    private val kind: Int,
    /** Хранимые события, которые нужно забрать за последние [storedWindowSec] секунд; null — не нужны. */
    private val storedKind: Int? = null,
    private val storedWindowSec: Long = 0,
    private val onEvent: (NostrEvent) -> Unit,
    private val onStatus: (connected: Int, total: Int) -> Unit,
    private val log: (String) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(pingSec, TimeUnit.SECONDS)
        // Через Tor соединение устанавливается дольше.
        .connectTimeout(if (proxy != null) 45 else 15, TimeUnit.SECONDS)
        .apply { if (proxy != null) proxy(proxy) }
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dpcall-relays").apply { isDaemon = true }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val links = (urls + spares.filterNot { it in urls }).distinct().map { Link(it, spare = it !in urls) }
    private val seenIds = LinkedHashSet<String>()
    private var evaluation: ScheduledFuture<*>? = null

    @Volatile
    private var running = false

    fun start() = executor.execute {
        if (running) return@execute
        running = true
        evaluate()
        evaluation = executor.scheduleWithFixedDelay(::evaluate, EVALUATE_SEC, EVALUATE_SEC, TimeUnit.SECONDS)
    }

    fun stop() = executor.execute {
        running = false
        evaluation?.cancel(false)
        evaluation = null
        links.forEach { it.deactivate() }
        notifyStatus()
    }

    /** Сеть появилась или сменилась — переподключаем оборванное без ожидания. */
    fun reconnectNow() = executor.execute {
        if (!running) return@execute
        // Соединение, которое уже устанавливается, не трогаем — иначе оно обрывается на полпути.
        links.forEach { if (it.active && !it.open && !it.isConnecting) it.connect(resetBackoff = true) }
    }

    /** Отправляет событие во все подключённые релеи. @return сколько релеев его получили. */
    fun publish(event: NostrEvent): Int {
        val message = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(event.toJson())
        }.toString()
        val sent = links.filter { it.send(message) }
        if (sent.isNotEmpty()) executor.execute { sent.forEach { it.awaitAck(event.id) } }
        return sent.size
    }

    val connectedCount: Int get() = links.count { it.active && it.open }

    /** Состояние каждого релея — для диагностики. */
    fun snapshot(): List<RelayState> {
        val now = System.currentTimeMillis()
        return links.map {
            RelayState(it.url, it.spare, it.active, it.open, it.health.isBenched(now), it.health.accepted)
        }
    }

    private fun notifyStatus() = onStatus(connectedCount, links.count { it.active })

    /** Подключает нужные релеи и отключает лишние. Вызывается из потока пула. */
    private fun evaluate() {
        if (!running) return
        // Ответы на проверки связи тоже идут от сервера и расходуют лимит соединения.
        links.forEach { if (it.open) it.countReceived(PONG_BYTES * EVALUATE_SEC / pingSec) }
        val now = System.currentTimeMillis()
        val chosen = RelayHealth.choose(urls, spares, target) { url ->
            links.first { it.url == url }.health.isBenched(now)
        }.toSet()
        var changed = false
        links.forEach { link ->
            val want = link.url in chosen
            if (want && !link.active) {
                if (link.spare || link.everActive) {
                    log("${link.url}: ${if (link.spare) "spare relay engaged" else "back in rotation"}")
                }
                link.activate()
                changed = true
            } else if (!want && link.active) {
                link.deactivate()
                changed = true
            }
        }
        if (changed) notifyStatus()
    }

    /** Релей подвёл несколько раз подряд: отстраняем, если без него есть чем работать. */
    private fun checkHealth(link: Link) {
        if (!link.health.failing) return
        // Без сети подводят все релеи сразу — тогда никого не отстраняем.
        if (links.none { it !== link && it.active && it.open }) return
        link.health.bench(System.currentTimeMillis())
        log("${link.url}: benched until ${link.health.benchedUntil}")
        evaluate()
    }

    private fun handleMessage(url: String, text: String) {
        val message = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return
        val type = (message.firstOrNull() as? JsonPrimitive)?.content ?: return
        if (type == "OK") {
            val id = (message.getOrNull(1) as? JsonPrimitive)?.content
            val ok = (message.getOrNull(2) as? JsonPrimitive)?.content == "true"
            links.firstOrNull { it.url == url }?.let { link ->
                if (id != null && link.pendingAcks.remove(id) != null) {
                    link.health.onAck(ok)
                    checkHealth(link)
                }
            }
        }
        if (type == "EOSE") {
            // Хранимое до этого момента получено: при переподключении спросим только новое.
            links.firstOrNull { it.url == url }?.storedSince = System.currentTimeMillis() / 1000 - STORED_OVERLAP_SEC
        }
        if (type != "EVENT") {
            // OK с отказом, NOTICE, CLOSED, AUTH — полезно видеть в журнале.
            if (type != "EOSE" && !(type == "OK" && message.getOrNull(2)?.toString() == "true")) {
                log("$url: ${text.take(200)}")
            }
            return
        }
        if (message.size < 3) return
        val event = NostrEvent.fromJson(message[2]) ?: return
        if ((event.kind != kind && event.kind != storedKind) || event.firstTag("p") != inboxTag) return
        // Одно и то же событие приходит от нескольких релеев.
        if (!seenIds.add(event.id)) return
        if (seenIds.size > 500) seenIds.iterator().let { it.next(); it.remove() }
        if (!event.isValid()) return
        onEvent(event)
    }

    private inner class Link(val url: String, val spare: Boolean) : WebSocketListener() {
        @Volatile
        private var socket: WebSocket? = null
        /** Новое соединение, которое заменит [socket], как только откроется. */
        private var next: WebSocket? = null
        private val retiring = mutableSetOf<WebSocket>()
        /** Сколько байт сервер прислал по текущему соединению (оценка, с TLS). */
        private var received = 0L
        private var lastRotation = 0L
        /** С какого момента запрашивать хранимые события (после первого EOSE). */
        var storedSince: Long? = null
        private var connecting = false
        private var retry: ScheduledFuture<*>? = null
        private var stable: ScheduledFuture<*>? = null
        private var backoffSec = 2L
        val health = RelayHealth()
        /** Отправленные события, на которые релей ещё не ответил OK. */
        val pendingAcks = HashMap<String, ScheduledFuture<*>>()

        @Volatile
        var open = false
            private set

        /** Релей сейчас в работе (выбран пулом). */
        @Volatile
        var active = false
            private set

        /** Уже работал раньше — значит, возвращается после отстранения. */
        var everActive = false
            private set

        fun activate() {
            everActive = true
            active = true
            connect(resetBackoff = true)
        }

        fun deactivate() {
            active = false
            close()
        }

        fun awaitAck(eventId: String) {
            pendingAcks.remove(eventId)?.cancel(false)
            if (pendingAcks.size >= MAX_PENDING_ACKS) return
            pendingAcks[eventId] = executor.schedule({
                if (pendingAcks.remove(eventId) != null && active) {
                    health.onAckTimeout()
                    checkHealth(this)
                }
            }, ACK_TIMEOUT_SEC, TimeUnit.SECONDS)
        }

        fun connect(resetBackoff: Boolean = false) {
            if (resetBackoff) backoffSec = 2L
            retry?.cancel(false)
            retry = null
            next?.cancel()
            next = null
            retiring.forEach { it.cancel() }
            retiring.clear()
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
            stable?.cancel(false)
            stable = null
            next?.cancel()
            next = null
            retiring.forEach { it.cancel() }
            retiring.clear()
            pendingAcks.values.forEach { it.cancel(false) }
            pendingAcks.clear()
            socket?.close(1000, null)
            socket = null
            open = false
        }

        fun send(message: String): Boolean = open && (socket?.send(message) ?: false)

        /**
         * В России соединения с Cloudflare, Hetzner, OVH и другими крупными хостингами
         * замирают, когда сервер прислал ~14–25 КБ: данные просто перестают приходить.
         * Поэтому заранее, на [ROTATE_BYTES], открываем новое соединение и закрываем старое,
         * только когда новое готово, — вызов не теряется.
         */
        fun countReceived(bytes: Long) {
            received += bytes
            val now = System.currentTimeMillis()
            if (received < ROTATE_BYTES || next != null || !open || now - lastRotation < MIN_ROTATION_MS) return
            lastRotation = now
            log("$url: rotating connection after ~${received / 1000} KB")
            next = try {
                client.newWebSocket(Request.Builder().url(url).build(), this)
            } catch (e: Exception) {
                null
            }
        }

        private fun subscribe(webSocket: WebSocket) {
            val now = System.currentTimeMillis() / 1000
            val request = buildJsonArray {
                add(JsonPrimitive("REQ"))
                add(JsonPrimitive("dpcall"))
                add(buildJsonObject {
                    putJsonArray("kinds") { add(JsonPrimitive(kind)) }
                    putJsonArray("#p") { add(JsonPrimitive(inboxTag)) }
                    put("since", now - 60)
                })
                if (storedKind != null) {
                    // То, что пришло, пока нас не было на связи; после первого раза — только новое.
                    add(buildJsonObject {
                        putJsonArray("kinds") { add(JsonPrimitive(storedKind)) }
                        putJsonArray("#p") { add(JsonPrimitive(inboxTag)) }
                        put("since", storedSince ?: (now - storedWindowSec))
                        put("limit", STORED_LIMIT)
                    })
                }
            }
            webSocket.send(request.toString())
        }

        private fun scheduleRetry() {
            if (!running || !active || retry != null) return
            val delay = backoffSec
            backoffSec = (backoffSec * 2).coerceAtMost(60)
            retry = executor.schedule({
                retry = null
                if (running && active) connect()
            }, delay, TimeUnit.SECONDS)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            executor.execute {
                if (webSocket === next) {
                    // Замена готова: подписываемся на новом соединении, старое закрываем чуть
                    // позже, чтобы не потерять то, что уже в пути.
                    val old = socket
                    if (old != null) retiring += old
                    socket = webSocket
                    next = null
                    received = HANDSHAKE_BYTES
                    subscribe(webSocket)
                    executor.schedule({ old?.close(1000, null); retiring.remove(old) }, 2, TimeUnit.SECONDS)
                    return@execute
                }
                if (webSocket !== socket) return@execute
                connecting = false
                open = true
                backoffSec = 2L
                received = HANDSHAKE_BYTES
                // Обрыв в первые минуты после подключения тоже считается неудачей.
                stable?.cancel(false)
                stable = executor.schedule({ if (open) health.onStable() }, STABLE_SEC, TimeUnit.SECONDS)
                subscribe(webSocket)
                notifyStatus()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            executor.execute {
                if (webSocket !== socket && webSocket !in retiring) return@execute
                handleMessage(url, text)
                if (webSocket === socket) countReceived((text.toByteArray(Charsets.UTF_8).size + FRAME_BYTES).toLong())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            onLost(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onLost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("$url: ${response?.code ?: ""} $t".take(200))
            onLost(webSocket)
        }

        private fun onLost(webSocket: WebSocket) {
            executor.execute {
                if (webSocket === next) {
                    // Замена не удалась — работаем на прежнем соединении, пока оно живо.
                    next = null
                    return@execute
                }
                if (webSocket !== socket) return@execute
                connecting = false
                socket = null
                open = false
                stable?.cancel(false)
                stable = null
                if (active) health.onConnectFailure()
                notifyStatus()
                checkHealth(this@Link)
                scheduleRetry()
            }
        }
    }

    data class RelayState(
        val url: String,
        val spare: Boolean,
        val active: Boolean,
        val open: Boolean,
        val benched: Boolean,
        val accepted: Int
    )

    companion object {
        private const val STORED_LIMIT = 50
        /** Сколько релеев держать в работе, пока хватает основных. */
        const val TARGET_RELAYS = 3
        private const val EVALUATE_SEC = 30L
        private const val ACK_TIMEOUT_SEC = 8L
        private const val STABLE_SEC = 120L
        private const val MAX_PENDING_ACKS = 64
        /**
         * Порог переподключения. Одно приглашение приходит целиком (~5 КБ), поэтому порог
         * с запасом на сообщение до замирания на ~14–25 КБ.
         */
        const val ROTATE_BYTES = 9_000L
        /** TLS-рукопожатие: сертификаты сервера тоже идут в счёт лимита. */
        private const val HANDSHAKE_BYTES = 5_000L
        /** Заголовки кадра WebSocket и записи TLS на каждое сообщение. */
        private const val FRAME_BYTES = 40
        private const val PONG_BYTES = 35L
        private const val MIN_ROTATION_MS = 1_000L
        private const val STORED_OVERLAP_SEC = 120L

        /**
         * Публичные релеи без регистрации, независимые операторы.
         * Каждый проверен: пересылает наши эфемерные события между двумя
         * соединениями (см. LiveRelayTest). Политика публичных релеев может
         * меняться — при правке списка перезапустите этот тест.
         */
        val DEFAULT_RELAYS = listOf(
            "wss://relay.snort.social",
            "wss://nostr.sathoarder.com",
            "wss://offchain.pub",
            "wss://relay.nostr.net"
        )

        /**
         * Запасные релеи: подключаются, только когда основных в работе меньше
         * [TARGET_RELAYS]. Проверены тем же LiveRelayTest.
         *
         * Основные подобраны так, чтобы большинство было вне Cloudflare, Hetzner, OVH и
         * других крупных хостингов: из России соединения с ними замирают после ~16 КБ.
         * Списки пересекаются с прежним (snort, sathoarder, nostr.net), поэтому старые
         * версии приложения по-прежнему дозваниваются до новых и наоборот.
         */
        val SPARE_RELAYS = listOf(
            "wss://relay.primal.net",
            "wss://nostr.oxtr.dev"
        )
    }
}
