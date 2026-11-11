package com.dpcall.app.domain

import kotlinx.serialization.Serializable

/**
 * Локальный контакт.
 * Идентифицируется уникальным кодом (Identity.value).
 * Имя задаёт пользователь.
 */
@Serializable
data class Contact(
    val code: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(code.isNotBlank()) { "Contact code cannot be blank" }
        require(name.isNotBlank()) { "Contact name cannot be blank" }
    }
}
