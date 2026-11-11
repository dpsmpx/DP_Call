package com.dpcall.app.util

import com.dpcall.app.domain.IdentityGenerator

/**
 * Проверка кода собеседника: 52 символа Crockford Base32, за которыми стоит
 * корректный публичный ключ.
 */
object CodeValidator {

    private const val LEGACY_LENGTH = 26

    fun isValid(code: String): Boolean = IdentityGenerator.publicKeyFromCode(code) != null

    /** Код старого формата (до перехода на ключи): по нему нельзя позвонить. */
    fun isLegacy(code: String): Boolean = normalize(code).length == LEGACY_LENGTH

    fun normalize(code: String): String = code.filterNot { it.isWhitespace() }.uppercase()
}
