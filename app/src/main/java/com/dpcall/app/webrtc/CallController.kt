package com.dpcall.app.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dpcall.app.signaling.SignalingHelper
import com.dpcall.app.signaling.SignalType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

data class CallUiState(
    val state: CallState = CallState.IDLE,
    val contactName: String = "",
    val contactCode: String = "",
    val signalToCopy: String? = null,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String = "",
    val connectionState: String = ""
)

class CallController(
    private val context: Context,
    private val myIdentity: String,
    private val useTurn: Boolean = true
) {
    companion object {
        private const val TAG = "CallController"
        private const val ICE_GATHER_TIMEOUT_MS = 8_000L
    }

    private var pcm: PeerConnectionManager? = null
    private val audio = AudioCallManager(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingLocalSdp: SessionDescription? = null
    private var iceGatherTimeout: Runnable? = null
    private var signalFinalized = false

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    fun startOutgoing(contactName: String, contactCode: String) {
        if (_uiState.value.state != CallState.IDLE && _uiState.value.state != CallState.FAILED) return

        resetInternal()
        audio.start()

        _uiState.update {
            it.copy(
                state = CallState.CREATING_OFFER,
                contactName = contactName,
                contactCode = contactCode,
                signalToCopy = null,
                errorMessage = null,
                statusMessage = "Создаём Offer…",
                isMuted = false,
                isSpeakerOn = false
            )
        }

        val manager = PeerConnectionManager(context, useTurn)
        pcm = manager

        manager.createPeerConnection(
            onIceCandidate = {},
            onConnectionChange = { handleConnectionChange(it) },
            onIceGatheringComplete = { onIceGatheringComplete() }
        )

        manager.createOffer(
            onSuccess = { sdp ->
                pendingLocalSdp = sdp
                _uiState.update { it.copy(statusMessage = "Сбор ICE-кандидатов…") }
                scheduleIceTimeout()
            },
            onError = { fail(humanizeError(it, "Не удалось создать Offer")) }
        )
    }

    fun applyRemoteAnswer(answerString: String) {
        if (_uiState.value.state != CallState.WAITING_FOR_ANSWER) return

        val decoded = SignalingHelper.tryParse(answerString).getOrElse {
            fail(it.message ?: "Не удалось разобрать Answer. Проверьте, что скопировали строку целиком.")
            return
        }
        if (decoded.type != SignalType.ANSWER) {
            fail("Ожидался Answer (DPCALL1.A…), получен ${decoded.type.name}")
            return
        }

        _uiState.update {
            it.copy(
                state = CallState.CONNECTING,
                signalToCopy = null,
                statusMessage = "Применяем Answer…",
                errorMessage = null
            )
        }

        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, decoded.payload.sdp)
        pcm?.setRemoteDescription(
            sdp = remoteSdp,
            onSuccess = {
                decoded.payload.candidates.forEach { parseAndAddCandidate(it) }
                _uiState.update { it.copy(statusMessage = "Соединяемся…") }
            },
            onError = { fail(humanizeError(it, "Не удалось применить Answer")) }
        )
    }

    fun acceptIncoming(offerString: String, contactName: String = "Собеседник") {
        if (_uiState.value.state != CallState.IDLE && _uiState.value.state != CallState.FAILED) return

        val decoded = SignalingHelper.tryParse(offerString).getOrElse {
            fail(it.message ?: "Не удалось разобрать Offer. Проверьте строку.")
            return
        }
        if (decoded.type != SignalType.OFFER) {
            fail("Ожидался Offer (DPCALL1.O…), получен ${decoded.type.name}")
            return
        }

        resetInternal()
        audio.start()

        val remoteIdentity = decoded.payload.identity
        _uiState.update {
            it.copy(
                state = CallState.CREATING_ANSWER,
                contactName = contactName,
                contactCode = remoteIdentity ?: "",
                errorMessage = null,
                statusMessage = "Создаём Answer…",
                isMuted = false,
                isSpeakerOn = false
            )
        }

        val manager = PeerConnectionManager(context, useTurn)
        pcm = manager

        manager.createPeerConnection(
            onIceCandidate = {},
            onConnectionChange = { handleConnectionChange(it) },
            onIceGatheringComplete = { onIceGatheringComplete() }
        )

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, decoded.payload.sdp)
        manager.setRemoteDescription(
            sdp = remoteSdp,
            onSuccess = {
                decoded.payload.candidates.forEach { parseAndAddCandidate(it) }
                manager.createAnswer(
                    onSuccess = { answerSdp ->
                        pendingLocalSdp = answerSdp
                        _uiState.update { it.copy(statusMessage = "Сбор ICE-кандидатов…") }
                        scheduleIceTimeout()
                    },
                    onError = { fail(humanizeError(it, "Не удалось создать Answer")) }
                )
            },
            onError = { fail(humanizeError(it, "Не удалось применить Offer")) }
        )
    }

    fun setMuted(muted: Boolean) {
        pcm?.setMicrophoneEnabled(!muted)
        _uiState.update { it.copy(isMuted = muted) }
    }

    fun setSpeaker(on: Boolean) {
        audio.setSpeakerphone(on)
        _uiState.update { it.copy(isSpeakerOn = on) }
    }

    fun hangup() {
        cancelIceTimeout()
        audio.stop()
        pcm?.close()
        pcm = null
        pendingLocalSdp = null
        signalFinalized = false
        _uiState.update {
            CallUiState(state = CallState.ENDED, contactName = it.contactName, statusMessage = "Звонок завершён")
        }
        mainHandler.postDelayed({ _uiState.update { CallUiState() } }, 1200)
    }

    fun reset() {
        cancelIceTimeout()
        audio.stop()
        pcm?.close()
        pcm = null
        pendingLocalSdp = null
        signalFinalized = false
        _uiState.update { CallUiState() }
    }

    private fun onIceGatheringComplete() {
        mainHandler.post {
            Log.d(TAG, "ICE gathering complete")
            finalizeLocalSignal("ICE ready")
        }
    }

    private fun scheduleIceTimeout() {
        cancelIceTimeout()
        val r = Runnable {
            Log.w(TAG, "ICE gather timeout — finalizing with current candidates")
            finalizeLocalSignal("ICE timeout")
        }
        iceGatherTimeout = r
        mainHandler.postDelayed(r, ICE_GATHER_TIMEOUT_MS)
    }

    private fun cancelIceTimeout() {
        iceGatherTimeout?.let { mainHandler.removeCallbacks(it) }
        iceGatherTimeout = null
    }

    private fun finalizeLocalSignal(reason: String) {
        if (signalFinalized) return
        val sdp = pendingLocalSdp ?: return
        val manager = pcm ?: return
        signalFinalized = true
        cancelIceTimeout()

        val candidates = manager.getCollectedCandidates().map {
            "${it.sdpMid}|${it.sdpMLineIndex}|${it.sdp}"
        }
        Log.d(TAG, "Finalize signal ($reason), candidates=${candidates.size}")

        val isOffer = sdp.type == SessionDescription.Type.OFFER
        val signal = if (isOffer) {
            SignalingHelper.createOffer(sdp.description, candidates, myIdentity)
        } else {
            SignalingHelper.createAnswer(sdp.description, candidates, myIdentity)
        }

        _uiState.update {
            it.copy(
                state = if (isOffer) CallState.WAITING_FOR_ANSWER else CallState.CONNECTING,
                signalToCopy = signal,
                statusMessage = if (isOffer) {
                    "Скопируйте Offer и отправьте собеседнику"
                } else {
                    "Скопируйте Answer и отправьте собеседнику"
                }
            )
        }
    }

    private fun handleConnectionChange(state: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "Connection: $state")
        mainHandler.post {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    _uiState.update {
                        it.copy(
                            state = CallState.CONNECTED,
                            signalToCopy = null,
                            connectionState = "CONNECTED",
                            statusMessage = "На линии",
                            errorMessage = null
                        )
                    }
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    fail("Соединение не установлено. Проверьте VPN и сеть, затем попробуйте снова.")
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (_uiState.value.state == CallState.CONNECTED) {
                        _uiState.update {
                            it.copy(connectionState = "DISCONNECTED", statusMessage = "Связь прервалась…")
                        }
                    }
                }
                PeerConnection.PeerConnectionState.CLOSED -> {}
                else -> {
                    _uiState.update {
                        it.copy(
                            connectionState = state.name,
                            statusMessage = when (state) {
                                PeerConnection.PeerConnectionState.CONNECTING -> "Соединяемся…"
                                PeerConnection.PeerConnectionState.NEW -> "Подготовка…"
                                else -> it.statusMessage
                            }
                        )
                    }
                }
            }
        }
    }

    private fun parseAndAddCandidate(candidateSdp: String) {
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

    private fun humanizeError(raw: String, fallback: String): String {
        val lower = raw.lowercase()
        return when {
            "permission" in lower || "record" in lower ->
                "Нет доступа к микрофону. Разрешите в настройках приложения."
            "network" in lower || "ice" in lower ->
                "Проблема с сетью. Проверьте VPN и интернет."
            raw.isBlank() -> fallback
            else -> "$fallback ($raw)"
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        cancelIceTimeout()
        audio.stop()
        pcm?.close()
        pcm = null
        pendingLocalSdp = null
        signalFinalized = false
        _uiState.update {
            it.copy(
                state = CallState.FAILED,
                errorMessage = message,
                signalToCopy = null,
                statusMessage = "Ошибка"
            )
        }
    }

    private fun resetInternal() {
        cancelIceTimeout()
        pcm?.close()
        pcm = null
        pendingLocalSdp = null
        signalFinalized = false
    }
}
