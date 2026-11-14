package com.dpsmpx.dpcall.update

import android.content.Context
import android.util.Log
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.data.UpdateInfo
import com.dpsmpx.dpcall.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import com.dpsmpx.dpcall.BuildConfig
import com.dpsmpx.dpcall.tor.TorProxy
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Проверка новой версии в GitHub Releases.
 *
 * Приложение только узнаёт номер последнего выпуска и показывает кнопку; сам APK
 * скачивает браузер, а устанавливает система — она же сверяет подпись с уже
 * установленной версией. Запрос уходит на api.github.com не чаще раза в сутки и
 * отключается в настройках; никаких сведений о пользователе в нём нет.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    const val REPOSITORY = "dpsmpx/DP_Call"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"
    /**
     * Прямая ссылка на APK последнего выпуска. Работает, потому что к каждому выпуску
     * прикладывается копия APK с постоянным именем (см. .github/workflows/build.yml).
     */
    const val DIRECT_DOWNLOAD = "https://github.com/$REPOSITORY/releases/latest/download/DP_Call.apk"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    /** Скачивать разрешаем только файлы выпусков этого репозитория. */
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPOSITORY/releases/"
    /** Начало имени APK выпуска: `DP_Call-v1.3.0.apk` и `DP_Call-Tor-v1.3.0.apk`. */
    private const val STANDARD_ASSET_PREFIX = "DP_Call-v"
    private const val TOR_ASSET_PREFIX = "DP_Call-Tor-v"
    private const val CHECK_INTERVAL_MS = 24 * 3600_000L
    private const val MAX_RESPONSE_BYTES = 512 * 1024L

    enum class Result { UPDATE_AVAILABLE, UP_TO_DATE, FAILED }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Плановая проверка: молча пропускается, если выключена или с прошлой прошло меньше суток. */
    suspend fun checkIfDue(context: Context) {
        if (com.dpsmpx.dpcall.carrier.CarrierTransport.active) return
        val settings = AppSettings.get(context)
        if (!settings.updateCheck.value) return
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheckAt
        if (elapsed in 0 until CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    /** Проверка прямо сейчас. @param manual по кнопке в настройках: напомнить и о скрытой версии. */
    suspend fun check(context: Context, manual: Boolean = true): Result = mutex.withLock {
        if (com.dpsmpx.dpcall.carrier.CarrierTransport.active) return Result.UP_TO_DATE
        val settings = AppSettings.get(context)
        // В режиме Tor GitHub тоже спрашиваем через Tor; пока он не готов — не спрашиваем вовсе.
        val viaTor = TorProxy.available && settings.torSignaling.value
        val proxy = if (viaTor) TorProxy.proxy() ?: return Result.FAILED else null
        val latest = withContext(Dispatchers.IO) { fetchLatest(proxy) }
        if (latest == null) {
            // Неудачу тоже запоминаем: без сети не нужно стучаться при каждом открытии.
            settings.lastUpdateCheckAt = System.currentTimeMillis()
            return Result.FAILED
        }
        settings.lastUpdateCheckAt = System.currentTimeMillis()
        val installed = installedVersion(context)
        return if (isNewer(latest.version, installed)) {
            AppLog.add(TAG, "update available: ${latest.version} (installed $installed)")
            settings.setAvailableUpdate(latest, showAgain = manual)
            Result.UPDATE_AVAILABLE
        } else {
            settings.setAvailableUpdate(null)
            Result.UP_TO_DATE
        }
    }

    /** Установленную версию обновлять не предлагаем, даже если запись о ней осталась. */
    fun pendingUpdate(context: Context, update: UpdateInfo?): UpdateInfo? =
        update?.takeIf { isNewer(it.version, installedVersion(context)) }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun fetchLatest(proxy: java.net.Proxy?): UpdateInfo? = try {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DP-Call")
            .build()
        val http = if (proxy != null) client.newBuilder().proxy(proxy).connectTimeout(45, TimeUnit.SECONDS).build() else client
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.add(TAG, "update check failed: HTTP ${response.code}")
                return null
            }
            val body = response.peekBody(MAX_RESPONSE_BYTES).string()
            parseRelease(body)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Update check failed", e)
        null
    }

    /** Разбирает ответ GitHub «последний выпуск». Черновики и предварительные выпуски не предлагаются. */
    internal fun parseRelease(body: String, tor: Boolean = BuildConfig.TOR): UpdateInfo? = try {
        val release = json.parseToJsonElement(body).jsonObject
        if (release.flag("draft") || release.flag("prerelease")) {
            null
        } else {
            val version = release.text("tag_name")?.removePrefix("v")?.takeIf { parseVersion(it) != null }
            // У выпуска два APK: обычный и с Tor. Предлагаем тот же вариант, что установлен.
            val apk = release["assets"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.text("browser_download_url") }
                ?.filter { it.startsWith(DOWNLOAD_PREFIX) && it.endsWith(".apk") }
                ?.firstOrNull { url ->
                    val name = url.substringAfterLast('/')
                    name.startsWith(if (tor) TOR_ASSET_PREFIX else STANDARD_ASSET_PREFIX)
                }
            version?.let { UpdateInfo(it, apk ?: RELEASES_PAGE) }
        }
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.text(name: String): String? = try {
        this[name]?.jsonPrimitive?.takeIf { it.isString }?.content
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.flag(name: String): Boolean = try {
        this[name]?.jsonPrimitive?.content == "true"
    } catch (e: Exception) {
        false
    }

    /** «1.2.10» → [1, 2, 10]; версии с суффиксом (1.2.0-dev) и мусор не разбираются. */
    internal fun parseVersion(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v").split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        return parts.map { part -> part.takeIf { it.length in 1..6 && it.all(Char::isDigit) }?.toInt() ?: return null }
    }

    /** Промежуточная сборка (1.2.0-dev) считается своей базовой версией: 1.2.0 её не «обновляет». */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val new = parseVersion(candidate) ?: return false
        val old = parseVersion(installed.substringBefore('-')) ?: return false
        for (i in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrElse(i) { 0 }
            val b = old.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
