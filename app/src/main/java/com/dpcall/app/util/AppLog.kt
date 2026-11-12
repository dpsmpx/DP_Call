package com.dpcall.app.util

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

    @Synchronized
    fun add(tag: String, message: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast("${format.format(Date())} $tag: $message")
        Log.i(tag, message)
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
