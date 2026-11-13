package com.dpsmpx.dpcall.signaling.relay

import com.dpsmpx.dpcall.domain.Base32
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.domain.IdentityKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Доставка [CallMessage] между двумя кодами через релеи: шифрование, подпись,
 * проверка и расшифровка. Не зависит от Android.
 */
class RelayMessenger(
    private val keys: IdentityKeys,
    relays: List<String> = RelayPool.DEFAULT_RELAYS,
    private val onMessage: (fromCode: String, message: CallMessage) -> Unit,
    /** Забирать с релеев отметки о звонках, пропущенных, пока нас не было на связи. */
    receiveMissed: Boolean = false,
    onStatus: (connected: Int, total: Int) -> Unit = { _, _ -> },
    log: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val pool = RelayPool(
        urls = relays,
        inboxTag = Envelope.inboxTag(keys.public),
        kind = NostrEvent.KIND_CALL_SIGNAL,
        storedKind = NostrEvent.KIND_MISSED_CALL.takeIf { receiveMissed },
        storedWindowSec = MISSED_TTL_SEC + CLOCK_SKEW_SEC,
        onEvent = ::onEvent,
        onStatus = onStatus,
        log = log
    )

    val connectedCount: Int get() = pool.connectedCount

    fun start() = pool.start()
    fun stop() = pool.stop()
    fun reconnectNow() = pool.reconnectNow()

    /**
     * Сообщения [CallMessage.MISSED] уходят хранимым событием со сроком жизни, остальные —
     * эфемерными.
     *
     * @return число релеев, принявших сообщение; 0 — нет связи или код старого формата.
     */
    fun send(toCode: String, message: CallMessage): Int {
        val recipient = IdentityGenerator.publicKeyFromCode(toCode) ?: return 0
        val stored = message.t == CallMessage.MISSED
        val inbox = listOf("p", Envelope.inboxTag(recipient))
        val sealed = Envelope.seal(
            senderSecret = keys.secret,
            senderPublic = keys.public,
            recipientPublic = recipient,
            plaintext = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        )
        val event = NostrEvent.create(
            secret = sealed.ephemeralSecret,
            public = sealed.ephemeralPublic,
            kind = if (stored) NostrEvent.KIND_MISSED_CALL else NostrEvent.KIND_CALL_SIGNAL,
            tags = if (stored) {
                listOf(inbox, listOf("expiration", (System.currentTimeMillis() / 1000 + MISSED_TTL_SEC).toString()))
            } else {
                listOf(inbox)
            },
            content = Base64.getEncoder().encodeToString(sealed.content)
        )
        return pool.publish(event)
    }

    private fun onEvent(event: NostrEvent) {
        val content = try {
            Base64.getDecoder().decode(event.content)
        } catch (e: Exception) {
            return
        }
        val ephemeral = with(RelayCrypto) { event.pubkey.hexToBytes() } ?: return
        val opened = Envelope.open(keys.secret, keys.public, ephemeral, content) ?: return
        val message = try {
            json.decodeFromString<CallMessage>(String(opened.plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        // Хранимым событием может быть только отметка о пропущенном, и наоборот: иначе
        // сохранённое на релее приглашение можно было бы «доставить» повторно.
        if ((event.kind == NostrEvent.KIND_MISSED_CALL) != (message.t == CallMessage.MISSED)) return
        onMessage(Base32.encode(opened.senderPublic), message)
    }

    companion object {
        /** Сколько релеи хранят отметку о пропущенном звонке. */
        const val MISSED_TTL_SEC = 48 * 3600L
        private const val CLOCK_SKEW_SEC = 600L
    }
}
