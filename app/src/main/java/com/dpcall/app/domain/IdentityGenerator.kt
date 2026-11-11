package com.dpcall.app.domain

import java.security.SecureRandom

/**
 * Генерирует криптостойкий уникальный идентификатор.
 *
 * 16 байт случайных данных → Base32 (Crockford) → 26 символов.
 * Алфавит без неоднозначных символов (0/O, 1/I/L).
 */
object IdentityGenerator {

    // Crockford Base32 (без I, L, O, U)
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BYTE_LENGTH = 16 // 128 bit

    private val secureRandom = SecureRandom()

    fun generate(): Identity {
        val bytes = ByteArray(BYTE_LENGTH)
        secureRandom.nextBytes(bytes)
        val encoded = encodeBase32(bytes)
        return Identity(encoded)
    }

    private fun encodeBase32(data: ByteArray): String {
        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                result.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(ALPHABET[index])
        }

        return result.toString()
    }
}
