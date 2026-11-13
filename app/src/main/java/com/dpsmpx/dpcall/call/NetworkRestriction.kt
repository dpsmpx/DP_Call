package com.dpsmpx.dpcall.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dpsmpx.dpcall.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Почему нет связи с релеями, если интернет на самом деле есть. */
enum class Restriction {
    /** Ограничений не замечено (или связи нет вовсе). */
    NONE,
    /** Мобильный интернет в режиме «белых списков»: открываются только разрешённые сайты. */
    MOBILE_WHITELIST,
    /** Интернет есть, но релеи в этой сети недоступны (блокировка у провайдера). */
    RELAYS_BLOCKED
}

/**
 * Отличает «нет интернета» от «интернет ограничен». Включается, только когда ни один
 * релей не отвечает дольше [OFFLINE_GRACE_MS]: тогда проверяет, открывается ли сайт из
 * российских «белых списков». Если да — связь есть, но релеи отрезаны.
 */
object NetworkRestriction {
    private const val TAG = "NetworkRestriction"
    private const val OFFLINE_GRACE_MS = 15_000L
    private const val RECHECK_MS = 60_000L
    /** Сайт Яндекса входит во все версии «белых списков» и отвечает быстро. */
    private const val WHITELISTED_URL = "https://ya.ru/"

    private val _state = MutableStateFlow(Restriction.NONE)
    val state: StateFlow<Restriction> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * Вызывается при каждом изменении числа подключённых релеев.
     * @param probeAllowed false в режиме Tor: напрямую в сеть не ходим.
     */
    @Synchronized
    fun onRelayStatus(context: Context, online: Boolean, probeAllowed: Boolean) {
        if (online || !probeAllowed) {
            job?.cancel()
            job = null
            _state.value = Restriction.NONE
            return
        }
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            delay(OFFLINE_GRACE_MS)
            while (isActive) {
                val result = check(app)
                if (result != _state.value) AppLog.add(TAG, "restriction: $result")
                _state.value = result
                delay(RECHECK_MS)
            }
        }
    }

    private fun check(context: Context): Restriction {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return Restriction.NONE) ?: return Restriction.NONE
        val whitelistedReachable = try {
            client.newCall(Request.Builder().url(WHITELISTED_URL).head().build()).execute().use { true }
        } catch (e: Exception) {
            false
        }
        return classify(
            whitelistedReachable = whitelistedReachable,
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        )
    }

    /** Релеи недоступны; что это значит при данных обстоятельствах. */
    fun classify(whitelistedReachable: Boolean, cellular: Boolean): Restriction = when {
        !whitelistedReachable -> Restriction.NONE
        cellular -> Restriction.MOBILE_WHITELIST
        else -> Restriction.RELAYS_BLOCKED
    }
}
