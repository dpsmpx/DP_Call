package com.dpsmpx.dpcall.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Журнал событий звонка в памяти процесса (последние [CAPACITY] строк).
 *
 * Попадает в отчёт, которым пользователь делится вручную. Коды собеседников,
 * ключи, SDP и IP-адреса сюда не пишутся — только состояния и типы событий.
 */
object AppLog {
    private const val CAPACITY = 500
    private val lines = ArrayDeque<String>(CAPACITY)
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val IPV4 = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
    private val IPV6 = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){3,}[0-9a-f:]*[0-9a-f]")

    /** Убирает IP-адреса: в текстах сетевых ошибок встречаются адреса релеев и самого устройства. */
    fun scrub(text: String): String = IPV6.replace(IPV4.replace(text, "<ip>"), "<ip>")

    @Synchronized
    fun add(tag: String, message: String) {
        val clean = scrub(message)
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast("${format.format(Date())} $tag: $clean")
        Log.i(tag, clean)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
