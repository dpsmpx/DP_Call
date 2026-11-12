package com.dpsmpx.dpcall.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dpsmpx.dpcall.webrtc.AudioRoute
import com.dpsmpx.dpcall.webrtc.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground-сервис звонка.
 *
 *  - держит процесс и микрофон живыми, когда приложение свёрнуто или экран выключен;
 *  - показывает уведомление: входящий (Принять / Отклонить) или текущий звонок
 *    (Завершить, микрофон);
 *  - гасит экран у уха (датчик приближения) и не даёт процессору уснуть;
 *  - ловит кнопку проводной гарнитуры там, где системный Telecom недоступен.
 *
 * Сам звонком не управляет — только отражает состояние [CallManager].
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var foregroundTypes = -1
    private var lastNotificationKey: Any? = null

    private var cpuLock: PowerManager.WakeLock? = null
    private var proximityLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CallManager.init(this)
        CallNotifications.ensureChannels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dpcall:call").apply {
            setReferenceCounted(false)
        }
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "dpcall:proximity").apply {
                setReferenceCounted(false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground обязателен после startForegroundService, даже если звонок уже закончился.
        val state = CallManager.uiState.value
        showNotification(state, forceForeground = true)
        if (!state.state.isActive) {
            shutdown()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch { CallManager.uiState.collect { onState(it) } }
        }
        return START_NOT_STICKY
    }

    private fun onState(state: CallUiState) {
        if (!state.state.isActive) {
            shutdown()
            return
        }
        showNotification(state, forceForeground = false)
        updateLocks(state)
        updateMediaSession(active = true)
    }

    private fun showNotification(state: CallUiState, forceForeground: Boolean) {
        val types = foregroundTypes()
        val key = listOf(
            state.state, state.contactName, state.isMuted, state.isOnHold, state.isReconnecting,
            state.signalToCopy != null, state.statusMessage.takeIf { state.state != CallState.CONNECTED }
        )
        val typesChanged = types != foregroundTypes
        if (!forceForeground && !typesChanged && key == lastNotificationKey) return
        lastNotificationKey = key

        val notification = CallNotifications.build(this, state)
        if (forceForeground || typesChanged) {
            try {
                ServiceCompat.startForeground(this, CallNotifications.CALL_NOTIFICATION_ID, notification, types)
                foregroundTypes = types
                return
            } catch (e: Exception) {
                // Например, из фона нельзя добавить тип «микрофон» — остаёмся с прежним.
                Log.w(TAG, "startForeground(types=$types) failed", e)
                if (foregroundTypes == -1) {
                    try {
                        ServiceCompat.startForeground(
                            this, CallNotifications.CALL_NOTIFICATION_ID, notification, phoneCallType()
                        )
                        foregroundTypes = phoneCallType()
                        return
                    } catch (e2: Exception) {
                        Log.e(TAG, "startForeground fallback failed", e2)
                    }
                }
            }
        }
        try {
            NotificationManagerCompat.from(this).notify(CallNotifications.CALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify not allowed", e)
        }
    }

    private fun phoneCallType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0

    private fun foregroundTypes(): Int {
        var types = phoneCallType()
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && micGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun updateLocks(state: CallUiState) {
        val inCall = state.state != CallState.RINGING
        try {
            cpuLock?.let { if (inCall && !it.isHeld) it.acquire(MAX_CALL_MS) else if (!inCall && it.isHeld) it.release() }
            val nearEar = state.state == CallState.CONNECTED && state.audioRoute == AudioRoute.EARPIECE
            proximityLock?.let {
                if (nearEar && !it.isHeld) it.acquire(MAX_CALL_MS) else if (!nearEar && it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock update failed", e)
        }
    }

    /**
     * Запасной обработчик кнопки гарнитуры. Когда звонок зарегистрирован в Telecom,
     * нажатия забирает система и приходят через TelecomBridge; сюда они попадают
     * только на устройствах без Telecom.
     */
    private fun updateMediaSession(active: Boolean) {
        if (!active) {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
            mediaSession = null
            return
        }
        if (mediaSession != null) return
        try {
            mediaSession = MediaSession(this, "DPCallHeadset").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean {
                        val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                        }
                        if (event == null || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                            return event != null
                        }
                        return when (event.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_CALL,
                            KeyEvent.KEYCODE_ENDCALL -> {
                                onHeadsetButton()
                                true
                            }
                            else -> false
                        }
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .build()
                )
                isActive = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession unavailable", e)
        }
    }

    private fun onHeadsetButton() {
        when {
            CallManager.uiState.value.state == CallState.RINGING -> {
                if (!CallManager.acceptIncoming()) CallNotifications.openApp(this, answer = true)
            }
            else -> CallManager.hangup()
        }
    }

    private fun shutdown() {
        observer?.cancel()
        observer = null
        releaseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseAll() {
        updateMediaSession(active = false)
        try {
            cpuLock?.let { if (it.isHeld) it.release() }
            proximityLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed", e)
        }
    }

    override fun onDestroy() {
        releaseAll()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallService"
        private const val MAX_CALL_MS = 6 * 60 * 60 * 1000L

        /**
         * Запускает сервис.
         * @return false, если система не разрешила (приложение в фоне) — тогда
         *   уведомление звонка показывается напрямую, см. CallNotifications.notifyDirect.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start call service", e)
            false
        }
    }
}
