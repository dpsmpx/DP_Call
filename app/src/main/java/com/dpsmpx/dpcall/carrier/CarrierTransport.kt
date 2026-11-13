package com.dpsmpx.dpcall.carrier

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dpsmpx.dpcall.call.CallManager
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.data.IdentityRepository
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.signaling.relay.CallMessage
import com.dpsmpx.dpcall.signaling.relay.RelayCrypto
import com.dpsmpx.dpcall.webrtc.CallState
import com.dpsmpx.dpcall.webrtc.PathProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface CarrierBackend {
    val turnUrl: String
    val username: String
    val password: String
    fun start(provider: String, room: String)
    fun send(data: ByteArray): Boolean
    fun close()
}

data class CarrierStatus(val phase: String = "off", val peerCode: String = "", val provider: String = "", val captchaUrl: String? = null)
data class CarrierTurn(val url: String, val username: String, val password: String)

/** Optional session. Once selected, failures never fall back to public relays or ICE servers. */
object CarrierTransport {
    val active: Boolean get() = _status.value.phase != "off"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(CarrierStatus())
    val status = _status.asStateFlow()
    @Volatile private var backend: CarrierBackend? = null
    @Volatile private var lastPeerAt = 0L
    @Volatile private var generation = 0
    // Replaced by a fake in JVM integration tests; production creates the native session lazily.
    internal var createBackend = CarrierBackendFactory::create
    private var monitor: Job? = null
    private var closing: Job? = null
    val ready: Boolean get() = _status.value.phase == "peer_ready" && backend != null && System.currentTimeMillis() - lastPeerAt in 0..10_000L
    val turn: CarrierTurn? get() = backend?.let { CarrierTurn(it.turnUrl, it.username, it.password) }

    fun connect(context: Context, rawRoom: String, peerCode: String) {
        if (CallManager.uiState.value.state.isActive) return
        val room = CarrierRoom.parse(rawRoom) ?: return
        val peer = IdentityGenerator.publicKeyFromCode(peerCode) ?: return
        stopSession()
        val gen = generation
        _status.value = CarrierStatus("connecting", peerCode, room.provider)
        val app = context.applicationContext
        PathProbe.cancel()
        RelaySignaling.restart(app)
        monitor = scope.launch {
            var pending: CarrierBackend? = null
            try {
                closing?.join()
                val keys = IdentityRepository(app).loadKeys()
                require(!peer.contentEquals(keys.public))
                val key = RelayCrypto.hkdf(RelayCrypto.sharedSecret(keys.secret, peer),
                    RelayCrypto.sha256(room.url.toByteArray()), "dpcall-carrier-v1".toByteArray())
                val first = keys.code < peerCode
                val created = createBackend(key, first,
                    { phase ->
                        main.post {
                            if (gen == generation) {
                                if (phase == "peer_ready") lastPeerAt = System.currentTimeMillis()
                                val captcha = phase.takeIf { it.startsWith("CAPTCHA:http://127.0.0.1:") }?.removePrefix("CAPTCHA:")
                                _status.value = _status.value.copy(phase = if (captcha != null) "captcha" else phase, captchaUrl = captcha)
                                RelaySignaling.carrierStatusChanged(ready)
                            }
                        }
                    },
                    { bytes ->
                        if (bytes.size <= 64 * 1024) {
                            val message = runCatching { json.decodeFromString<CallMessage>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                            if (message != null) main.post {
                                if (gen == generation) CallManager.onRelayMessage(peerCode, message)
                            }
                        }
                    })
                pending = created
                withContext(Dispatchers.Main) {
                    if (gen != generation) return@withContext
                    backend = created
                    pending = null
                    created.start(room.provider, room.url)
                }
                while (isActive && gen == generation) {
                    delay(2_000)
                    withContext(Dispatchers.Main) {
                        if (gen == generation) {
                            if (!ready && _status.value.phase == "peer_ready") _status.value = _status.value.copy(phase = "waiting_peer")
                            RelaySignaling.carrierStatusChanged(ready)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                main.post { if (gen == generation) {
                    _status.value = _status.value.copy(phase = "failed")
                    RelaySignaling.carrierStatusChanged(false)
                } }
            } finally { pending?.close() }
        }
    }

    fun send(toCode: String, message: CallMessage): Int {
        if (!ready || !toCode.equals(_status.value.peerCode, ignoreCase = true) || message.t == CallMessage.MISSED) return 0
        return if (backend?.send(json.encodeToString(message).toByteArray(Charsets.UTF_8)) == true) 1 else 0
    }

    fun disconnect(context: Context) {
        if (CallManager.uiState.value.state.isActive) return
        stopSession()
        _status.value = CarrierStatus()
        PathProbe.markStale()
        RelaySignaling.restart(context.applicationContext)
    }

    private fun stopSession() {
        generation++
        val previousMonitor = monitor
        previousMonitor?.cancel(); monitor = null
        val previous = backend; backend = null
        // Keep the whole close chain: a rapid retry must not overlap native sessions.
        val earlierClose = closing
        closing = scope.launch {
            earlierClose?.join()
            previousMonitor?.join()
            previous?.close()
        }
        lastPeerAt = 0
    }
}
