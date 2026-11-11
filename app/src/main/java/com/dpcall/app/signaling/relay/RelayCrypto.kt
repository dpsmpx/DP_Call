package com.dpcall.app.signaling.relay

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография сигнального канала.
 *
 * Ключи — secp256k1 в формате x-only (как в BIP-340 / Nostr).
 * Общий секрет — x-координата ECDH, из неё HKDF-SHA256 выводит ключ AES-256-GCM.
 */
object RelayCrypto {
    private val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** x-only публичный ключ (32 байта) или null, если секрет не годится. */
    fun publicKeyOrNull(secret: ByteArray): ByteArray? = try {
        if (!Secp256k1.secKeyVerify(secret)) null
        else Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33)
    } catch (e: Exception) {
        null
    }

    fun isValidPublicKey(xOnly: ByteArray): Boolean = try {
        xOnly.size == 32 && Secp256k1.pubkeyParse(byteArrayOf(2) + xOnly).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** x-координата общей точки: одинакова у обеих сторон независимо от чётности ключей. */
    fun sharedSecret(secret: ByteArray, peerXOnly: ByteArray): ByteArray {
        val point = Secp256k1.pubKeyTweakMul(Secp256k1.pubkeyParse(byteArrayOf(2) + peerXOnly), secret)
        return Secp256k1.pubKeyCompress(point).copyOfRange(1, 33)
    }

    fun signSchnorr(hash32: ByteArray, secret: ByteArray): ByteArray =
        Secp256k1.signSchnorr(hash32, secret, randomBytes(32))

    fun verifySchnorr(signature: ByteArray, hash32: ByteArray, xOnly: ByteArray): Boolean = try {
        Secp256k1.verifySchnorr(signature, hash32, xOnly)
    } catch (e: Exception) {
        false
    }

    /** HKDF-SHA256 (RFC 5869), один блок на выходе — 32 байта. */
    fun hkdf(inputKey: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, inputKey)
        return hmac(prk, info + byteArrayOf(1))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** @return nonce(12) || ciphertext+tag */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** @throws java.security.GeneralSecurityException если данные подделаны или ключ не тот */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "sealed data too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
