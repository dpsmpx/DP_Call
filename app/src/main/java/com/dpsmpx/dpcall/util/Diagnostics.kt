package com.dpsmpx.dpcall.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальная диагностика без телеметрии: падения записываются в файл на устройстве,
 * а отчёт уходит куда-либо только когда пользователь сам нажмёт «Поделиться».
 */
object Diagnostics {
    private const val CRASH_FILE = "crashes.log"
    private const val MAX_CRASH_BYTES = 64 * 1024
    private const val MAX_REPORT_CHARS = 60_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val file = File(app.filesDir, CRASH_FILE)
                if (file.length() > MAX_CRASH_BYTES) file.delete()
                file.appendText("=== ${timestamp()} [${thread.name}]\n$trace\n")
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * Собирает отчёт: версия, устройство, сохранённые падения и журнал самого
     * приложения. Коды и ключи в журнал не пишутся. Вызывать не из главного потока.
     */
    fun buildReport(context: Context): String {
        val app = context.applicationContext
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val crashes = try {
            File(app.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText().orEmpty()
        } catch (e: Exception) {
            ""
        }
        val logcat = try {
            val process = ProcessBuilder("logcat", "-d", "-t", "300", "-v", "time", "*:W")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "logcat unavailable: ${e.message}"
        }
        val report = buildString {
            appendLine("DP Call $version (${app.packageName})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Report time: ${timestamp()}")
            appendLine()
            appendLine("Build: ${if (com.dpsmpx.dpcall.BuildConfig.TOR) "tor" else "standard"}, Tor: ${com.dpsmpx.dpcall.call.RelaySignaling.status.value.tor ?: "off"}")
            appendLine("--- Relays ---")
            val relays = com.dpsmpx.dpcall.call.RelaySignaling.relayStates()
            appendLine(relays.joinToString("\n") { r ->
                val state = when {
                    r.benched -> "benched"
                    !r.active -> "idle"
                    r.open -> "open"
                    else -> "connecting"
                }
                "${r.url}${if (r.spare) " (spare)" else ""}: $state, accepted=${r.accepted}"
            }.ifBlank { "not started" })
            appendLine("--- Crashes ---")
            appendLine(AppLog.scrub(crashes).ifBlank { "none" })
            appendLine("--- Call events ---")
            appendLine(AppLog.dump().ifBlank { "none" })
            appendLine("--- Log ---")
            appendLine(AppLog.scrub(logcat))
        }
        return if (report.length > MAX_REPORT_CHARS) report.takeLast(MAX_REPORT_CHARS) else report
    }
}
