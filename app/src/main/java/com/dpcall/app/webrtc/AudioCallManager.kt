package com.dpcall.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Аудиофокус, режим связи и маршрутизация звука во время звонка.
 *
 * Если звонок зарегистрирован в системном Telecom, маршрутами управляет он
 * (см. TelecomBridge), а этот класс отвечает только за режим и фокус.
 * Маршрутизация здесь — запасной путь для устройств без Telecom.
 */
class AudioCallManager(context: Context) {

    companion object {
        private const val TAG = "AudioCallManager"
    }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var started = false
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var routing = false
    private var scoStarted = false
    private var routesListener: ((current: AudioRoute, available: List<AudioRoute>) -> Unit)? = null
    private var requestedRoute: AudioRoute? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "Audio focus change: $change")
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    /** Аудиофокус и режим связи. Маршрутизация включается отдельно: [startRouting]. */
    fun start() {
        if (started) return
        started = true
        requestedRoute = null
        savedMode = audioManager.mode
        requestFocus()
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Log.w(TAG, "setMode failed", e)
        }
    }

    /** Берёт маршрутизацию на себя (когда Telecom недоступен). */
    fun startRouting(onRoutes: (current: AudioRoute, available: List<AudioRoute>) -> Unit) {
        if (!started || routing) return
        routing = true
        routesListener = onRoutes
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        applyRoute(defaultRoute())
    }

    val isRouting: Boolean get() = routing

    fun stop() {
        if (!started) return
        started = false
        routesListener = null
        try {
            if (routing) {
                routing = false
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    stopSco()
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            }
            audioManager.mode = savedMode
        } catch (e: Exception) {
            Log.w(TAG, "restore audio failed", e)
        }
        abandonFocus()
    }

    /** Переключает маршрут (запасной путь, без Telecom). */
    fun setRoute(route: AudioRoute) {
        if (!routing) return
        requestedRoute = route
        applyRoute(route)
    }

    fun availableRoutes(): List<AudioRoute> {
        val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        val result = mutableListOf<AudioRoute>()
        val wired = types.any {
            it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types) result += AudioRoute.BLUETOOTH
        if (wired) {
            result += AudioRoute.WIRED_HEADSET
        } else if (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types) {
            result += AudioRoute.EARPIECE
        }
        result += AudioRoute.SPEAKER
        return result
    }

    private fun defaultRoute(): AudioRoute = availableRoutes().first()

    private fun onDevicesChanged() {
        if (!routing) return
        val available = availableRoutes()
        // Подключили гарнитуру — переходим на неё; отключили — на маршрут по умолчанию.
        val wanted = requestedRoute?.takeIf { it in available && it == AudioRoute.SPEAKER } ?: available.first()
        applyRoute(wanted)
    }

    private fun applyRoute(route: AudioRoute) {
        val available = availableRoutes()
        val target = if (route in available) route else available.first()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wantedTypes = when (target) {
                    AudioRoute.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    AudioRoute.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                    AudioRoute.BLUETOOTH -> listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                    AudioRoute.WIRED_HEADSET -> listOf(
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET
                    )
                }
                val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in wantedTypes }
                if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = target == AudioRoute.SPEAKER
                if (target == AudioRoute.BLUETOOTH) startSco() else stopSco()
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyRoute($target) failed", e)
        }
        routesListener?.invoke(target, available)
    }

    @Suppress("DEPRECATION")
    private fun startSco() {
        if (scoStarted) return
        scoStarted = true
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoStarted) return
        scoStarted = false
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
    }

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        focusRequest = req
        val granted = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Focus requested, granted=$granted")
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
