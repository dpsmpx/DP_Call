package com.dpsmpx.dpcall.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.util.AppLog
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.ExternalAudioProcessingFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Общая на процесс фабрика WebRTC с настроенной обработкой звука.
 *
 * Исходящий звук (микрофон): эхоподавление, шумоподавление, автоусиление и
 * фильтр низких частот из WebRTC Audio Processing.
 * Входящий звук: [ReceiveNoiseGate] перед воспроизведением.
 */
internal object RtcEngine {
    private const val TAG = "RtcEngine"

    private var factory: PeerConnectionFactory? = null
    private var processing: ExternalAudioProcessingFactory? = null

    @Synchronized
    fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        val app = context.applicationContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val hardwareAec = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val adm = JavaAudioDeviceModule.builder(app)
            // Аппаратное эхоподавление, если есть: оно знает акустику устройства.
            .setUseHardwareAcousticEchoCanceler(hardwareAec)
            // Шумоподавление — программное (WebRTC NS): стабильное качество на всех устройствах.
            .setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(msg: String?) = Log.e(TAG, "record init: $msg").let {}
                override fun onWebRtcAudioRecordStartError(
                    code: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    msg: String?
                ) = Log.e(TAG, "record start: $code $msg").let {}
                override fun onWebRtcAudioRecordError(msg: String?) = Log.e(TAG, "record: $msg").let {}
            })
            .createAudioDeviceModule()

        val apf = ExternalAudioProcessingFactory().apply {
            setRenderPreProcessing(ReceiveNoiseGate())
            setBypassFlagForRenderPre(false)
            setBypassFlagForCapturePost(true)
        }
        processing = apf

        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setAudioProcessingFactory(apf)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
        // Фабрика держит собственную ссылку на модуль.
        adm.release()
        factory = created
        Log.d(TAG, "Factory created, hardwareAec=$hardwareAec")
        return created
    }

    /** Включает/выключает шумоподавление входящего звука на лету. */
    @Synchronized
    fun setReceiveNoiseSuppression(enabled: Boolean) {
        processing?.setBypassFlagForRenderPre(!enabled)
    }
}

/** Показатели качества связи за последний интервал опроса. */
data class LinkStats(
    val lossPercent: Double,
    val rttMs: Double?,
    /** Тип выбранного маршрута, например «host→srflx» или «relay→relay» (через TURN). */
    val route: String? = null,
    /** Потери наших пакетов у собеседника (по его отчётам RTCP), %. */
    val sendLossPercent: Double? = null,
    /** Доля звука собеседника, восполненного подстановкой вместо потерянного или опоздавшего, %. */
    val concealedPercent: Double? = null,
    /** За интервал пришёл хоть один пакет звука. */
    val audioArrived: Boolean = true
)

/**
 * Обёртка над PeerConnection для голосовых звонков. Только аудио.
 *
 * Помимо звука открывает служебный data channel (согласован заранее, id=0):
 * по нему передаются «bye» при завершении и состояние микрофона.
 */
