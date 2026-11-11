package com.dpcall.app.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import com.dpcall.app.data.AppSettings
import com.dpcall.app.data.ContactsRepository
import com.dpcall.app.data.IdentityRepository
import com.dpcall.app.domain.Contact
import com.dpcall.app.signaling.DecodedSignal
import com.dpcall.app.signaling.SignalCodec
import com.dpcall.app.signaling.SignalType
import com.dpcall.app.signaling.SignalingHelper
import com.dpcall.app.signaling.relay.CallMessage
import com.dpcall.app.util.CodeValidator
import com.dpcall.app.webrtc.AudioCallManager
import com.dpcall.app.webrtc.AudioRoute
import com.dpcall.app.webrtc.CallState
import com.dpcall.app.webrtc.LinkStats
import com.dpcall.app.webrtc.PeerConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.UUID
import kotlin.math.abs

enum class LinkQuality { UNKNOWN, GOOD, FAIR, POOR }

data class CallUiState(
    val state: CallState = CallState.IDLE,
    val isIncoming: Boolean = false,
    val contactName: String = "",
    val contactCode: String = "",
    /** Строка DPCALL1, которую нужно передать собеседнику. */
    val signalToCopy: String? = null,
    val isMuted: Boolean = false,
    val isRemoteMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val isReconnecting: Boolean = false,
    /** Собеседник достижим через релеи — ручной обмен кодами не нужен. */
    val viaRelay: Boolean = false,
    /** Телефон собеседника уже звонит. */
    val remoteRinging: Boolean = false,
    /** Вызов не дошёл: собеседник не в сети. Остаётся ручная отправка приглашения. */
    val peerUnreachable: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    val noiseSuppression: Boolean = true,
    val quality: LinkQuality = LinkQuality.UNKNOWN,
    /** SystemClock.elapsedRealtime() момента соединения, 0 — ещё не соединились. */
    val connectedAt: Long = 0L,
    /** Длительность разговора (для экрана завершения), сек. */
    val durationSec: Long = 0L,
    val errorMessage: String? = null,
    val statusMessage: String = ""
)

/** Результат обработки строки DPCALL1, пришедшей извне. */
sealed interface SignalResult {
    /** Offer принят: приложение «звонит». */
    data object Ringing : SignalResult
    data object AnswerApplied : SignalResult
    /** Тихо пропущено (уже обработано, устарело и т.п.). */
    data object Ignored : SignalResult
    /** Это наш собственный сигнал. */
    data object Own : SignalResult
    data object Busy : SignalResult
    /** Answer пришёл, но мы его не ждём. */
    data object Unexpected : SignalResult
    data class Invalid(val message: String) : SignalResult
}

/**
 * Единственный на процесс «движок» звонка.
 *
 * Живёт дольше Activity: звонок продолжается в свёрнутом приложении, а управлять
 * им можно из UI, из уведомления ([CallService]) и с гарнитуры ([TelecomBridge]).
 * Все публичные методы вызываются с главного потока.
 */
@SuppressLint("StaticFieldLeak") // хранится только applicationContext
object CallManager {
    private const val TAG = "CallManager"
    private const val ICE_GATHER_TIMEOUT_MS = 8_000L
    private const val RING_TIMEOUT_MS = 60_000L
    private const val RECONNECT_TIMEOUT_MS = 20_000L
    private const val ENDED_SCREEN_MS = 1_800L
    private const val AUTO_OFFER_MAX_AGE_MS = 30 * 60_000L
    private const val OFFER_RESEND_MS = 5_000L
    private const val OFFER_RESEND_ATTEMPTS = 9
    private const val ANSWER_RESEND_MS = 3_000L
    private const val ANSWER_RESEND_ATTEMPTS = 5
    private const val NO_ANSWER_TIMEOUT_MS = 65_000L
    /** С запасом на неточные часы устройств; повторы отсекаются по идентификатору звонка. */
    private const val RELAY_MESSAGE_MAX_AGE_MS = 5 * 60_000L
    private const val MSG_BYE = "bye"
    private const val MSG_MUTE = "mute:"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var audio: AudioCallManager
    private lateinit var telecom: TelecomBridge
    private var pcm: PeerConnectionManager? = null

