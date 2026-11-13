package com.dpsmpx.dpcall.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Какие пути для звука доступны в текущей сети.
 *
 * @property direct STUN ответил: у телефона есть внешний адрес, прямое соединение возможно.
 * @property turn хотя бы один TURN-сервер выдал адрес для ретрансляции.
 * @property ownTurn свой TURN из настроек: null — не задан, иначе — ответил ли он.
 */
data class PathCheck(
    val direct: Boolean,
    val turn: Boolean,
    val ownTurn: Boolean?,
    val checkedAt: Long
) {
    /** Ни прямого пути, ни ретранслятора: звонок почти наверняка не соединится. */
    val blocked: Boolean get() = !direct && !turn

    companion object {
        /**
         * @param candidates строка кандидата и адрес сервера, который его выдал.
         * @param ownTurnUrl адрес своего TURN-сервера или null.
         */
        fun classify(candidates: List<Pair<String, String?>>, ownTurnUrl: String?, now: Long): PathCheck {
            fun type(sdp: String) = Regex(" typ (\\w+)").find(sdp)?.groupValues?.get(1)
            val relays = candidates.filter { type(it.first) == "relay" }
            val ownHost = ownTurnUrl?.let(::hostOf)
            return PathCheck(
                direct = candidates.any { type(it.first) == "srflx" },
                turn = relays.isNotEmpty(),
                ownTurn = ownHost?.let { host -> relays.any { it.second?.let(::hostOf) == host } },
                checkedAt = now
            )
        }

        /** Хост из адреса вида `turn:host:3478?transport=tcp`. */
        fun hostOf(url: String): String =
            url.substringAfter(':').substringBefore('?').substringBeforeLast(':').trim('[', ']').lowercase()
    }
}

/**
 * Пробный сбор маршрутов без звонка: создаёт пустое соединение, ждёт адреса от STUN
 * и TURN и закрывает его. Микрофон не используется, данные никуда не отправляются,
 * кроме самих серверов STUN/TURN.
 */
object PathProbe {
    private const val TAG = "PathProbe"
    private const val TIMEOUT_MS = 10_000L
    private const val FRESH_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val _result = MutableStateFlow<PathCheck?>(null)
    val result: StateFlow<PathCheck?> = _result.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var stale = false

    /** Сеть сменилась — прошлый результат больше не верен. */
    fun markStale() {
        stale = true
    }

    /** Проверяет, если результата нет, он устарел или сеть сменилась. */
    fun runIfStale(context: Context, busy: Boolean) {
        val last = _result.value
        if (last == null || stale || System.currentTimeMillis() - last.checkedAt > FRESH_MS) run(context, busy)
    }

    /** @param busy идёт звонок: проверку не запускаем, чтобы не мешать ему. */
    fun run(context: Context, busy: Boolean) {
        if (com.dpsmpx.dpcall.carrier.CarrierTransport.available) return
        if (_running.value || busy) return
        _running.value = true
        stale = false
        val app = context.applicationContext
        val custom = AppSettings.get(app).turn.takeIf { it.isValid }
        val candidates = CopyOnWriteArrayList<IceCandidate>()
        var pc: PeerConnection? = null
        var finished = false

        /** @param failed проверка сама не смогла выполниться — о сети это ничего не говорит. */
        fun finish(failed: Boolean = false) {
            if (finished) return
            finished = true
            try {
                pc?.dispose()
            } catch (e: Exception) {
                AppLog.add(TAG, "dispose: $e")
            }
            pc = null
            // Ни одного адреса, даже локального, — сбор не запустился, о сети судить нельзя.
            if (failed || candidates.isEmpty()) {
                _running.value = false
                return
            }
            val check = PathCheck.classify(candidates.map { it.sdp to it.serverUrl }, custom?.url, System.currentTimeMillis())
            AppLog.add(TAG, "direct=${check.direct} turn=${check.turn} ownTurn=${check.ownTurn}")
            _result.value = check
            _running.value = false
        }

        try {
            val config = PeerConnection.RTCConfiguration(IceServerConfig.createIceServers(true, custom)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }
            val created = RtcEngine.factory(app).createPeerConnection(config, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { candidates += it }
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) main.post { finish() }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }) ?: throw IllegalStateException("PeerConnection was not created")
            pc = created
            // Без единого потока описание пустое и маршруты не собираются.
            created.createDataChannel("probe", DataChannel.Init())
            created.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp ?: return
                    main.post {
                        if (!finished) pc?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) = main.post { finish(failed = true) }.let {}
                        }, sdp)
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) = main.post { finish(failed = true) }.let {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
            main.postDelayed({ finish() }, TIMEOUT_MS)
        } catch (e: Throwable) {
            AppLog.add(TAG, "failed: $e")
            finish(failed = true)
        }
    }
}
