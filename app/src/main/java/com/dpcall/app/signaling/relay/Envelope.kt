package com.dpcall.app.signaling.relay

import com.dpcall.app.signaling.relay.RelayCrypto.toHex

/**
 * Запечатанное сообщение для одного получателя.
 *
 * Два слоя шифрования:
 *  - внешний — одноразовым ключом: релей не видит, кто отправитель;
 *  - внутренний — ключом пары «отправитель ↔ получатель»: расшифровать его может
 *    только тот, для кого он предназначен, а создать — только владелец кода
 *    отправителя. Так получатель уверен, кто ему звонит.
 *
 * Адресат обозначен не кодом, а хешем от него ([inboxTag]): по тегу нельзя
 * восстановить код и позвонить его владельцу.
 */
object Envelope {
    private const val VERSION: Byte = 1
    private val SALT = "dpcall-signal-v1".toByteArray()
    private val INFO_OUTER = "outer".toByteArray()
    private val INFO_INNER = "inner".toByteArray()
    private val INBOX_PREFIX = "dpcall-inbox-v1".toByteArray()

    class Sealed(val ephemeralSecret: ByteArray, val ephemeralPublic: ByteArray, val content: ByteArray)

    class Opened(val senderPublic: ByteArray, val plaintext: ByteArray)

    /** Тег «почтового ящика» получателя (hex, 64 символа). */
    fun inboxTag(recipientPublic: ByteArray): String = RelayCrypto.sha256(INBOX_PREFIX, recipientPublic).toHex()

    fun seal(senderSecret: ByteArray, senderPublic: ByteArray, recipientPublic: ByteArray, plaintext: ByteArray): Sealed {
        var ephemeralSecret: ByteArray
        var ephemeralPublic: ByteArray?
        do {
            ephemeralSecret = RelayCrypto.randomBytes(32)
            ephemeralPublic = RelayCrypto.publicKeyOrNull(ephemeralSecret)
        } while (ephemeralPublic == null)

        val innerKey = RelayCrypto.hkdf(RelayCrypto.sharedSecret(senderSecret, recipientPublic), SALT, INFO_INNER)
        // Внутренний слой привязан к одноразовому ключу и получателю — его нельзя переупаковать.
        val inner = RelayCrypto.seal(innerKey, plaintext, aad = ephemeralPublic + recipientPublic)

        val outerKey = RelayCrypto.hkdf(RelayCrypto.sharedSecret(ephemeralSecret, recipientPublic), SALT, INFO_OUTER)
        val outer = RelayCrypto.seal(outerKey, senderPublic + inner, aad = ephemeralPublic)
        return Sealed(ephemeralSecret, ephemeralPublic, byteArrayOf(VERSION) + outer)
    }

    /** @return null, если сообщение не нам, повреждено или подделано. */
    fun open(recipientSecret: ByteArray, recipientPublic: ByteArray, ephemeralPublic: ByteArray, content: ByteArray): Opened? {
        return try {
            if (content.isEmpty() || content[0] != VERSION) return null
            val outerKey = RelayCrypto.hkdf(RelayCrypto.sharedSecret(recipientSecret, ephemeralPublic), SALT, INFO_OUTER)
            val outer = RelayCrypto.open(outerKey, content.copyOfRange(1, content.size), aad = ephemeralPublic)
            if (outer.size <= 32) return null
            val senderPublic = outer.copyOfRange(0, 32)
            val innerKey = RelayCrypto.hkdf(RelayCrypto.sharedSecret(recipientSecret, senderPublic), SALT, INFO_INNER)
            val plaintext = RelayCrypto.open(
                innerKey, outer.copyOfRange(32, outer.size), aad = ephemeralPublic + recipientPublic
            )
            Opened(senderPublic, plaintext)
        } catch (e: Exception) {
            null
        }
    }
}