    private var myIdentity: String? = null
    private var contacts: List<Contact> = emptyList()

    /** Увеличивается при каждом новом звонке и при завершении — отсекает устаревшие колбэки. */
    private var generation = 0
    private var pendingOffer: DecodedSignal? = null
    private var pendingLocalSdp: SessionDescription? = null
    private var signalFinalized = false
    private var useFallbackRouting = false
    private var statsJob: Job? = null
    private var resendJob: Job? = null
    private var ringback: ToneGenerator? = null
    private lateinit var settings: AppSettings

    /** Идентификатор текущего звонка в сигнальном канале. */
    private var callId = ""
    /** Завершённые звонки: повторно пришедшие сообщения по ним игнорируются. */
    private val finishedCallIds = LinkedHashSet<String>()
    /** Звонки, которые пользователь отклонил: на повтор приглашения отвечаем отказом ещё раз. */
    private val rejectedCallIds = HashSet<String>()
    /** Система не дала запустить CallService из фона — уведомление показываем сами. */
    private var serviceFallback = false
    private val handledSignals = HashSet<Int>()

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val state: CallState get() = _uiState.value.state

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        audio = AudioCallManager(app)
        telecom = TelecomBridge(app, scope, telecomListener)
        settings = AppSettings.get(app)
        _uiState.update { it.copy(noiseSuppression = settings.noiseSuppression) }
        scope.launch {
            // Запасной путь уведомлений, когда foreground-сервис звонка недоступен.
            uiState.collect { state ->
                if (!serviceFallback) return@collect
                if (state.state.isActive) {
                    CallNotifications.notifyDirect(app, state)
                } else {
                    serviceFallback = false
                    CallNotifications.cancelDirect(app)
                }
            }
        }