class PeerConnectionManager(
    private val context: Context,
    private val useTurn: Boolean = true
) {
    interface Listener {
        fun onConnectionChange(state: PeerConnection.PeerConnectionState)
        fun onIceGatheringComplete()
        /** Найден новый локальный маршрут (кандидат). */
        fun onLocalCandidate()
        fun onControlMessage(message: String)
        fun onControlChannelOpen()
    }

    companion object {
        private const val TAG = "PeerConnectionManager"
        private const val CONTROL_CHANNEL = "dpcall-ctl"
    }

    private val factory = RtcEngine.factory(context)
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var audioSender: RtpSender? = null
    private var controlChannel: DataChannel? = null
    private var micEnabled = true
    private var trackGeneration = 0

    private val collectedCandidates = CopyOnWriteArrayList<IceCandidate>()

    /**
     * Путь между телефонами (ICE) хотя бы раз был найден. Если при этом соединение не
     * поднялось, сорвалось шифрованное рукопожатие (DTLS) — это другая блокировка.
     */
    @Volatile
    var iceConnected = false
        private set

    private var lastPacketsLost = 0L
    private var lastConcealed = 0L
    private var lastSamples = 0L
    private var lastPacketsReceived = 0L

    fun createPeerConnection(listener: Listener, noiseSuppression: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(IceServerConfig.createIceServers(useTurn, AppSettings.get(context).turn)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                AppLog.add(TAG, "signaling=$state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLog.add(TAG, "ice=$state")
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    iceConnected = true
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                AppLog.add(TAG, "connection=$newState")
                newState?.let { listener.onConnectionChange(it) }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                AppLog.add(TAG, "gathering=$state candidates=${candidateSummary(collectedCandidates.map { it.sdp })}")
                if (state == PeerConnection.IceGatheringState.COMPLETE) listener.onIceGatheringComplete()
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                collectedCandidates += candidate
                listener.onLocalCandidate()
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: throw IllegalStateException("PeerConnection was not created")
        peerConnection = pc

        RtcEngine.setReceiveNoiseSuppression(noiseSuppression)
        val (source, track) = createLocalAudio(noiseSuppression)
        audioSource = source
        localAudioTrack = track
        audioSender = pc.addTrack(track, listOf("dpcall"))

        try {
            val init = DataChannel.Init().apply {
                negotiated = true
                id = 0
                ordered = true
            }
            controlChannel = pc.createDataChannel(CONTROL_CHANNEL, init)?.also { dc ->
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        AppLog.add(TAG, "control channel=${controlChannel?.state()}")
                        if (controlChannel?.state() == DataChannel.State.OPEN) listener.onControlChannelOpen()
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary) return
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        listener.onControlMessage(String(bytes, Charsets.UTF_8))
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Control channel unavailable", e)
        }
    }

    private fun createLocalAudio(noiseSuppression: Boolean): Pair<AudioSource, AudioTrack> {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", noiseSuppression.toString()))
        }
        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack("dpcall-audio-${trackGeneration++}", source)
        track.setEnabled(micEnabled)
        return source to track
    }

    /**
     * Переключает шумоподавление во время звонка: входящее — сразу, исходящее —
     * заменой аудиотрека (без пересогласования SDP).
     */
    fun setNoiseSuppression(enabled: Boolean) {
        RtcEngine.setReceiveNoiseSuppression(enabled)
        val sender = audioSender ?: return
        if (peerConnection == null) return
        try {
            val oldTrack = localAudioTrack
            val oldSource = audioSource
            val (source, track) = createLocalAudio(enabled)
            if (sender.setTrack(track, false)) {
                audioSource = source
                localAudioTrack = track
                oldTrack?.dispose()
                oldSource?.dispose()
            } else {
                track.dispose()
                source.dispose()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to switch noise suppression", e)
        }
    }

    /** Ограничивает битрейт исходящего звука без пересогласования SDP. */
    fun setMaxAudioBitrate(bps: Int) {
        val sender = audioSender ?: return
        try {
            val params = sender.parameters
            params.encodings.forEach { it.maxBitrateBps = bps }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set audio bitrate", e)
        }
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) =
        createSdp(isOffer = true, onSuccess, onError)

    /**
     * Новый Offer с перезапуском ICE: соединение ищет маршруты заново (например,
     * после перехода с Wi-Fi на мобильную сеть), звук при этом не пересоздаётся.
     */
    fun createRestartOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) =
        createSdp(isOffer = true, onSuccess, onError, iceRestart = true)

    /**
     * Текущее локальное описание. После завершения сбора ICE оно уже содержит
     * кандидатов, поэтому при пересогласовании их не нужно передавать отдельно.
     */
    fun localSdp(): String? = try {
        peerConnection?.localDescription?.description
    } catch (e: Exception) {
        null
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) =
        createSdp(isOffer = false, onSuccess, onError)

    private fun createSdp(
        isOffer: Boolean,
        onSuccess: (SessionDescription) -> Unit,
        onError: (String) -> Unit,
        iceRestart: Boolean = false
    ) {
        val pc = peerConnection ?: return onError("No peer connection")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val kind = (if (isOffer) "offer" else "answer") + (if (iceRestart) " (ice restart)" else "")
        val logError: (String) -> Unit = { AppLog.add(TAG, "create/setLocal $kind FAILED: $it"); onError(it) }
        val observer = object : SimpleSdpObserver(logError) {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return logError("Empty SDP")
                peerConnection?.setLocalDescription(object : SimpleSdpObserver(logError) {
                    override fun onSetSuccess() {
                        AppLog.add(TAG, "setLocal $kind ok")
                        onSuccess(sdp)
                    }
                }, sdp)
            }
        }
        if (isOffer) pc.createOffer(observer, constraints) else pc.createAnswer(observer, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: return onError("No peer connection")
        val tuned = SessionDescription(sdp.type, SdpTuning.tuneOpus(sdp.description))
        AppLog.add(TAG, "setRemote ${sdp.type} len=${sdp.description.length}")
        pc.setRemoteDescription(object : SimpleSdpObserver({ AppLog.add(TAG, "setRemote ${sdp.type} FAILED: $it"); onError(it) }) {
            override fun onSetSuccess() {
                AppLog.add(TAG, "setRemote ${sdp.type} ok")
                onSuccess()
            }
        }, tuned)
    }

    /** Сколько кандидатов какого типа: host — локальные, srflx — через STUN, relay — через TURN. */
    private fun candidateSummary(sdps: List<String>): String =
        listOf("host", "srflx", "prflx", "relay").joinToString(" ") { type ->
            "$type=" + sdps.count { " typ $type" in it }
        }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun getCollectedCandidates(): List<IceCandidate> = collectedCandidates.toList()

    /** Сводка по типам локальных кандидатов — для журнала. */
    fun localCandidateSummary(): String = candidateSummary(collectedCandidates.map { it.sdp })

    fun setMicrophoneEnabled(enabled: Boolean) {
        micEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
    }

    /** Включает/выключает воспроизведение собеседника (удержание). */
    fun setRemoteAudioEnabled(enabled: Boolean) {
        try {
            peerConnection?.receivers?.forEach { it.track()?.setEnabled(enabled) }
        } catch (e: Exception) {
            Log.w(TAG, "setRemoteAudioEnabled failed", e)
        }
    }

    fun sendControl(message: String): Boolean {
        val dc = controlChannel ?: return false
        return try {
            dc.state() == DataChannel.State.OPEN &&
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false))
        } catch (e: Exception) {
            false
        }
    }

    /** Асинхронно считает потери и задержку с момента прошлого вызова. */
    fun pollStats(onResult: (LinkStats) -> Unit) {
        val pc = peerConnection ?: return
        pc.getStats { report: RTCStatsReport ->
            var lost = 0L
            var received = 0L
            var rtt: Double? = null
            var route: String? = null
            var sendLoss: Double? = null
            var concealed = 0L
            var samples = 0L
            for (stats in report.statsMap.values) {
                val m = stats.members
                when (stats.type) {
                    "inbound-rtp" -> if (m["kind"] == "audio") {
                        lost += (m["packetsLost"] as? Number)?.toLong() ?: 0L
                        received += (m["packetsReceived"] as? Number)?.toLong() ?: 0L
                        concealed += (m["concealedSamples"] as? Number)?.toLong() ?: 0L
                        samples += (m["totalSamplesReceived"] as? Number)?.toLong() ?: 0L
                    }
                    "remote-inbound-rtp" -> if (m["kind"] == "audio") {
                        (m["fractionLost"] as? Number)?.let { sendLoss = it.toDouble() * 100.0 }
                    }
                    "candidate-pair" -> if (m["nominated"] == true && m["state"] == "succeeded") {
                        (m["currentRoundTripTime"] as? Number)?.let { rtt = it.toDouble() * 1000.0 }
                        val local = report.statsMap[m["localCandidateId"]]?.members?.get("candidateType")
                        val remote = report.statsMap[m["remoteCandidateId"]]?.members?.get("candidateType")
                        if (local != null && remote != null) route = "$local→$remote"
                    }
                }
            }
            val dLost = (lost - lastPacketsLost).coerceAtLeast(0)
            val dReceived = (received - lastPacketsReceived).coerceAtLeast(0)
            lastPacketsLost = lost
            lastPacketsReceived = received
            val total = dLost + dReceived
            val loss = if (total > 0) dLost * 100.0 / total else if (received > 0) 100.0 else 0.0
            val dConcealed = (concealed - lastConcealed).coerceAtLeast(0)
            val dSamples = (samples - lastSamples).coerceAtLeast(0)
            lastConcealed = concealed
            lastSamples = samples
            onResult(
                LinkStats(
                    lossPercent = loss,
                    rttMs = rtt,
                    route = route,
                    sendLossPercent = sendLoss,
                    concealedPercent = if (dSamples > 0) dConcealed * 100.0 / dSamples else null,
                    audioArrived = dReceived > 0
                )
            )
        }
    }

    fun close() {
        val pc = peerConnection
        peerConnection = null
        audioSender = null
        try {
            controlChannel?.let {
                it.unregisterObserver()
                it.close()
                it.dispose()
            }
        } catch (e: Exception) {
            Log.w(TAG, "control channel close", e)
        }
        controlChannel = null
        try {
            pc?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "pc dispose", e)
        }
        try {
            localAudioTrack?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "track dispose", e)
        }
        localAudioTrack = null
        try {
            audioSource?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "source dispose", e)
        }
        audioSource = null
        collectedCandidates.clear()
    }

    private open class SimpleSdpObserver(private val onError: (String) -> Unit) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) = onError(error ?: "create failed")
        override fun onSetFailure(error: String?) = onError(error ?: "set failed")
    }
}
