package com.dpsmpx.dpcall.call

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.dpsmpx.dpcall.util.AppLog
import com.dpsmpx.dpcall.webrtc.AudioRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Регистрация звонка в системном Telecom (Jetpack Core-Telecom, self-managed).
 *
 * Это то, что делает DP Call «настоящей звонилкой» для системы:
 *  - кнопки проводной и Bluetooth-гарнитуры (ответить / завершить) приходят сюда;
 *  - система сама маршрутизирует звук (динамик, гарнитура, Bluetooth, часы, авто);
 *  - обычный входящий GSM-вызов ставит наш звонок на удержание и возвращает обратно.
 *
 * Если Telecom недоступен или отказал, звонок продолжает работать без него
 * (см. [Listener.onTelecomUnavailable]).
 */
class TelecomBridge(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        /** Ответ с гарнитуры / часов / автомобиля. */
        fun onTelecomAnswer()
        /** Завершение с гарнитуры или системой. */
        fun onTelecomDisconnect()
        fun onTelecomHold(onHold: Boolean)
        fun onTelecomRoutes(current: AudioRoute?, available: List<AudioRoute>)
        fun onTelecomMuted(muted: Boolean)
        fun onTelecomUnavailable()
    }

    companion object {
        private const val TAG = "TelecomBridge"
    }

    private val appContext = context.applicationContext
    private var callsManager: CallsManager? = null
    private var registered = false

    private var job: Job? = null
    private var control: CallControlScope? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var currentEndpoint: CallEndpointCompat? = null
    private var pendingAnswer = false
    private var pendingActive = false

    /** Звонок принят системой, маршрутизация звука идёт через Telecom. */
    val isReady: Boolean get() = control != null

    private fun ensureRegistered(): CallsManager? {
        callsManager?.let { if (registered) return it }
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            !pm.hasSystemFeature("android.software.telecom") &&
            !pm.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)
        ) {
            return null
        }
        return try {
            val cm = callsManager ?: CallsManager(appContext).also { callsManager = it }
            cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            registered = true
            cm
        } catch (e: Exception) {
            Log.w(TAG, "Telecom registration failed", e)
            null
        }
    }

    /** @return false, если Telecom недоступен (звонок пойдёт без него). */
    fun start(displayName: String, code: String, incoming: Boolean): Boolean {
        stop(DisconnectCause.LOCAL)
        val cm = ensureRegistered() ?: return false

        val attributes = CallAttributesCompat(
            displayName = displayName,
            address = Uri.fromParts("sip", code.ifBlank { "unknown" } + "@dpcall", null),
            direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE
        )
        pendingAnswer = false
        pendingActive = false

        lateinit var thisJob: Job
        thisJob = scope.launch {
            try {
                cm.addCall(
                    attributes,
                    onAnswer = {
                        AppLog.add(TAG, "telecom: answer")
                        if (job === thisJob) listener.onTelecomAnswer()
                    },
                    onDisconnect = { cause ->
                        AppLog.add(TAG, "telecom: disconnect cause=${cause.code} ${cause.reason ?: ""}")
                        if (job === thisJob) {
                            // Telecom уже завершил звонок со своей стороны.
                            control = null
                            job = null
                            listener.onTelecomDisconnect()
                        }
                    },
                    onSetActive = {
                        AppLog.add(TAG, "telecom: set active")
                        if (job === thisJob) listener.onTelecomHold(false)
                    },
                    onSetInactive = {
                        AppLog.add(TAG, "telecom: set inactive (hold)")
                        if (job === thisJob) listener.onTelecomHold(true)
                    }
                ) {
                    if (job !== thisJob) return@addCall
                    AppLog.add(TAG, "telecom: call registered (incoming=$incoming)")
                    control = this
                    if (pendingAnswer) launch { answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
                    if (pendingActive) launch { setActive() }
                    pendingAnswer = false
                    pendingActive = false
                    launch {
                        currentCallEndpoint.collect {
                            currentEndpoint = it
                            emitRoutes(thisJob)
                        }
                    }
                    launch {
                        availableEndpoints.collect {
                            endpoints = it
                            emitRoutes(thisJob)
                        }
                    }
                    launch {
                        isMuted.distinctUntilChanged().drop(1).collect {
                            if (job === thisJob) listener.onTelecomMuted(it)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.add(TAG, "telecom: addCall FAILED: $e")
                if (job === thisJob) {
                    control = null
                    job = null
                    listener.onTelecomUnavailable()
                }
            }
        }
        job = thisJob
        return true
    }

    private fun emitRoutes(owner: Job) {
        if (job !== owner) return
        listener.onTelecomRoutes(
            current = currentEndpoint?.toRoute(),
            available = endpoints.mapNotNull { it.toRoute() }.distinct()
        )
    }

    /** Сообщает системе, что пользователь принял входящий в приложении. */
    fun answer() {
        val c = control
        if (c == null) pendingAnswer = true
        else c.launch { runCatching { c.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) } }
    }

    /** Исходящий звонок соединился / звонок снят с удержания. */
    fun setActive() {
        val c = control
        if (c == null) pendingActive = true
        else c.launch { runCatching { c.setActive() } }
    }

    fun requestRoute(route: AudioRoute): Boolean {
        val c = control ?: return false
        val endpoint = endpoints.firstOrNull { it.toRoute() == route } ?: return false
        c.launch { runCatching { c.requestEndpointChange(endpoint) } }
        return true
    }

    /** Завершает звонок в Telecom. [cause] — константа [DisconnectCause]. */
    fun stop(cause: Int) {
        val c = control
        val j = job
        control = null
        job = null
        endpoints = emptyList()
        currentEndpoint = null
        pendingAnswer = false
        pendingActive = false
        if (j == null) return
        if (c == null) {
            j.cancel()
            return
        }
        scope.launch {
            try {
                withTimeoutOrNull(3_000) { c.disconnect(DisconnectCause(cause)) }
            } catch (e: Exception) {
                Log.w(TAG, "Telecom disconnect failed", e)
            } finally {
                j.cancel()
            }
        }
    }

    private fun CallEndpointCompat.toRoute(): AudioRoute? = when (type) {
        CallEndpointCompat.TYPE_EARPIECE -> AudioRoute.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.SPEAKER
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.BLUETOOTH
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
        else -> null
    }
}