        val identityRepo = IdentityRepository(app)
        scope.launch {
            runCatching { myIdentity = identityRepo.getOrCreate().value }
            identityRepo.identityFlow.collect { id -> id?.let { myIdentity = it.value } }
        }
        scope.launch {
            ContactsRepository(app).contactsFlow.collect { list ->
                contacts = list
                // При холодном старте через «Поделиться» звонок может начаться раньше,
                // чем загрузятся контакты, — подставляем имя, как только оно известно.
                val current = _uiState.value
                if (current.state != CallState.IDLE && current.contactCode.isNotBlank()) {
                    list.firstOrNull { it.code.equals(current.contactCode, ignoreCase = true) }?.let { contact ->
                        if (contact.name != current.contactName) {
                            _uiState.update { it.copy(contactName = contact.name) }
                        }
                    }
                }
            }
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- исходящий

    fun startOutgoing(contactName: String, contactCode: String) {
        if (state.isActive) return
        if (!hasMicPermission()) {
            showFailure(contactName, contactCode, "Нет доступа к микрофону. Разрешите его в настройках приложения.")
            return
        }
        val gen = beginCall()
        callId = UUID.randomUUID().toString()
        val reachable = CodeValidator.isValid(contactCode)
        _uiState.update {
            freshState().copy(
                state = CallState.CREATING_OFFER,
                isIncoming = false,
                contactName = contactName,
                contactCode = contactCode,
                viaRelay = reachable,
                statusMessage = if (reachable) "Вызываем…" else "Готовим приглашение…"
            )
        }
        ensureService()
        startTelecom(contactName, contactCode, incoming = false)
        startAudio()

        val manager = createPeer(gen) ?: return
        manager.createOffer(
            onSuccess = { sdp -> main.post { if (gen == generation) onLocalSdpReady(sdp) } },
            onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, "Не удалось создать приглашение")) } }
        )
    }

    // ----------------------------------------------------------------- входящий

    /**
     * Обрабатывает строку DPCALL1 (из «Поделиться», буфера обмена или ручной вставки).
     *
     * @param auto сигнал найден автоматически (буфер обмена): устаревшие и уже
     *   обработанные строки тихо пропускаются.
     * @param autoAccept сразу принять Offer без «звонка» — пользователь сам его вставил.
     */
    fun handleSignal(raw: String, auto: Boolean = false, autoAccept: Boolean = false): SignalResult {
        val text = SignalCodec.extract(raw) ?: raw
        val decoded = SignalCodec.decode(text).getOrElse {
            return SignalResult.Invalid(it.message ?: "Повреждённый или неверный код")
        }
        val key = text.filterNot { it.isWhitespace() }.hashCode()
        val remoteId = decoded.payload.identity
        if (remoteId != null && remoteId.equals(myIdentity, ignoreCase = true)) return SignalResult.Own

        return when (decoded.type) {
            SignalType.OFFER -> {
                if (auto) {
                    val age = abs(System.currentTimeMillis() - decoded.payload.ts)
                    if (key in handledSignals || age > AUTO_OFFER_MAX_AGE_MS) return SignalResult.Ignored
                }
                if (state.isActive) {
                    // То же приглашение могло уже прийти через релеи.
                    val sameCall = decoded.payload.callId != null && decoded.payload.callId == callId
                    return if (sameCall || key in handledSignals) SignalResult.Ignored else SignalResult.Busy
                }
                handledSignals += key
                val id = decoded.payload.callId ?: UUID.randomUUID().toString()
                if (id in finishedCallIds) return SignalResult.Ignored
                ring(decoded, code = remoteId.orEmpty(), id = id, viaRelay = false)
                if (autoAccept) acceptIncoming()
                SignalResult.Ringing
            }
            SignalType.ANSWER -> {
                if (state != CallState.WAITING_FOR_ANSWER) {
                    return if (auto) SignalResult.Ignored else SignalResult.Unexpected
                }
                if (auto && key in handledSignals) return SignalResult.Ignored
                handledSignals += key
                applyRemoteAnswer(decoded)
                SignalResult.AnswerApplied
            }
        }
    }

    private fun ring(offer: DecodedSignal, code: String, id: String, viaRelay: Boolean) {
        val gen = beginCall()
        callId = id
        val name = contacts.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name
            ?: "Неизвестный абонент"
        pendingOffer = offer
        _uiState.update {
            freshState().copy(
                state = CallState.RINGING,
                isIncoming = true,
                contactName = name,
                contactCode = code,
                viaRelay = viaRelay,
                statusMessage = "Входящий звонок"
            )
        }
        ensureService()
        startTelecom(name, code, incoming = true)
        main.postDelayed({
            if (gen == generation && state == CallState.RINGING) {
                CallNotifications.showMissedCall(app, name)
                finish(DisconnectCause.MISSED, "Пропущенный звонок", graceful = false)
            }
        }, RING_TIMEOUT_MS)
    }

    // ------------------------------------------------------- сигналы через релеи

    /** Сообщение сигнального канала. Отправитель уже проверен криптографически. */
    fun onRelayMessage(fromCode: String, message: CallMessage) {
        if (abs(System.currentTimeMillis() - message.ts) > RELAY_MESSAGE_MAX_AGE_MS) return
        if (message.t == CallMessage.OFFER) {
            onRelayOffer(fromCode, message)
            return
        }
        val current = _uiState.value
        if (message.id != callId || !current.state.isActive ||
            !fromCode.equals(current.contactCode, ignoreCase = true)
        ) {
            return
        }
        when (message.t) {
            CallMessage.RINGING -> if (current.state == CallState.WAITING_FOR_ANSWER && !current.remoteRinging) {
                _uiState.update { it.copy(remoteRinging = true, peerUnreachable = false, statusMessage = "Звонит…") }
                startRingback()
                val gen = generation
                main.postDelayed({
                    if (gen == generation && state == CallState.WAITING_FOR_ANSWER) {
                        sendToPeer(CallMessage.CANCEL)
                        finish(DisconnectCause.LOCAL, "Нет ответа", graceful = false)
                    }
                }, NO_ANSWER_TIMEOUT_MS)
            }
            CallMessage.ANSWER -> if (current.state == CallState.WAITING_FOR_ANSWER) {
                val decoded = message.signal?.let { SignalCodec.decode(it).getOrNull() }
                if (decoded != null && decoded.type == SignalType.ANSWER) applyRemoteAnswer(decoded)
            }
            CallMessage.REJECT -> if (current.state == CallState.WAITING_FOR_ANSWER) {
                finish(DisconnectCause.REMOTE, "Вызов отклонён", graceful = false)
            }
            CallMessage.BUSY -> if (current.state == CallState.WAITING_FOR_ANSWER) {
                finish(DisconnectCause.REMOTE, "Абонент занят", graceful = false)
            }
            CallMessage.CANCEL, CallMessage.BYE -> {
                if (current.state == CallState.RINGING) {
                    CallNotifications.showMissedCall(app, current.contactName)
                    finish(DisconnectCause.MISSED, "", graceful = false, showEnded = false)
                } else {
                    finish(DisconnectCause.REMOTE, "Собеседник завершил звонок", graceful = false)
                }
            }
        }
    }

    private fun onRelayOffer(fromCode: String, message: CallMessage) {
        val signal = message.signal ?: return
        if (message.id in finishedCallIds) {
            // Наш отказ мог потеряться — вызывающий всё ещё повторяет приглашение.
            if (message.id in rejectedCallIds) {
                RelaySignaling.send(fromCode, CallMessage(CallMessage.REJECT, message.id))
            }
            return
        }
        if (message.id == callId && state.isActive) {
            // Вызывающий повторяет приглашение, пока не получит подтверждение.
            if (state == CallState.RINGING) sendToPeer(CallMessage.RINGING)
            return
        }
        val known = contacts.any { it.code.equals(fromCode, ignoreCase = true) }
        if (!known && settings.contactsOnly.value) {
            Log.d(TAG, "Call from unknown code ignored")
            return
        }
        val decoded = SignalCodec.decode(signal).getOrNull() ?: return
        if (decoded.type != SignalType.OFFER) return
        if (state.isActive) {
            RelaySignaling.send(fromCode, CallMessage(CallMessage.BUSY, message.id))
            return
        }
        ring(decoded, code = fromCode, id = message.id, viaRelay = true)
        sendToPeer(CallMessage.RINGING)
    }

    /** Отправляет служебное сообщение собеседнику текущего звонка. */
    private fun sendToPeer(type: String, signal: String? = null) {
        val code = _uiState.value.contactCode
        if (callId.isBlank() || !CodeValidator.isValid(code)) return
        RelaySignaling.send(code, CallMessage(type, callId, signal))
    }

    /** Повторяет сообщение: события эфемерны, а собеседник мог быть не на связи. */
    private fun startResend(type: String, signal: String, attempts: Int, intervalMs: Long, done: () -> Boolean, onExhausted: () -> Unit = {}) {
        resendJob?.cancel()
        if (!CodeValidator.isValid(_uiState.value.contactCode)) return
        val gen = generation
        resendJob = scope.launch {
            repeat(attempts) {
                if (gen != generation || done()) return@launch
                sendToPeer(type, signal)
                delay(intervalMs)
            }
            if (gen == generation && !done()) onExhausted()
        }
    }

    private fun startRingback() {
        stopRingback()
        try {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 50).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ringback tone unavailable", e)
        }
    }

    private fun stopRingback() {
        try {
            ringback?.let {
                it.stopTone()
                it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ringback release failed", e)
        }
        ringback = null
    }

    private fun ensureService() {
        if (CallService.start(app)) {
            serviceFallback = false
        } else {
            serviceFallback = true
            CallNotifications.notifyDirect(app, _uiState.value)
        }
    }

    /** @return false, если принять нельзя (нет звонка или нет доступа к микрофону). */
    fun acceptIncoming(): Boolean {
        if (state != CallState.RINGING) return false
        val offer = pendingOffer ?: return false
        if (!hasMicPermission()) return false
        val gen = generation
        pendingOffer = null

        _uiState.update { it.copy(state = CallState.CREATING_ANSWER, statusMessage = "Соединяемся…") }
        // Приложение сейчас на экране — сервис с доступом к микрофону точно запустится.
        ensureService()
        telecom.answer()
        startAudio()

        val manager = createPeer(gen) ?: return true
        manager.setRemoteDescription(
            sdp = SessionDescription(SessionDescription.Type.OFFER, offer.payload.sdp),
            onSuccess = {
                main.post {
                    if (gen != generation) return@post
                    offer.payload.candidates.forEach { addRemoteCandidate(it) }
                    manager.createAnswer(
                        onSuccess = { sdp -> main.post { if (gen == generation) onLocalSdpReady(sdp) } },
                        onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, "Не удалось создать ответ")) } }
                    )
                }
            },
            onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, "Не удалось применить приглашение")) } }
        )
        return true
    }

    fun declineIncoming() {
        if (state != CallState.RINGING) return
        if (rejectedCallIds.size > 200) rejectedCallIds.clear()
        rejectedCallIds += callId
        sendToPeer(CallMessage.REJECT)
        finish(DisconnectCause.REJECTED, "Вызов отклонён", graceful = false, showEnded = false)
    }

    private fun applyRemoteAnswer(answer: DecodedSignal) {
        val gen = generation
        stopRingback()
        resendJob?.cancel()
        _uiState.update {
            it.copy(
                state = CallState.CONNECTING,
                signalToCopy = null,
                peerUnreachable = false,
                statusMessage = "Соединяемся…",
                errorMessage = null
            )
        }
        pcm?.setRemoteDescription(
            sdp = SessionDescription(SessionDescription.Type.ANSWER, answer.payload.sdp),
            onSuccess = {
                main.post { if (gen == generation) answer.payload.candidates.forEach { addRemoteCandidate(it) } }
            },
            onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, "Не удалось применить ответ")) } }
        )
    }

    // --------------------------------------------------------- управление звонком

    fun toggleMute() = setMuted(!_uiState.value.isMuted)

    fun setMuted(muted: Boolean) {
        if (!state.isActive || state == CallState.RINGING) return
        if (!muted) {
            // Гарнитура могла выключить микрофон на уровне системы — включаем обратно.
            runCatching {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (am.isMicrophoneMute) am.isMicrophoneMute = false
            }
        }
        _uiState.update { it.copy(isMuted = muted) }
        applyMicState()
        pcm?.sendControl(MSG_MUTE + if (muted) "1" else "0")
    }

    private fun applyMicState() {
        val s = _uiState.value
        pcm?.setMicrophoneEnabled(!s.isMuted && !s.isOnHold)
    }

    fun setAudioRoute(route: AudioRoute) {
        if (!state.isActive) return
        if (!telecom.requestRoute(route)) audio.setRoute(route)
    }

    /** Динамик ↔ обычный маршрут (трубка / гарнитура). */
    fun toggleSpeaker() {
        val s = _uiState.value
        val target = if (s.audioRoute == AudioRoute.SPEAKER) {
            s.availableRoutes.firstOrNull { it != AudioRoute.SPEAKER } ?: return
        } else {
            AudioRoute.SPEAKER
        }
        setAudioRoute(target)
    }

    /** Шумоподавление в обе стороны: наш микрофон и звук собеседника. */
    fun setNoiseSuppression(enabled: Boolean) {
        settings.noiseSuppression = enabled
        _uiState.update { it.copy(noiseSuppression = enabled) }
        pcm?.setNoiseSuppression(enabled)
    }

    fun hangup() {
        when {
            state == CallState.RINGING -> declineIncoming()
            state.isActive -> endByUser()
            else -> dismiss()
        }
    }

    private fun endByUser() {
        val current = _uiState.value
        val notAnsweredYet = !current.isIncoming &&
            (current.state == CallState.CREATING_OFFER || current.state == CallState.WAITING_FOR_ANSWER)
        pcm?.sendControl(MSG_BYE)
        sendToPeer(if (notAnsweredYet) CallMessage.CANCEL else CallMessage.BYE)
        finish(DisconnectCause.LOCAL, "Звонок завершён", graceful = true)
    }

    /** Закрывает экран завершённого / неудачного звонка. */
    fun dismiss() {
        if (!state.isActive) _uiState.update { freshState() }
    }

    // ------------------------------------------------------------------ внутреннее

    private fun freshState() = CallUiState(noiseSuppression = _uiState.value.noiseSuppression)

    /** Сбрасывает всё от предыдущего звонка и возвращает номер нового поколения. */
    private fun beginCall(): Int {
        releaseResources(DisconnectCause.LOCAL, graceful = false)
        useFallbackRouting = false
        return generation
    }

    private fun startTelecom(name: String, code: String, incoming: Boolean) {
        if (!telecom.start(name, code, incoming)) {
            useFallbackRouting = true
        }
    }

    private fun startAudio() {
        audio.start()
        ensureFallbackRouting()
    }

    private fun ensureFallbackRouting() {
        if (!useFallbackRouting || telecom.isReady) return
        audio.startRouting { current, available ->
            _uiState.update { it.copy(audioRoute = current, availableRoutes = available) }
        }
    }

    private fun createPeer(gen: Int): PeerConnectionManager? {
        return try {
            val manager = PeerConnectionManager(app, useTurn = true)
            pcm = manager
            manager.createPeerConnection(PeerListener(gen), _uiState.value.noiseSuppression)
            manager
        } catch (e: Throwable) {
            Log.e(TAG, "createPeer failed", e)
            fail("Не удалось запустить звонок (${e.message ?: e.javaClass.simpleName})")
            null
        }
    }

    private fun onLocalSdpReady(sdp: SessionDescription) {
        pendingLocalSdp = sdp
        if (!_uiState.value.viaRelay) _uiState.update { it.copy(statusMessage = "Ищем маршруты связи…") }
        val gen = generation
        main.postDelayed({
            if (gen == generation) finalizeLocalSignal("timeout")
        }, ICE_GATHER_TIMEOUT_MS)
    }

    private fun finalizeLocalSignal(reason: String) {
        if (signalFinalized) return
        val sdp = pendingLocalSdp ?: return
        val manager = pcm ?: return
        signalFinalized = true

        val candidates = manager.getCollectedCandidates().map {
            "${it.sdpMid}|${it.sdpMLineIndex}|${it.sdp}"
        }
        Log.d(TAG, "Finalize signal ($reason), candidates=${candidates.size}")

        val isOffer = sdp.type == SessionDescription.Type.OFFER
        val signal = if (isOffer) {
            SignalingHelper.createOffer(sdp.description, candidates, myIdentity, callId)
        } else {
            SignalingHelper.createAnswer(sdp.description, candidates, myIdentity, callId)
        }
        // Свой сигнал не должен «позвонить» нам же из буфера обмена.
        handledSignals += signal.hashCode()

        _uiState.update {
            // Соединение могло установиться раньше, чем сработал таймер ICE.
            if (it.state == CallState.CONNECTED) return@update it
            it.copy(
                state = if (isOffer) CallState.WAITING_FOR_ANSWER else CallState.CONNECTING,
                signalToCopy = signal,
                statusMessage = when {
                    !isOffer && it.viaRelay -> "Соединяемся…"
                    !isOffer -> "Отправьте ответ собеседнику"
                    it.viaRelay -> "Вызываем…"
                    else -> "Отправьте приглашение собеседнику"
                }
            )
        }

        // Автоматическая доставка через релеи; ручная отправка остаётся запасным путём.
        if (isOffer) {
            startResend(
                CallMessage.OFFER, signal, OFFER_RESEND_ATTEMPTS, OFFER_RESEND_MS,
                done = { state != CallState.WAITING_FOR_ANSWER || _uiState.value.remoteRinging },
                onExhausted = {
                    _uiState.update {
                        it.copy(peerUnreachable = true, statusMessage = "Абонент не в сети")
                    }
                }
            )
        } else {
            startResend(
                CallMessage.ANSWER, signal, ANSWER_RESEND_ATTEMPTS, ANSWER_RESEND_MS,
                done = { state != CallState.CONNECTING }
            )
        }
    }

    private fun addRemoteCandidate(candidateSdp: String) {
        try {
            val parts = candidateSdp.split('|', limit = 3)
            val candidate = if (parts.size == 3) {
                IceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
            } else {
                IceCandidate("0", 0, candidateSdp)
            }
            pcm?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add candidate: $candidateSdp", e)
        }
    }

    private fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        when (newState) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                val first = _uiState.value.connectedAt == 0L
                stopRingback()
                resendJob?.cancel()
                _uiState.update {
                    it.copy(
                        state = CallState.CONNECTED,
                        signalToCopy = null,
                        isReconnecting = false,
                        connectedAt = if (first) SystemClock.elapsedRealtime() else it.connectedAt,
                        statusMessage = "На линии",
                        errorMessage = null
                    )
                }
                if (first) {
                    telecom.setActive()
                    startStatsPolling()
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                if (state != CallState.CONNECTED) return
                _uiState.update {
                    it.copy(isReconnecting = true, quality = LinkQuality.POOR, statusMessage = "Связь прервалась, восстанавливаем…")
                }
                val gen = generation
                main.postDelayed({
                    if (gen == generation && _uiState.value.isReconnecting) {
                        finish(DisconnectCause.REMOTE, "Связь потеряна", graceful = false)
                    }
                }, RECONNECT_TIMEOUT_MS)
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                if (_uiState.value.connectedAt != 0L) {
                    finish(DisconnectCause.REMOTE, "Связь потеряна", graceful = false)
                } else {
                    fail("Соединение не установлено. Проверьте VPN и сеть, затем попробуйте снова.")
                }
            }
            PeerConnection.PeerConnectionState.CONNECTING -> {
                if (state == CallState.CONNECTING && _uiState.value.signalToCopy == null) {
                    _uiState.update { it.copy(statusMessage = "Соединяемся…") }
                }
            }
            else -> {}
        }
    }

    private fun onControlMessage(message: String) {
        when {
            message == MSG_BYE -> {
                if (state.isActive) finish(DisconnectCause.REMOTE, "Собеседник завершил звонок", graceful = false)
            }
            message.startsWith(MSG_MUTE) -> {
                _uiState.update { it.copy(isRemoteMuted = message.endsWith("1")) }
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        val gen = generation
        statsJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val manager = pcm ?: break
                manager.pollStats { stats -> main.post { if (gen == generation) onStats(stats) } }
            }
        }
    }

    private fun onStats(stats: LinkStats) {
        if (state != CallState.CONNECTED || _uiState.value.isReconnecting) return
        val rtt = stats.rttMs ?: 0.0
        val quality = when {
            stats.lossPercent > 12 || rtt > 700 -> LinkQuality.POOR
            stats.lossPercent > 4 || rtt > 350 -> LinkQuality.FAIR
            else -> LinkQuality.GOOD
        }
        if (quality != _uiState.value.quality) _uiState.update { it.copy(quality = quality) }
    }

    private fun humanizeError(raw: String, fallback: String): String {
        val lower = raw.lowercase()
        return when {
            "permission" in lower || "record" in lower ->
                "Нет доступа к микрофону. Разрешите его в настройках приложения."
            "network" in lower || "ice" in lower ->
                "Проблема с сетью. Проверьте VPN и интернет."
            raw.isBlank() -> fallback
            else -> "$fallback ($raw)"
        }
    }

    /** Освобождает ресурсы звонка и делает все отложенные колбэки недействительными. */
    private fun releaseResources(telecomCause: Int, graceful: Boolean) {
        generation++
        statsJob?.cancel()
        statsJob = null
        resendJob?.cancel()
        resendJob = null
        stopRingback()
        if (callId.isNotBlank()) {
            finishedCallIds += callId
            if (finishedCallIds.size > 200) finishedCallIds.iterator().let { it.next(); it.remove() }
            callId = ""
        }
        val manager = pcm
        pcm = null
        if (manager != null) {
            manager.setMicrophoneEnabled(false)
            if (graceful) {
                // Даём «bye» уйти по data channel до закрытия соединения.
                main.postDelayed({ manager.close() }, 250)
            } else {
                manager.close()
            }
        }
        audio.stop()
        telecom.stop(telecomCause)
        pendingOffer = null
        pendingLocalSdp = null
        signalFinalized = false
    }

    private fun finish(telecomCause: Int, message: String, graceful: Boolean, showEnded: Boolean = true) {
        val prev = _uiState.value
        releaseResources(telecomCause, graceful)
        if (!showEnded) {
            _uiState.update { freshState() }
            return
        }
        val duration = if (prev.connectedAt != 0L) (SystemClock.elapsedRealtime() - prev.connectedAt) / 1000 else 0L
        _uiState.update {
            freshState().copy(
                state = CallState.ENDED,
                isIncoming = prev.isIncoming,
                contactName = prev.contactName,
                contactCode = prev.contactCode,
                durationSec = duration,
                statusMessage = message
            )
        }
        val gen = generation
        main.postDelayed({
            if (gen == generation && state == CallState.ENDED) _uiState.update { freshState() }
        }, ENDED_SCREEN_MS)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        val prev = _uiState.value
        if (prev.state.isActive) sendToPeer(CallMessage.BYE)
        releaseResources(DisconnectCause.LOCAL, graceful = false)
        showFailure(prev.contactName, prev.contactCode, message)
    }

    private fun showFailure(name: String, code: String, message: String) {
        _uiState.update {
            freshState().copy(
                state = CallState.FAILED,
                contactName = name,
                contactCode = code,
                errorMessage = message,
                statusMessage = "Не удалось соединиться"
            )
        }
    }

    private class PeerListener(private val gen: Int) : PeerConnectionManager.Listener {
        private inline fun onMain(crossinline block: () -> Unit) {
            main.post { if (gen == generation) block() }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) =
            onMain { CallManager.onConnectionChange(state) }

        override fun onIceGatheringComplete() = onMain { finalizeLocalSignal("complete") }

        override fun onControlMessage(message: String) = onMain { CallManager.onControlMessage(message) }

        override fun onControlChannelOpen() = onMain {
            if (_uiState.value.isMuted) pcm?.sendControl(MSG_MUTE + "1")
        }
    }

    private val telecomListener = object : TelecomBridge.Listener {
        override fun onTelecomAnswer() {
            main.post {
                if (state == CallState.RINGING && !acceptIncoming()) {
                    // Без доступа к микрофону ответить с гарнитуры нельзя — откроем приложение.
                    CallNotifications.openApp(app, answer = true)
                }
            }
        }

        override fun onTelecomDisconnect() {
            main.post {
                when {
                    state == CallState.RINGING -> declineIncoming()
                    state.isActive -> endByUser()
                }
            }
        }

        override fun onTelecomHold(onHold: Boolean) {
            main.post {
                if (!state.isActive || _uiState.value.isOnHold == onHold) return@post
                _uiState.update { it.copy(isOnHold = onHold) }
                applyMicState()
                pcm?.setRemoteAudioEnabled(!onHold)
            }
        }

        override fun onTelecomRoutes(current: AudioRoute?, available: List<AudioRoute>) {
            main.post {
                if (!state.isActive) return@post
                _uiState.update {
                    it.copy(
                        audioRoute = current ?: it.audioRoute,
                        availableRoutes = available.ifEmpty { it.availableRoutes }
                    )
                }
            }
        }

        override fun onTelecomMuted(muted: Boolean) {
            main.post { if (_uiState.value.isMuted != muted) setMuted(muted) }
        }

        override fun onTelecomUnavailable() {
            main.post {
                if (!state.isActive) return@post
                useFallbackRouting = true
                ensureFallbackRouting()
            }
        }
    }
}
