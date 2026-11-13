package com.dpsmpx.dpcall.util

import com.dpsmpx.dpcall.domain.IdentityGenerator

/**
 * Проверка кода собеседника: 52 символа Crockford Base32, за которыми стоит
 * корректный публичный ключ.
 */
object CodeValidator {

    private const val LEGACY_LENGTH = 26

    /** Слово ровно из 52 букв и цифр — кандидат в код. */
    private val CODE_TOKEN = Regex("(?<![0-9A-Za-z])[0-9A-Za-z]{${IdentityGenerator.CODE_LENGTH}}(?![0-9A-Za-z])")

    fun isValid(code: String): Boolean = IdentityGenerator.publicKeyFromCode(code) != null

    /** Код старого формата (до перехода на ключи): по нему нельзя позвонить. */
    fun isLegacy(code: String): Boolean = normalize(code).length == LEGACY_LENGTH

    fun normalize(code: String): String = code.filterNot { it.isWhitespace() }.uppercase()

    /**
     * Находит код в произвольном тексте — например, в сообщении «Мой код DP Call: …»,
     * которым поделились целиком. @return нормализованный код или null.
     */
    fun extract(text: String): String? {
        normalize(text).takeIf(::isValid)?.let { return it }
        return CODE_TOKEN.findAll(text).map { it.value.uppercase() }.firstOrNull(::isValid)
    }
}
