package com.dpcall.app.util

/**
 * Проверка формата identity-кода.
 * Ожидаем Crockford Base32, 20–40 символов (наш генератор даёт 26).
 */
object CodeValidator {

    private val VALID_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()

    fun isValid(code: String): Boolean {
        val cleaned = code.trim().uppercase()
        if (cleaned.length !in 20..40) return false
        return cleaned.all { it in VALID_CHARS }
    }

    fun normalize(code: String): String = code.trim().uppercase()
}
