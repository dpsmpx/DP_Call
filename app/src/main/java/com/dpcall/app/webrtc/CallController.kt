package com.dpcall.app.webrtc

import android.content.Context
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
    val errorMessage: String? = null,
    val connectionState: String = ""
)

class CallController(
    private val context: Context,
    private val myIdentity: String,
    private val useTurn: Boolean = true
) {
    companion object {
        private const val TAG = "CallController"
    }

    private var pcm: PeerConnectionManager? = null
    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    fun startOutgoing(contactName: String, contactCode: String) {
        if (_uiState.value.state != CallState.IDLE) return

        _uiState.update {
            it.copy(
                state = CallState.CREATING_OFFER,
                contactName = contactName,
                contactCode = contactCode,
                signalToCopy = null,
                errorMessage = null
            )
        }

        val manager = PeerConnectionManager(context, useTurn)
        pcm = manager

        manager.createPeerConnection(
            onIceCandidate = {},
            onConnectionChange = { handleConnectionChange(it) },
            onIceGatheringComplete = {}
        )

        manager.createOffer(
            onSuccess = { sdp ->
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val candidates = manager.getCollectedCandidates().map {
                        "${it.sdpMid}|${it.sdpMLineIndex}|${it.sdp}"
                    }
                    val offerString = SignalingHelper.createOffer(
                        sdp = sdp.description,
                        candidates = candidates,
                        myIdentity = myIdentity
                    )
                    _uiState.update {
                        it.copy(
                            state = CallState.WAITING_FOR_ANSWER,
                            signalToCopy = offerString
                        )
                    }
                }, 1500)
            },
            onError = { fail(it) }
        )
    }

    fun applyRemoteAnswer(answerString: String) {
        if (_uiState.value.state != CallState.WAITING_FOR_ANSWER) return

        val decoded = SignalingHelper.tryParse(answerString).getOrElse {
            fail(it.message ?: "Неверный Answer")
            return
        }
        if (decoded.type != SignalType.ANSWER) {
            fail("Ожидался Answer, получен ${decoded.type}")
            return
        }

        _uiState.update { it.copy(state = CallState.CONNECTING, signalToCopy = null) }

        val remoteSdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            decoded.payload.sdp
        )
        pcm?.setRemoteDescription(
            sdp = remoteSdp,
            onSuccess = {
                decoded.payload.candidates.forEach { parseAndAddCandidate(it) }
            },
            onError = { fail(it) }
        )
    }

    fun acceptIncoming(offerString: String, contactName: String = "Собеседник") {
        if (_uiState.value.state != CallState.IDLE) return

        val decoded = SignalingHelper.tryParse(offerString).getOrElse {
            fail(it.message ?: "Неверный Offer")
            return
        }
        if (decoded.type != SignalType.OFFER) {
            fail("Ожидался Offer, получен ${decoded.type}")
            return
        }

        val remoteIdentity = decoded.payload.identity
        _uiState.update {
            it.copy(
                state = CallState.CREATING_ANSWER,
                contactName = contactName,
                contactCode = remoteIdentity ?: "",
                errorMessage = null
            )
        }

        val manager = PeerConnectionManager(context, useTurn)
        pcm = manager

        manager.createPeerConnection(
            onIceCandidate = {},
            onConnectionChange = { handleConnectionChange(it) },
            onIceGatheringComplete = {}
        )

        val remoteSdp = SessionDescription(
            SessionDescription.Type.OFFER,
            decoded.payload.sdp
        )

        manager.setRemoteDescription(
            sdp = remoteSdp,
            onSuccess = {
                decoded.payload.candidates.forEach { parseAndAddCandidate(it) }

                manager.createAnswer(
                    onSuccess = { answerSdp ->
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val candidates = manager.getCollectedCandidates().map {
                                "${it.sdpMid}|${it.sdpMLineIndex}|${it.sdp}"
                            }
                            val answerString = SignalingHelper.createAnswer(
                                sdp = answerSdp.description,
                                candidates = candidates,
                                myIdentity = myIdentity
                            )
                            _uiState.update {
                                it.copy(
                                    state = CallState.CONNECTING,
                                    signalToCopy = answerString
                                )
                            }
                        }, 1500)
                    },
                    onError = { fail(it) }
                )
            },
            onError = { fail(it) }
        )
    }

    fun setMuted(muted: Boolean) {
        pcm?.setMicrophoneEnabled(!muted)
        _uiState.update { it.copy(isMuted = muted) }
    }

    fun hangup() {
        pcm?.close()
        pcm = null
        _uiState.update {
            CallUiState(state = CallState.ENDED, contactName = it.contactName)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            _uiState.update { CallUiState() }
        }, 1500)
    }

    fun reset() {
        pcm?.close()
        pcm = null
        _uiState.update { CallUiState() }
    }

    private fun handleConnectionChange(state: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "Connection: $state")
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                _uiState.update {
                    it.copy(
                        state = CallState.CONNECTED,
                        signalToCopy = null,
                        connectionState = "CONNECTED"
                    )
                }
            }
            PeerConnection.PeerConnectionState.FAILED,
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                if (_uiState.value.state == CallState.CONNECTED ||
                    _uiState.value.state == CallState.CONNECTING
                ) {
                    _uiState.update { it.copy(connectionState = state.name) }
                }
            }
            PeerConnection.PeerConnectionState.CLOSED -> {}
            else -> {
                _uiState.update { it.copy(connectionState = state.name) }
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

    private fun fail(message: String) {
        Log.e(TAG, message)
        pcm?.close()
        pcm = null
        _uiState.update {
            it.copy(state = CallState.FAILED, errorMessage = message, signalToCopy = null)
        }
    }
}
