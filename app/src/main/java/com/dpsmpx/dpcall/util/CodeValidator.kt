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
     * которым поделились целиком. Понимает код, который мессенджер разбил на части
     * пробелами, переносами или дефисами, обёрнутый в кавычки или разметку, с
     * невидимыми символами внутри, а также перепутанные при наборе O/0 и I/L/1.
     *
     * @return нормализованный код или null.
     */
    fun extract(text: String): String? {
        if (text.length > MAX_TEXT) return null
        val clean = text.filterNot { it in INVISIBLE }
        normalize(clean).takeIf(::isValid)?.let { return it }
        CODE_TOKEN.findAll(clean).map { it.value.uppercase() }.firstOrNull(::isValid)?.let { return it }
        return joinedCandidates(clean).firstOrNull(::isValid)
            ?: joinedCandidates(clean).map(::fixLookalikes).firstOrNull(::isValid)
    }

    /**
     * Подряд идущие латинско-цифровые куски, разделённые только пробелами, переносами
     * или дефисами, суммарной длиной ровно в код. Перебираются все начала: перед кодом
     * могут стоять обычные латинские слова.
     */
    private fun joinedCandidates(text: String): Sequence<String> = sequence {
        val runs = RUN.findAll(text).toList()
        for (start in runs.indices) {
            val sb = StringBuilder()
            var i = start
            while (i < runs.size && sb.length < IdentityGenerator.CODE_LENGTH) {
                if (i > start) {
                    val gap = text.substring(runs[i - 1].range.last + 1, runs[i].range.first)
                    if (!gap.all { it.isWhitespace() || it in JOINERS }) break
                }
                sb.append(runs[i].value)
                i++
            }
            if (sb.length == IdentityGenerator.CODE_LENGTH) yield(sb.toString().uppercase())
        }
    }

    /** В Crockford Base32 нет букв O, I, L: при наборе вручную их путают с 0 и 1. */
    private fun fixLookalikes(code: String): String =
        code.replace('O', '0').replace('I', '1').replace('L', '1')

    private val RUN = Regex("[0-9A-Za-z]+")
    private const val JOINERS = "-‐‑‒–—_.·"
    /** Символы нулевой ширины и мягкий перенос, которые мессенджеры вставляют в текст. */
    private const val INVISIBLE = "\u200B\u200C\u200D\u2060\uFEFF\u00AD"
    private const val MAX_TEXT = 20_000
}
