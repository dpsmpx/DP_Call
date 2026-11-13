package com.dpsmpx.dpcall.carrier

import java.net.URI

/** Accept only guest meeting links, never arbitrary hosts, credentials or fragments. */
data class CarrierRoom(val provider: String, val url: String) {
    companion object {
        fun parse(raw: String): CarrierRoom? = try {
            val uri = URI(raw.trim())
            val host = uri.host?.lowercase()
            val path = uri.path.orEmpty()
            val provider = when {
                host == "telemost.yandex.ru" && Regex("/j/[A-Za-z0-9_-]+").matches(path) -> "telemost"
                host in setOf("vk.ru", "vk.com") && Regex("/call/join/[A-Za-z0-9_-]+").matches(path) -> "vk"
                else -> null
            }
            if (provider == null || uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.fragment != null || uri.query != null) null
            else CarrierRoom(provider, "https://${if (provider == "vk") "vk.ru" else host}$path")
        } catch (e: Exception) { null }
    }
}
