package com.dpcall.app.domain

/**
 * Локальный уникальный идентификатор пользователя.
 *
 * Формат: Base32 (Crockford) без padding, 26 символов.
 * Удобен для копирования, не предназначен для диктовки.
 */
@JvmInline
value class Identity(val value: String) {
    init {
        require(value.isNotBlank()) { "Identity cannot be blank" }
        require(value.length in 20..40) { "Identity length out of expected range" }
    }

    override fun toString(): String = value
}
