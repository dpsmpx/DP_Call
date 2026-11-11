package com.dpcall.app.domain

import com.dpcall.app.signaling.relay.RelayCrypto
import java.security.SecureRandom

/**
 * Ключи пользователя.
 *
 * Код, которым обмениваются собеседники, — это публичный ключ secp256k1
 * (32 байта, x-only) в Crockford Base32: 52 символа. Секретный ключ не покидает
 * устройство. По коду можно зашифровать вызов для его владельца и убедиться,
 * что входящий вызов действительно от этого контакта.
 */
class IdentityKeys(val secret: ByteArray, val public: ByteArray) {
    val code: String = Base32.encode(public)
    val identity: Identity get() = Identity(code)
}

object IdentityGenerator {
    const val CODE_LENGTH = 52
    private val secureRandom = SecureRandom()

    fun generate(): IdentityKeys {
        while (true) {
            val secret = ByteArray(32).also { secureRandom.nextBytes(it) }
            val public = RelayCrypto.publicKeyOrNull(secret) ?: continue
            return IdentityKeys(secret, public)
        }
    }

    fun fromSecret(secret: ByteArray): IdentityKeys? =
        RelayCrypto.publicKeyOrNull(secret)?.let { IdentityKeys(secret, it) }

    /** Публичный ключ из кода или null, если это не корректный код нового формата. */
    fun publicKeyFromCode(code: String): ByteArray? {
        val cleaned = code.trim().uppercase()
        if (cleaned.length != CODE_LENGTH) return null
        val bytes = Base32.decode(cleaned) ?: return null
        if (bytes.size != 32 || Base32.encode(bytes) != cleaned) return null
        return bytes.takeIf { RelayCrypto.isValidPublicKey(it) }
    }
}
