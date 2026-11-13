package com.dpsmpx.dpcall.tor

/**
 * Мосты Tor: разбор строк от пользователя и конфигурация для torrc. Без Android — для тестов.
 *
 * Строки мостов в том виде, в каком их выдают @GetBridgesBot и bridges.torproject.org:
 * `obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0`, `webtunnel [2001:db8::1]:443 … url=…`.
 * Слово `Bridge` в начале допускается.
 */
object TorBridges {
    /** Способы маскировки, которые умеет встроенный IPtProxy, в имени для torrc. */
    val SUPPORTED = setOf("obfs4", "webtunnel", "snowflake", "meek_lite")

    enum class Mode { BUILTIN, CUSTOM, NONE }

    /** Годные строки мостов из произвольного текста: по одной на строку, без дублей. */
    fun parse(text: String): List<String> = text.lineSequence()
        .map { it.trim().removePrefix("Bridge ").removePrefix("bridge ").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .filter { line ->
            val parts = line.split(Regex("\\s+"))
            parts.size >= 2 && parts[0] in SUPPORTED && parts[1].contains(':') && line.none { it == '\n' || it == '\r' }
        }
        .distinct()
        .toList()

    /** Какие способы маскировки нужны для этих мостов. */
    fun transportsOf(bridges: List<String>): Set<String> = bridges.map { it.substringBefore(' ') }.toSet()

    /**
     * Содержимое torrc.
     * @param ports локальный SOCKS-порт каждого способа маскировки (из IPtProxy).
     */
    fun torrc(bridges: List<String>, ports: Map<String, Long>): String = buildString {
        val usable = bridges.filter { ports.containsKey(it.substringBefore(' ')) }
        if (usable.isEmpty()) return@buildString
        appendLine("UseBridges 1")
        ports.forEach { (transport, port) -> appendLine("ClientTransportPlugin $transport socks5 127.0.0.1:$port") }
        usable.forEach { appendLine("Bridge $it") }
    }
}
