package com.dpcall.app.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над PeerConnection для голосовых звонков.
 * Только аудио. Видео не создаётся.
 */
class PeerConnectionManager(
    private val context: Context,
    private val useTurn: Boolean = true
) {
    companion object {
        private const val TAG = "PeerConnectionManager"
        private val factoryInitialized = AtomicBoolean(false)

        fun initFactory(context: Context) {
            if (factoryInitialized.compareAndSet(false, true)) {
                val options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
            }
        }
    }

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private val collectedCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var onIceCandidate: ((IceCandidate) -> Unit)? = null
    private var onConnectionChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    private var onIceGatheringComplete: (() -> Unit)? = null

    init {
        initFactory(context)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(
        onIceCandidate: (IceCandidate) -> Unit = {},
        onConnectionChange: (PeerConnection.PeerConnectionState) -> Unit = {},
        onIceGatheringComplete: () -> Unit = {}
    ) {
        this.onIceCandidate = onIceCandidate
        this.onConnectionChange = onConnectionChange
        this.onIceGatheringComplete = onIceGatheringComplete

        val rtcConfig = PeerConnection.RTCConfiguration(IceServerConfig.createIceServers(useTurn)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "onIceConnectionChange: $state")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "onConnectionChange: $newState")
                newState?.let { onConnectionChange(it) }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "onIceGatheringChange: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    onIceGatheringComplete()
                }
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                collectedCandidates += candidate
                onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "onAddTrack: ${receiver?.track()?.kind()}")
            }
        })

        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("AUDIO_LOCAL", audioSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localAudioTrack, listOf("stream1"))
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return onError("Empty offer")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() = onSuccess(sdp)
                    override fun onSetFailure(error: String?) = onError(error ?: "setLocalDescription failed")
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = onError(error ?: "createOffer failed")
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return onError("Empty answer")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() = onSuccess(sdp)
                    override fun onSetFailure(error: String?) = onError(error ?: "setLocalDescription failed")
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = onError(error ?: "createAnswer failed")
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(
        sdp: SessionDescription,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() = onSuccess()
            override fun onSetFailure(error: String?) = onError(error ?: "setRemoteDescription failed")
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun getCollectedCandidates(): List<IceCandidate> = collectedCandidates.toList()

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun isMicrophoneEnabled(): Boolean = localAudioTrack?.enabled() ?: false

    fun close() {
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        collectedCandidates.clear()
    }

    fun dispose() {
        close()
        factory.dispose()
        eglBase.release()
    }
}
