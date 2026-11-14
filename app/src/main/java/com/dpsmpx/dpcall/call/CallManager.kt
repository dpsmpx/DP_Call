package com.dpsmpx.dpcall.call

import com.dpsmpx.dpcall.R
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
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.data.CallLogEntry
import com.dpsmpx.dpcall.data.CallLogRepository
import com.dpsmpx.dpcall.data.CallOutcome
import com.dpsmpx.dpcall.data.ContactsRepository
import com.dpsmpx.dpcall.data.IdentityRepository
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.signaling.DecodedSignal
import com.dpsmpx.dpcall.signaling.SdpPayload
import com.dpsmpx.dpcall.signaling.SignalCodec
import com.dpsmpx.dpcall.signaling.SignalType
import com.dpsmpx.dpcall.signaling.SignalingHelper
import com.dpsmpx.dpcall.signaling.relay.CallMessage
import com.dpsmpx.dpcall.util.AppLog
import com.dpsmpx.dpcall.util.CodeValidator
import com.dpsmpx.dpcall.webrtc.AudioCallManager
import com.dpsmpx.dpcall.webrtc.AudioRoute
import com.dpsmpx.dpcall.webrtc.BitrateController
import com.dpsmpx.dpcall.webrtc.CallState
import com.dpsmpx.dpcall.webrtc.HearQuality
import com.dpsmpx.dpcall.webrtc.HearingEstimate
import com.dpsmpx.dpcall.webrtc.LinkStats
import com.dpsmpx.dpcall.webrtc.PeerConnectionManager
import kotlinx.coroutines.CompletableDeferred
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
    /** Собеседник есть в контактах (иначе на экране предлагается его добавить). */
    val isKnownContact: Boolean = true,
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
    /** Подтверждения «звонит» всё нет: вызов продолжается, но ручная отправка уже предложена. */
    val slowToReach: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    val noiseSuppression: Boolean = true,
    val quality: LinkQuality = LinkQuality.UNKNOWN,
    /** Насколько хорошо слышно собеседника: доля потерянного и восполненного звука. */
    val hearing: HearQuality = HearQuality.UNKNOWN,
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
    /** Ручной обмен: ждём все маршруты, досылать их потом нечем. */
    private const val ICE_GATHER_TIMEOUT_MS = 8_000L
    /** Через релеи: отправляем сразу, остальные маршруты досылаем по мере нахождения. */
    private const val ICE_FAST_GATHER_MS = 1_000L
    private const val CANDIDATE_FLUSH_MS = 300L
    /** Сколько ждать соединения после первого отказа ICE, прежде чем сдаться. */
    private const val SETUP_GRACE_RELAY_MS = 20_000L
    private const val SETUP_GRACE_MANUAL_MS = 180_000L
    private const val RING_TIMEOUT_MS = 60_000L
    private const val RECONNECT_TIMEOUT_MS = 30_000L
    private const val UNKNOWN_CALL_LIMIT = 3
    private const val UNKNOWN_CALL_WINDOW_MS = 10 * 60_000L
    private const val RESTART_MIN_INTERVAL_MS = 4_000L
    private const val RESTART_GATHER_TIMEOUT_MS = 4_000L
    private const val ENDED_SCREEN_MS = 1_800L
    private const val AUTO_OFFER_MAX_AGE_MS = 30 * 60_000L
    /**
     * Паузы между повторами приглашения: чаще в первые секунды, когда собеседник,
     * скорее всего, на связи и потерялось одно сообщение; всего около 45 секунд.
     */
    private val OFFER_RESEND_DELAYS = listOf(1_500L, 2_500L, 4_000L) + List(7) { 5_000L }
    private val ANSWER_RESEND_DELAYS = listOf(1_000L, 1_500L, 2_500L, 3_000L, 3_000L, 3_000L)
    /** Повторы списка маршрутов: каждое сообщение может потеряться по пути. */
    private val CANDIDATE_RESEND_DELAYS = listOf(1_500L, 3_000L)
    /** Нет ни одного релея: проверяем связь чаще, но не дольше общего срока повторов. */
    private const val NO_RELAY_RETRY_MS = 1_000L
    private const val NO_ANSWER_TIMEOUT_MS = 65_000L
    /** С запасом на неточные часы устройств; повторы отсекаются по идентификатору звонка. */
    private const val RELAY_MESSAGE_MAX_AGE_MS = 5 * 60_000L
    /** Через сколько без подтверждения «звонит» честно говорим, что собеседник пока не отвечает. */
    private const val SLOW_REACH_MS = 12_000L
    /** Отметка о пропущенном старше срока хранения на релеях — повтор или сбитые часы. */
    private const val MISSED_MAX_AGE_MS = 49 * 3600_000L
    /** Сколько замеров (по 2 с) усредняет индикатор слышимости. */
    private const val HEARING_WINDOW = 3
    private const val MSG_BYE = "bye"
    private const val MSG_MUTE = "mute:"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var audio: AudioCallManager
    private lateinit var telecom: TelecomBridge
    private var pcm: PeerConnectionManager? = null

    private var myIdentity: String? = null
    /** Секретный ключ — для подписи приглашений и ответов. */
    private var mySecret: ByteArray? = null

    /**
     * Одноразовый для процесса токен: только уведомление самого приложения может
     * попросить MainActivity принять звонок. Без него стороннее приложение могло бы
     * запустить Activity с нужным действием и «снять трубку» за пользователя.
     */
    val answerToken: String = UUID.randomUUID().toString()

    /** Время последних вызовов с незнакомых кодов — для ограничения частоты. */
    private val unknownCallTimes = ArrayDeque<Long>()
    private val unknownNoticeTimes = ArrayDeque<Long>()
    private var contacts: List<Contact> = emptyList()
    /** Контакты прочитаны с диска: до этого нельзя судить, знаком ли нам звонивший. */
    private val contactsLoaded = CompletableDeferred<Unit>()
    /** Отметка о пропущенном для текущего исходящего звонка уже оставлена. */
    private var missedNoticeSent = false

    /** Увеличивается при каждом новом звонке и при завершении — отсекает устаревшие колбэки. */
    private var generation = 0
    private var pendingOffer: DecodedSignal? = null
    private var pendingLocalSdp: SessionDescription? = null
    private var signalFinalized = false
    private var peerSignalVersion = 1
    private var useFallbackRouting = false
    private var statsJob: Job? = null
    private var resendJob: Job? = null
    private var candidateResendJob: Job? = null

    // Пересогласование соединения (ICE restart) при смене сети или обрыве.
    private enum class Restart { NONE, OFFERING, ANSWERING }
    private var restart = Restart.NONE
    private var restartSent = false
    private var restartJob: Job? = null
    private var lastRestartAt = 0L
    private var lastReOfferKey = 0
    private var lastReAnswer: String? = null
    /** Растёт при каждом восстановлении связи — чтобы старый таймер обрыва не завершил звонок. */
    private var connectionEpoch = 0

    // Маршруты собеседника: до установки его описания копятся, потом применяются.
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = LinkedHashSet<String>()
    private val appliedRemoteCandidates = HashSet<String>()
    private var candidateFlushScheduled = false
    private var sentCandidateCount = 0
    private var setupGraceScheduled = false

    private lateinit var callLog: CallLogRepository
    private var callStartedAt = 0L
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
        callLog = CallLogRepository(app)
        _uiState.update { it.copy(noiseSuppression = settings.noiseSuppression) }
        scope.launch {
            // Журнал переходов состояния — для отчёта об ошибке.
            var last = ""
            uiState.collect { s ->
                val line = "state=${s.state} incoming=${s.isIncoming} relay=${s.viaRelay} ringing=${s.remoteRinging} " +
                    "reconnecting=${s.isReconnecting} muted=${s.isMuted} route=${s.audioRoute} status=\"${s.statusMessage}\"" +
                    (s.errorMessage?.let { " error=\"$it\"" } ?: "")
                if (line != last) {
                    last = line
                    AppLog.add(TAG, line)
                }
            }
        }
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
            reloadIdentity()
            identityRepo.identityFlow.collect { id -> id?.let { myIdentity = it.value } }
        }
        scope.launch {
            ContactsRepository(app).contactsFlow.collect { list ->
                contacts = list
                contactsLoaded.complete(Unit)
                // При холодном старте через «Поделиться» звонок может начаться раньше,
                // чем загрузятся контакты, — подставляем имя, как только оно известно.
                val current = _uiState.value
                if (current.state != CallState.IDLE && current.contactCode.isNotBlank()) {
                    list.firstOrNull { it.code.equals(current.contactCode, ignoreCase = true) }?.let { contact ->
                        if (contact.name != current.contactName || !current.isKnownContact) {
                            _uiState.update { it.copy(contactName = contact.name, isKnownContact = true) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun reloadIdentity() {
        runCatching {
            val keys = IdentityRepository(app).loadKeys()
            mySecret = keys.secret
            myIdentity = keys.code
        }
    }

    /** Ключи заменены (восстановление из резервной копии): подписываем уже новыми. */
    fun onIdentityChanged() {
        scope.launch { reloadIdentity() }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- исходящий

    fun startOutgoing(contactName: String, contactCode: String) {
        if (state.isActive) return
        if (com.dpsmpx.dpcall.carrier.CarrierTransport.active &&
            (!com.dpsmpx.dpcall.carrier.CarrierTransport.ready ||
                !contactCode.equals(com.dpsmpx.dpcall.carrier.CarrierTransport.status.value.peerCode, ignoreCase = true))) {
            showFailure(contactName, contactCode, app.getString(R.string.carrier_join_first))
            return
        }
        if (!hasMicPermission()) {
            showFailure(contactName, contactCode, app.getString(R.string.error_mic_permission))
            return
        }
        val gen = beginCall()
        callId = UUID.randomUUID().toString()
        callStartedAt = System.currentTimeMillis()
        val reachable = CodeValidator.isValid(contactCode)
        _uiState.update {
            freshState().copy(
                state = CallState.CREATING_OFFER,
                isIncoming = false,
                contactName = contactName,
                contactCode = contactCode,
                viaRelay = reachable,
                statusMessage = if (reachable) app.getString(R.string.status_calling) else app.getString(R.string.status_preparing_invite)
            )
        }
        ensureService()
        startTelecom(contactName, contactCode, incoming = false)
        startAudio()

        val manager = createPeer(gen) ?: return
        manager.createOffer(
            onSuccess = { sdp -> main.post { if (gen == generation) onLocalSdpReady(sdp) } },
            onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, app.getString(R.string.error_create_invite))) } }
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
            return SignalResult.Invalid(app.getString(R.string.error_bad_signal))
        }
        val key = text.filterNot { it.isWhitespace() }.hashCode()
        val remoteId = decoded.payload.identity
        if (com.dpsmpx.dpcall.carrier.CarrierTransport.active &&
            (!com.dpsmpx.dpcall.carrier.CarrierTransport.ready ||
                !remoteId.equals(com.dpsmpx.dpcall.carrier.CarrierTransport.status.value.peerCode, ignoreCase = true))) {
            return if (auto) SignalResult.Ignored else SignalResult.Invalid(app.getString(R.string.carrier_join_first))
        }
        if (remoteId != null && remoteId.equals(myIdentity, ignoreCase = true)) return SignalResult.Own
        // Ручная передача идёт через чужие приложения и буфер обмена: единственное, что
        // доказывает автора сигнала, — подпись его ключом. Без неё имя звонящего можно
        // было бы подделать, а чужой «ответ» — подставить в исходящий звонок.
        if (!decoded.verified || remoteId == null) {
            AppLog.add(TAG, "manual signal rejected: signature missing or invalid")
            return if (auto) SignalResult.Ignored else SignalResult.Invalid(app.getString(R.string.error_unsigned_signal))
        }

        return when (decoded.type) {
            SignalType.OFFER -> {
                val age = abs(System.currentTimeMillis() - decoded.payload.ts)
                if (auto && (key in handledSignals || age > AUTO_OFFER_MAX_AGE_MS)) return SignalResult.Ignored
                // Старое приглашение бесполезно (звонящий уже не ждёт) и может быть повтором.
                if (age > AUTO_OFFER_MAX_AGE_MS) {
                    return SignalResult.Invalid(app.getString(R.string.error_signal_expired))
                }
                if (state.isActive) {
                    // То же приглашение могло уже прийти через релеи.
                    val sameCall = decoded.payload.callId != null && decoded.payload.callId == callId
                    return if (sameCall || key in handledSignals) SignalResult.Ignored else SignalResult.Busy
                }
                handledSignals += key
                val id = decoded.payload.callId ?: UUID.randomUUID().toString()
                if (id in finishedCallIds) return SignalResult.Ignored
                ring(decoded, code = remoteId, id = id, viaRelay = false)
                if (autoAccept) acceptIncoming()
                SignalResult.Ringing
            }
            SignalType.ANSWER -> {
                if (state != CallState.WAITING_FOR_ANSWER) {
                    return if (auto) SignalResult.Ignored else SignalResult.Unexpected
                }
                if (auto && key in handledSignals) return SignalResult.Ignored
                // Ответ принимается только от того, кому мы звоним.
                if (!remoteId.equals(_uiState.value.contactCode, ignoreCase = true)) {
                    AppLog.add(TAG, "manual answer rejected: signed by someone else")
                    return if (auto) SignalResult.Ignored else SignalResult.Invalid(app.getString(R.string.error_answer_wrong_sender))
                }
                if (decoded.payload.callId != callId) return SignalResult.Invalid(app.getString(R.string.answer_not_expected))
                handledSignals += key
                applyRemoteAnswer(decoded)
                SignalResult.AnswerApplied
            }
        }
    }

    private fun ring(offer: DecodedSignal, code: String, id: String, viaRelay: Boolean) {
        val gen = beginCall()
        callId = id
        // Телефон звонит — отметка о пропущенном для этого же звонка уже не нужна.
        settings.markCallSeen(id)
        callStartedAt = System.currentTimeMillis()
        val contact = contacts.firstOrNull { it.code.equals(code, ignoreCase = true) }
        val name = contact?.name ?: app.getString(R.string.unknown_caller)
        pendingOffer = offer
        _uiState.update {
            freshState().copy(
                state = CallState.RINGING,
                isIncoming = true,
                contactName = name,
                contactCode = code,
                isKnownContact = contact != null,
                viaRelay = viaRelay,
                statusMessage = app.getString(R.string.incoming_call)
            )
        }
        ensureService()
        startTelecom(name, code, incoming = true)
        main.postDelayed({
            if (gen == generation && state == CallState.RINGING) {
                CallNotifications.showMissedCall(app, name, code)
                finish(DisconnectCause.MISSED, app.getString(R.string.missed_call), graceful = false)
            }
        }, RING_TIMEOUT_MS)
    }

    // ------------------------------------------------------- сигналы через релеи

    /** Сообщение сигнального канала. Отправитель уже проверен криптографически. */
    fun onRelayMessage(fromCode: String, message: CallMessage) {
        AppLog.add(
            TAG,
            "relay in: ${message.t} call=${message.id.take(6)} mine=${callId.take(6)} " +
                "fromPeer=${fromCode.equals(_uiState.value.contactCode, ignoreCase = true)} " +
                "signal=${message.signal?.length ?: 0} ageMs=${System.currentTimeMillis() - message.ts}"
        )
        if (message.t == CallMessage.MISSED) {
            onMissedNotice(fromCode, message)
            return
        }
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
        peerSignalVersion = maxOf(peerSignalVersion, message.signalVersion.coerceIn(1, 2))
        when (message.t) {
            CallMessage.RINGING -> if (current.state == CallState.WAITING_FOR_ANSWER && !current.remoteRinging) {
                _uiState.update { it.copy(remoteRinging = true, peerUnreachable = false, slowToReach = false, statusMessage = app.getString(R.string.status_ringing)) }
                startRingback()
                val gen = generation
                main.postDelayed({
                    if (gen == generation && state == CallState.WAITING_FOR_ANSWER) {
                        sendToPeer(CallMessage.CANCEL)
                        finish(DisconnectCause.LOCAL, app.getString(R.string.no_answer), graceful = false)
                    }
                }, NO_ANSWER_TIMEOUT_MS)
            }
            CallMessage.ANSWER -> if (current.state == CallState.WAITING_FOR_ANSWER) {
                val decoded = message.signal?.let { SignalCodec.decode(it).getOrNull() }
                if (decoded != null && decoded.type == SignalType.ANSWER) applyRemoteAnswer(decoded)
            }
            CallMessage.REJECT -> if (current.state == CallState.WAITING_FOR_ANSWER) {
                finish(DisconnectCause.REMOTE, app.getString(R.string.call_declined), graceful = false)
            }
            CallMessage.BUSY -> if (current.state == CallState.WAITING_FOR_ANSWER) {
                finish(DisconnectCause.REMOTE, app.getString(R.string.peer_busy), graceful = false)
            }
            CallMessage.CANDIDATES -> message.signal?.lineSequence()?.filter { it.isNotBlank() }
                ?.take(SignalCodec.MAX_CANDIDATES)?.forEach { addRemoteCandidate(it) }
            CallMessage.RESTART -> if (!current.isIncoming) requestIceRestart()
            CallMessage.RE_OFFER -> message.signal?.let { onRestartOffer(it) }
            CallMessage.RE_ANSWER -> message.signal?.let { onRestartAnswer(it) }
            CallMessage.CANCEL, CallMessage.BYE -> {
                if (current.state == CallState.RINGING) {
                    CallNotifications.showMissedCall(app, current.contactName, current.contactCode)
                    finish(DisconnectCause.MISSED, "", graceful = false, showEnded = false)
                } else {
                    finish(DisconnectCause.REMOTE, app.getString(R.string.peer_ended), graceful = false)
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
        if (message.id == callId && state.isActive && fromCode.equals(_uiState.value.contactCode, ignoreCase = true)) {
            // Вызывающий повторяет приглашение, пока не получит подтверждение.
            if (state == CallState.RINGING) sendToPeer(CallMessage.RINGING)
            return
        }
        val known = contacts.any { it.code.equals(fromCode, ignoreCase = true) }
        val selectedCarrierPeer = com.dpsmpx.dpcall.carrier.CarrierTransport.active &&
            fromCode.equals(com.dpsmpx.dpcall.carrier.CarrierTransport.status.value.peerCode, ignoreCase = true)
        if (!known && settings.contactsOnly.value && !selectedCarrierPeer) {
            AppLog.add(TAG, "call from unknown code ignored (contacts only)")
            return
        }
        if (!known && !selectedCarrierPeer && state.isActive.not()) {
            // Не даём незнакомым кодам названивать: не больше нескольких звонков за интервал.
            val now = SystemClock.elapsedRealtime()
            while (unknownCallTimes.isNotEmpty() && now - unknownCallTimes.first() > UNKNOWN_CALL_WINDOW_MS) {
                unknownCallTimes.removeFirst()
            }
            if (unknownCallTimes.size >= UNKNOWN_CALL_LIMIT) {
                AppLog.add(TAG, "call from unknown code dropped (rate limit)")
                return
            }
            unknownCallTimes.addLast(now)
        }
        val decoded = SignalCodec.decode(signal).getOrNull() ?: return
        if (decoded.type != SignalType.OFFER) return
        if (state.isActive) {
            RelaySignaling.send(fromCode, CallMessage(CallMessage.BUSY, message.id))
            return
        }
        ring(decoded, code = fromCode, id = message.id, viaRelay = true)
        peerSignalVersion = message.signalVersion.coerceIn(1, 2)
        sendToPeer(CallMessage.RINGING)
    }

    /**
     * «Вам звонили»: вызов не дошёл, потому что мы были не в сети. Отметка хранится на
     * релеях и приходит при каждом подключении, поэтому уже учтённые отсекаются по
     * идентификатору звонка, сохранённому на диске.
     */
    private fun onMissedNotice(fromCode: String, message: CallMessage) {
        if (!settings.missedNotices.value) return
        val now = System.currentTimeMillis()
        if (now - message.ts > MISSED_MAX_AGE_MS || message.ts - now > RELAY_MESSAGE_MAX_AGE_MS) return
        scope.launch {
            contactsLoaded.await()
            val contact = contacts.firstOrNull { it.code.equals(fromCode, ignoreCase = true) }
            if (contact == null) {
                if (settings.contactsOnly.value) return@launch
                // Незнакомые коды не должны заваливать журнал: тот же предел, что и для звонков.
                val tick = SystemClock.elapsedRealtime()
                while (unknownNoticeTimes.isNotEmpty() && tick - unknownNoticeTimes.first() > UNKNOWN_CALL_WINDOW_MS) {
                    unknownNoticeTimes.removeFirst()
                }
                if (unknownNoticeTimes.size >= UNKNOWN_CALL_LIMIT) return@launch
            }
            if (!settings.markCallSeen(message.id)) return@launch
            if (contact == null) unknownNoticeTimes.addLast(SystemClock.elapsedRealtime())
            val name = contact?.name ?: app.getString(R.string.unknown_caller)
            AppLog.add(TAG, "missed call notice: call=${message.id.take(6)} known=${contact != null}")
            runCatching {
                callLog.add(
                    CallLogEntry(
                        code = fromCode, name = name, incoming = true,
                        outcome = CallOutcome.MISSED, startedAt = message.ts.coerceAtMost(now)
                    )
                )
            }
            settings.addUnseenMissed()
            CallNotifications.showMissedCall(app, name, fromCode, at = message.ts.coerceAtMost(now))
        }
    }

    /**
     * Оставляет собеседнику отметку о звонке, который до него не дошёл. Нужна только
     * если его телефон не звонил: иначе он увидит пропущенный и так.
     */
    private fun leaveMissedNotice() {
        val current = _uiState.value
        if (missedNoticeSent || !settings.missedNotices.value) return
        if (current.isIncoming || !current.viaRelay || current.remoteRinging || !signalFinalized) return
        if (current.state != CallState.WAITING_FOR_ANSWER) return
        missedNoticeSent = true
        sendToPeer(CallMessage.MISSED)
    }

    /**
     * Отправляет служебное сообщение собеседнику текущего звонка.
     * @return сколько релеев его приняли.
     */
    private fun sendToPeer(type: String, signal: String? = null): Int {
        val code = _uiState.value.contactCode
        if (callId.isBlank() || !CodeValidator.isValid(code)) {
            AppLog.add(TAG, "relay out: $type SKIPPED (callId blank=${callId.isBlank()}, code valid=${CodeValidator.isValid(code)})")
            return 0
        }
        val accepted = RelaySignaling.send(code, CallMessage(type, callId, signal, signalVersion = 2))
        AppLog.add(TAG, "relay out: $type call=${callId.take(6)} signal=${signal?.length ?: 0} relays=$accepted")
        return accepted
    }

    /**
     * Повторяет сообщение: события эфемерны, а собеседник мог быть не на связи.
     * Пока не подключён ни один релей, отправка пробуется каждую секунду, но общий
     * срок повторов от этого не растёт.
     */
    private fun startResend(type: String, signal: () -> String?, delays: List<Long>, done: () -> Boolean, onExhausted: () -> Unit = {}) {
        resendJob?.cancel()
        if (!CodeValidator.isValid(_uiState.value.contactCode)) return
        val gen = generation
        resendJob = scope.launch {
            var budget = delays.sum()
            var index = 0
            while (budget > 0) {
                if (gen != generation || done()) return@launch
                // Каждый повтор собирается заново — с маршрутами, найденными к этому моменту.
                val accepted = signal()?.let { sendToPeer(type, it) } ?: 0
                val pause = if (accepted == 0) {
                    RelaySignaling.reconnectNow()
                    NO_RELAY_RETRY_MS
                } else {
                    delays.getOrElse(index++) { delays.last() }
                }.coerceAtMost(budget)
                delay(pause)
                budget -= pause
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

        _uiState.update { it.copy(state = CallState.CREATING_ANSWER, statusMessage = app.getString(R.string.status_connecting)) }
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
                    onRemoteDescriptionSet(offer.payload.candidates)
                    manager.createAnswer(
                        onSuccess = { sdp -> main.post { if (gen == generation) onLocalSdpReady(sdp) } },
                        onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, app.getString(R.string.error_create_answer))) } }
                    )
                }
            },
            onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, app.getString(R.string.error_apply_invite))) } }
        )
        return true
    }

    fun declineIncoming() {
        if (state != CallState.RINGING) return
        if (rejectedCallIds.size > 200) rejectedCallIds.clear()
        rejectedCallIds += callId
        sendToPeer(CallMessage.REJECT)
        finish(DisconnectCause.REJECTED, app.getString(R.string.call_declined), graceful = false, showEnded = false, outcome = CallOutcome.DECLINED)
    }

    private fun applyRemoteAnswer(answer: DecodedSignal) {
        val gen = generation
        stopRingback()
        resendJob?.cancel()
        _uiState.update {
            it.copy(
                state = CallState.CONNECTING,
                signalToCopy = null,
                remoteRinging = false,
                peerUnreachable = false,
                slowToReach = false,
                statusMessage = app.getString(R.string.status_connecting),
                errorMessage = null
            )
        }
        pcm?.setRemoteDescription(
            sdp = SessionDescription(SessionDescription.Type.ANSWER, answer.payload.sdp),
            onSuccess = {
                main.post { if (gen == generation) onRemoteDescriptionSet(answer.payload.candidates) }
            },
            onError = { err -> main.post { if (gen == generation) fail(humanizeError(err, app.getString(R.string.error_apply_answer))) } }
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
        leaveMissedNotice()
        sendToPeer(if (notAnsweredYet) CallMessage.CANCEL else CallMessage.BYE)
        finish(DisconnectCause.LOCAL, app.getString(R.string.call_ended), graceful = true)
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
            fail(app.getString(R.string.error_start_call, e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun onLocalSdpReady(sdp: SessionDescription) {
        pendingLocalSdp = sdp
        if (!_uiState.value.viaRelay) _uiState.update { it.copy(statusMessage = app.getString(R.string.status_gathering)) }
        val gen = generation
        val canTrickle = CodeValidator.isValid(_uiState.value.contactCode) && _uiState.value.viaRelay
        main.postDelayed({
            if (gen == generation) finalizeLocalSignal(if (canTrickle) "fast" else "timeout")
        }, if (canTrickle) ICE_FAST_GATHER_MS else ICE_GATHER_TIMEOUT_MS)
    }

    /** Текущий offer/answer со всеми найденными на этот момент маршрутами. */
    private fun buildLocalSignal(): String? {
        val sdp = pendingLocalSdp ?: return null
        val manager = pcm ?: return null
        val candidates = manager.getCollectedCandidates().map { "${it.sdpMid}|${it.sdpMLineIndex}|${it.sdp}" }
        val signal = if (sdp.type == SessionDescription.Type.OFFER) {
            SignalingHelper.createOffer(sdp.description, candidates, myIdentity, callId, mySecret)
        } else {
            SignalingHelper.createAnswer(sdp.description, candidates, myIdentity, callId, mySecret)
        }
        // Свой сигнал не должен «позвонить» нам же из буфера обмена.
        handledSignals += signal.hashCode()
        return if (peerSignalVersion >= 2) SignalCodec.compact(signal) else signal
    }

    /** Gathered routes may have changed since the call screen was first shown. */
    fun signalForSharing(compact: Boolean = false): String? {
        val signal = buildLocalSignal() ?: return null
        return if (compact) SignalCodec.compact(signal) else signal
    }

    private fun finalizeLocalSignal(reason: String) {
        if (signalFinalized) return
        val sdp = pendingLocalSdp ?: return
        val manager = pcm ?: return
        val signal = buildLocalSignal() ?: return
        signalFinalized = true
        sentCandidateCount = manager.getCollectedCandidates().size
        AppLog.add(TAG, "local ${sdp.type} ready ($reason), candidates: ${manager.localCandidateSummary()}")

        val isOffer = sdp.type == SessionDescription.Type.OFFER

        _uiState.update {
            // Соединение могло установиться раньше, чем сработал таймер ICE.
            if (it.state == CallState.CONNECTED) return@update it
            it.copy(
                state = if (isOffer) CallState.WAITING_FOR_ANSWER else CallState.CONNECTING,
                signalToCopy = signal,
                statusMessage = when {
                    !isOffer && it.viaRelay -> app.getString(R.string.status_connecting)
                    !isOffer -> app.getString(R.string.status_send_answer)
                    it.viaRelay -> app.getString(R.string.status_calling)
                    else -> app.getString(R.string.status_send_invite)
                }
            )
        }

        // Автоматическая доставка через релеи; ручная отправка остаётся запасным путём.
        if (isOffer) {
            startResend(
                CallMessage.OFFER, ::buildLocalSignal, OFFER_RESEND_DELAYS,
                done = { state != CallState.WAITING_FOR_ANSWER || _uiState.value.remoteRinging },
                onExhausted = {
                    // Отметку оставляем сразу: звонящий может просто закрыть приложение.
                    leaveMissedNotice()
                    _uiState.update {
                        it.copy(peerUnreachable = true, statusMessage = app.getString(R.string.peer_offline))
                    }
                }
            )
            val gen = generation
            main.postDelayed({
                val current = _uiState.value
                if (gen == generation && current.state == CallState.WAITING_FOR_ANSWER && current.viaRelay &&
                    !current.remoteRinging && !current.peerUnreachable
                ) {
                    _uiState.update { it.copy(slowToReach = true, statusMessage = app.getString(R.string.status_slow_to_reach)) }
                }
            }, SLOW_REACH_MS)
        } else {
            startResend(
                CallMessage.ANSWER, ::buildLocalSignal, ANSWER_RESEND_DELAYS,
                done = { state != CallState.CONNECTING }
            )
        }
    }

    /** Найден новый локальный маршрут: если offer/answer уже ушёл, досылаем отдельно. */
    private fun onLocalCandidate() {
        if (!signalFinalized || candidateFlushScheduled || restart != Restart.NONE) return
        if (state == CallState.CONNECTED) return
        candidateFlushScheduled = true
        val gen = generation
        main.postDelayed({
            if (gen == generation) {
                candidateFlushScheduled = false
                flushLocalCandidates()
            }
        }, CANDIDATE_FLUSH_MS)
    }

    private fun flushLocalCandidates() {
        val manager = pcm ?: return
        if (!signalFinalized || state == CallState.CONNECTED || !state.isActive) return
        val candidates = manager.getCollectedCandidates()
        if (candidates.size == sentCandidateCount) return
        sentCandidateCount = candidates.size
        AppLog.add(TAG, "local candidates now: ${manager.localCandidateSummary()}")
        // Отправляем весь список, а не только новые: сообщения эфемерны и могут теряться.
        val list = candidates.joinToString("\n") { "${it.sdpMid}|${it.sdpMLineIndex}|${it.sdp}" }
        sendToPeer(CallMessage.CANDIDATES, list)
        // Тот же список ещё пару раз, пока соединения нет; новый список заменяет повторы.
        candidateResendJob?.cancel()
        val gen = generation
        candidateResendJob = scope.launch {
            for (pause in CANDIDATE_RESEND_DELAYS) {
                delay(pause)
                if (gen != generation || state == CallState.CONNECTED || !state.isActive || restart != Restart.NONE) return@launch
                sendToPeer(CallMessage.CANDIDATES, list)
            }
        }
        // Приглашение для ручной отправки тоже должно содержать все маршруты.
        if (_uiState.value.signalToCopy != null) {
            buildLocalSignal()?.let { fresh -> _uiState.update { it.copy(signalToCopy = fresh) } }
        }
    }

    private fun onRemoteDescriptionSet(candidates: List<String>) {
        remoteDescriptionSet = true
        val all = candidates + pendingRemoteCandidates
        pendingRemoteCandidates.clear()
        all.forEach { addRemoteCandidate(it) }
    }

    private fun addRemoteCandidate(candidateSdp: String) {
        if (candidateSdp in appliedRemoteCandidates) return
        // Предел на звонок: собеседник не должен заставить нас перебирать тысячи адресов.
        if (appliedRemoteCandidates.size + pendingRemoteCandidates.size >= SignalCodec.MAX_CANDIDATES * 2) return
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates += candidateSdp
            return
        }
        appliedRemoteCandidates += candidateSdp
        try {
            val parts = candidateSdp.split('|', limit = 3)
            val candidate = if (parts.size == 3) {
                IceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
            } else {
                IceCandidate("0", 0, candidateSdp)
            }
            pcm?.addIceCandidate(candidate)
            val type = Regex(" typ (\\w+)").find(candidateSdp)?.groupValues?.get(1) ?: "?"
            AppLog.add(TAG, "remote candidate added: $type (total ${appliedRemoteCandidates.size})")
        } catch (e: Exception) {
            AppLog.add(TAG, "remote candidate rejected: $e")
        }
    }

    private fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        when (newState) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                val first = _uiState.value.connectedAt == 0L
                connectionEpoch++
                stopRingback()
                resendJob?.cancel()
                _uiState.update {
                    it.copy(
                        state = CallState.CONNECTED,
                        signalToCopy = null,
                        isReconnecting = false,
                        connectedAt = if (first) SystemClock.elapsedRealtime() else it.connectedAt,
                        statusMessage = app.getString(R.string.status_connected),
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
                markReconnecting()
                // Короткие провалы WebRTC чинит сам; если не вышло — ищем маршруты заново.
                val gen = generation
                val epoch = connectionEpoch
                main.postDelayed({
                    if (gen == generation && epoch == connectionEpoch) requestIceRestart()
                }, 2_500)
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                if (_uiState.value.connectedAt != 0L) {
                    markReconnecting()
                    requestIceRestart()
                } else if (!setupGraceScheduled) {
                    // Собеседник мог ещё не получить наш ответ или не дослать маршруты:
                    // первый отказ ICE — не приговор, соединение может подняться позже.
                    setupGraceScheduled = true
                    val gen = generation
                    val grace = if (_uiState.value.viaRelay) SETUP_GRACE_RELAY_MS else SETUP_GRACE_MANUAL_MS
                    AppLog.add(TAG, "ICE failed before connect, waiting ${grace / 1000}s for late candidates")
                    main.postDelayed({
                        if (gen == generation && state.isActive && _uiState.value.connectedAt == 0L) {
                            // Путь найден, а соединения нет — сорвалось рукопожатие DTLS (в России
                            // его режут по «отпечатку»); иначе не нашёлся сам путь (STUN/TURN).
                            val dtls = pcm?.iceConnected == true
                            AppLog.add(TAG, if (dtls) "failed: DTLS after ICE connected" else "failed: no ICE path")
                            fail(app.getString(if (dtls) R.string.error_dtls_blocked else R.string.error_no_path))
                        }
                    }, grace)
                }
            }
            PeerConnection.PeerConnectionState.CONNECTING -> {
                if (state == CallState.CONNECTING && _uiState.value.signalToCopy == null) {
                    _uiState.update { it.copy(statusMessage = app.getString(R.string.status_connecting)) }
                }
            }
            else -> {}
        }
    }

    private fun markReconnecting() {
        if (_uiState.value.isReconnecting) return
        _uiState.update {
            it.copy(isReconnecting = true, quality = LinkQuality.POOR, statusMessage = app.getString(R.string.status_reconnecting))
        }
        val gen = generation
        val epoch = connectionEpoch
        main.postDelayed({
            if (gen == generation && epoch == connectionEpoch && _uiState.value.isReconnecting) {
                sendToPeer(CallMessage.BYE)
                finish(DisconnectCause.REMOTE, app.getString(R.string.connection_lost), graceful = false)
            }
        }, RECONNECT_TIMEOUT_MS)
    }

    // ------------------------------------------------- пересогласование соединения

    /** У телефона сменилась сеть (Wi-Fi ↔ мобильная): старые маршруты больше не работают. */
    fun onNetworkChanged() {
        if (state != CallState.CONNECTED) return
        AppLog.add(TAG, "default network changed during call")
        val gen = generation
        main.postDelayed({ if (gen == generation) requestIceRestart() }, 1_500)
    }

    /**
     * Запускает поиск маршрутов заново. Новый Offer всегда создаёт вызывавшая
     * сторона (чтобы обе не начали одновременно); вызванная просит её об этом.
     */
    private fun requestIceRestart() {
        if (state != CallState.CONNECTED || restart != Restart.NONE) return
        AppLog.add(TAG, "ice restart requested (incoming=${_uiState.value.isIncoming})")
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAt < RESTART_MIN_INTERVAL_MS) return
        lastRestartAt = now
        val gen = generation
        // Если связь так и не вернулась — пробуем ещё раз.
        main.postDelayed({
            if (gen == generation && _uiState.value.isReconnecting) requestIceRestart()
        }, 10_000)

        if (_uiState.value.isIncoming) {
            sendToPeer(CallMessage.RESTART)
            return
        }
        val manager = pcm ?: return
        restart = Restart.OFFERING
        restartSent = false
        manager.createRestartOffer(
            onSuccess = { main.post { if (gen == generation) awaitRestartCandidates(gen) } },
            onError = { main.post { if (gen == generation) restart = Restart.NONE } }
        )
    }

    private fun awaitRestartCandidates(gen: Int) {
        main.postDelayed({ if (gen == generation) onRestartGathered() }, RESTART_GATHER_TIMEOUT_MS)
    }

    /** Кандидаты собраны: локальное описание готово к отправке собеседнику. */
    private fun onRestartGathered() {
        if (restart == Restart.NONE || restartSent) return
        val sdp = pcm?.localSdp() ?: return
        restartSent = true
        val gen = generation
        restartJob?.cancel()
        if (restart == Restart.OFFERING) {
            val signal = SignalCodec.encode(SignalType.OFFER, SdpPayload(sdp = sdp, identity = myIdentity, callId = callId))
            restartJob = scope.launch {
                repeat(8) {
                    if (gen != generation || restart != Restart.OFFERING) return@launch
                    sendToPeer(CallMessage.RE_OFFER, signal)
                    delay(2_500)
                }
                if (gen == generation && restart == Restart.OFFERING) restart = Restart.NONE
            }
        } else {
            val signal = SignalCodec.encode(SignalType.ANSWER, SdpPayload(sdp = sdp, identity = myIdentity, callId = callId))
            lastReAnswer = signal
            restart = Restart.NONE
            restartJob = scope.launch {
                repeat(3) {
                    if (gen != generation) return@launch
                    sendToPeer(CallMessage.RE_ANSWER, signal)
                    delay(2_000)
                }
            }
        }
    }

    private fun onRestartOffer(signal: String) {
        if (state != CallState.CONNECTED || !_uiState.value.isIncoming) return
        val key = signal.hashCode()
        if (key == lastReOfferKey) {
            // Повтор уже обработанного предложения — наш ответ, видимо, потерялся.
            lastReAnswer?.let { sendToPeer(CallMessage.RE_ANSWER, it) }
            return
        }
        if (restart != Restart.NONE) return
        val decoded = SignalCodec.decode(signal).getOrNull() ?: return
        if (decoded.type != SignalType.OFFER) return
        val manager = pcm ?: return
        lastReOfferKey = key
        lastReAnswer = null
        restart = Restart.ANSWERING
        restartSent = false
        val gen = generation
        val onError: (String) -> Unit = { main.post { if (gen == generation) restart = Restart.NONE } }
        manager.setRemoteDescription(
            sdp = SessionDescription(SessionDescription.Type.OFFER, decoded.payload.sdp),
            onSuccess = {
                main.post {
                    if (gen != generation) return@post
                    manager.createAnswer(
                        onSuccess = { main.post { if (gen == generation) awaitRestartCandidates(gen) } },
                        onError = onError
                    )
                }
            },
            onError = onError
        )
    }

    private fun onRestartAnswer(signal: String) {
        if (restart != Restart.OFFERING || !restartSent) return
        val decoded = SignalCodec.decode(signal).getOrNull() ?: return
        if (decoded.type != SignalType.ANSWER) return
        restart = Restart.NONE
        restartJob?.cancel()
        pcm?.setRemoteDescription(
            sdp = SessionDescription(SessionDescription.Type.ANSWER, decoded.payload.sdp),
            onSuccess = {},
            onError = { Log.w(TAG, "Restart answer rejected: $it") }
        )
    }

    private fun onControlMessage(message: String) {
        AppLog.add(TAG, "control in: $message")
        when {
            message == MSG_BYE -> {
                if (state.isActive) finish(DisconnectCause.REMOTE, app.getString(R.string.peer_ended), graceful = false)
            }
            message.startsWith(MSG_MUTE) -> {
                _uiState.update { it.copy(isRemoteMuted = message.endsWith("1")) }
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        bitrate = BitrateController()
        recentConcealment.clear()
        val gen = generation
        statsJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val manager = pcm ?: break
                manager.pollStats { stats -> main.post { if (gen == generation) onStats(stats) } }
            }
        }
    }

    private var loggedRoute: String? = null
    private var bitrate = BitrateController()
    /** Последние замеры доли восполненного звука — для сглаживания индикатора слышимости. */
    private val recentConcealment = ArrayDeque<Double>()

    private fun onStats(stats: LinkStats) {
        if (stats.route != null && stats.route != loggedRoute) {
            loggedRoute = stats.route
            AppLog.add(TAG, "media route: ${stats.route}, rtt=${stats.rttMs?.toInt()}ms loss=${stats.lossPercent.toInt()}%")
        }
        if (state != CallState.CONNECTED || _uiState.value.isReconnecting) return
        val rtt = stats.rttMs ?: 0.0
        val quality = when {
            stats.lossPercent > 12 || rtt > 700 -> LinkQuality.POOR
            stats.lossPercent > 4 || rtt > 350 -> LinkQuality.FAIR
            else -> LinkQuality.GOOD
        }
        if (quality != _uiState.value.quality) _uiState.update { it.copy(quality = quality) }

        // Битрейт подстраивается под потери наших пакетов у собеседника; если он
        // их не сообщает, ориентируемся на потери в обратную сторону.
        bitrate.onSample(stats.sendLossPercent ?: stats.lossPercent, stats.rttMs)?.let { bps ->
            pcm?.setMaxAudioBitrate(bps)
            AppLog.add(TAG, "audio bitrate -> ${bps / 1000} kbit/s (send loss=${stats.sendLossPercent?.toInt()}%, rtt=${stats.rttMs?.toInt()}ms)")
        }

        stats.concealedPercent?.let {
            recentConcealment.addLast(it)
            if (recentConcealment.size > HEARING_WINDOW) recentConcealment.removeFirst()
        }
        val hearing = HearingEstimate.of(recentConcealment.takeIf { it.isNotEmpty() }?.average(), stats.audioArrived)
        if (hearing != _uiState.value.hearing) _uiState.update { it.copy(hearing = hearing) }
    }

    private fun humanizeError(raw: String, fallback: String): String {
        val lower = raw.lowercase()
        return when {
            "permission" in lower || "record" in lower ->
                app.getString(R.string.error_mic_permission)
            "network" in lower || "ice" in lower ->
                app.getString(R.string.error_network)
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
        candidateResendJob?.cancel()
        candidateResendJob = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        appliedRemoteCandidates.clear()
        candidateFlushScheduled = false
        sentCandidateCount = 0
        setupGraceScheduled = false
        missedNoticeSent = false
        loggedRoute = null
        restartJob?.cancel()
        restartJob = null
        restart = Restart.NONE
        restartSent = false
        lastRestartAt = 0L
        lastReOfferKey = 0
        lastReAnswer = null
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
        peerSignalVersion = 1
    }

    private fun finish(
        telecomCause: Int,
        message: String,
        graceful: Boolean,
        showEnded: Boolean = true,
        outcome: CallOutcome? = null
    ) {
        val prev = _uiState.value
        AppLog.add(TAG, "finish: \"$message\" in state=${prev.state} telecomCause=$telecomCause")
        recordCall(prev, outcome)
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

    /** Записывает завершившийся звонок в журнал. */
    private fun recordCall(prev: CallUiState, outcome: CallOutcome?) {
        if (!prev.state.isActive || prev.contactCode.isBlank()) return
        val connected = prev.connectedAt != 0L
        val entry = CallLogEntry(
            code = prev.contactCode,
            name = prev.contactName,
            incoming = prev.isIncoming,
            outcome = when {
                connected -> CallOutcome.ANSWERED
                outcome != null -> outcome
                prev.isIncoming -> CallOutcome.MISSED
                else -> CallOutcome.NOT_CONNECTED
            },
            startedAt = if (callStartedAt != 0L) callStartedAt else System.currentTimeMillis(),
            durationSec = if (connected) (SystemClock.elapsedRealtime() - prev.connectedAt) / 1000 else 0L
        )
        if (entry.outcome == CallOutcome.MISSED) settings.addUnseenMissed()
        scope.launch { runCatching { callLog.add(entry) } }
    }

    private fun fail(message: String) {
        AppLog.add(TAG, "FAIL: $message")
        val prev = _uiState.value
        if (prev.state.isActive) sendToPeer(CallMessage.BYE)
        recordCall(prev, null)
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
                statusMessage = app.getString(R.string.status_failed)
            )
        }
    }

    private class PeerListener(private val gen: Int) : PeerConnectionManager.Listener {
        private inline fun onMain(crossinline block: () -> Unit) {
            main.post { if (gen == generation) block() }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) =
            onMain { CallManager.onConnectionChange(state) }

        override fun onIceGatheringComplete() = onMain {
            finalizeLocalSignal("complete")
            flushLocalCandidates()
            onRestartGathered()
        }

        override fun onLocalCandidate() = onMain { CallManager.onLocalCandidate() }

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
