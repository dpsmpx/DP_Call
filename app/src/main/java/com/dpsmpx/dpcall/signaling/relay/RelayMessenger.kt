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
    onStatus: (connected: Int, total: Int) -> Unit = { _, _ -> },
    log: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val pool = RelayPool(
        urls = relays,
        inboxTag = Envelope.inboxTag(keys.public),
        kind = NostrEvent.KIND_CALL_SIGNAL,
        onEvent = ::onEvent,
        onStatus = onStatus,
        log = log
    )

    val connectedCount: Int get() = pool.connectedCount

    fun start() = pool.start()
    fun stop() = pool.stop()
    fun reconnectNow() = pool.reconnectNow()

    /** @return число релеев, принявших сообщение; 0 — нет связи или код старого формата. */
    fun send(toCode: String, message: CallMessage): Int {
        val recipient = IdentityGenerator.publicKeyFromCode(toCode) ?: return 0
        val sealed = Envelope.seal(
            senderSecret = keys.secret,
            senderPublic = keys.public,
            recipientPublic = recipient,
            plaintext = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        )
        val event = NostrEvent.create(
            secret = sealed.ephemeralSecret,
            public = sealed.ephemeralPublic,
            kind = NostrEvent.KIND_CALL_SIGNAL,
            tags = listOf(listOf("p", Envelope.inboxTag(recipient))),
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
        onMessage(Base32.encode(opened.senderPublic), message)
    }
}
