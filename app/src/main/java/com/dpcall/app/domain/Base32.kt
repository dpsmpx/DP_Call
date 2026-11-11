package com.dpcall.app.domain

/** Crockford Base32 без padding (алфавит без I, L, O, U). */
object Base32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(data: ByteArray): String {
        val result = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = ((buffer shl 8) or (byte.toInt() and 0xFF)) and 0xFFFF
            bitsLeft += 8
            while (bitsLeft >= 5) {
                result.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) result.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return result.toString()
    }

    /** @return null, если строка содержит посторонние символы. */
    fun decode(text: String): ByteArray? {
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        for (ch in text) {
            val value = ALPHABET.indexOf(ch.uppercaseChar())
            if (value < 0) return null
            buffer = ((buffer shl 5) or value) and 0xFFFF
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[index++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }
        return out
    }
}
