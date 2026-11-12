package com.dpcall.app.ui

import com.dpcall.app.R
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dpcall.app.call.CallManager
import com.dpcall.app.call.ListenService
import com.dpcall.app.call.RelaySignaling
import com.dpcall.app.call.SignalResult
import com.dpcall.app.data.AppSettings
import com.dpcall.app.signaling.SignalCodec
import com.dpcall.app.ui.call.CallScreen
import com.dpcall.app.ui.home.HomeScreen
import com.dpcall.app.ui.theme.DPCallTheme
import com.dpcall.app.webrtc.CallState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val startupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private var pendingMicAction: (() -> Unit)? = null
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.mic_required), Toast.LENGTH_LONG).show()
        }
    }

    /** Отметка времени содержимого буфера, которое мы уже проверяли. */
    private var lastClipTimestamp = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)
        enableEdgeToEdge()
        // При самом первом запуске сначала показываем инструкцию; разрешения — после неё.
        if (savedInstanceState == null && AppSettings.get(this).onboardingShown.value) {
            requestStartupPermissions()
        }

        setContent {
            DPCallTheme {
                val call by CallManager.uiState.collectAsStateWithLifecycle()
                Crossfade(targetState = call.state != CallState.IDLE, label = "screen") { inCall ->
                    if (inCall) {
                        CallScreen(
                            state = call,
                            onAccept = { withMicPermission { CallManager.acceptIncoming() } },
                            onMinimize = { moveTaskToBack(true) }
                        )
                    } else {
                        HomeScreen(
                            onOnboardingDone = { requestStartupPermissions() },
                            onCall = { contact ->
                                withMicPermission { CallManager.startOutgoing(contact.name, contact.code) }
                            },
                            onAnswerOffer = { raw, onResult ->
                                withMicPermission { onResult(CallManager.handleSignal(raw, autoAccept = true)) }
                            }
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                CallManager.uiState.collect { applyWindowMode(it.state) }
            }
        }
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // С экрана запуск фонового сервиса всегда разрешён — даже если из фона не удалось.
        ListenService.sync(this)
        RelaySignaling.reconnectNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            // Действие одноразовое: не повторяем его при пересоздании Activity.
            intent.action = ACTION_SHOW_CALL
            // Activity экспортирована (это главный экран), поэтому действию «принять» верим,
            // только если его прислало наше собственное уведомление.
            val trusted = intent.getStringExtra(EXTRA_ANSWER_TOKEN) == CallManager.answerToken
            intent.removeExtra(EXTRA_ANSWER_TOKEN)
            if (trusted && CallManager.uiState.value.state == CallState.RINGING) {
                withMicPermission { CallManager.acceptIncoming() }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Буфер обмена доступен только приложению в фокусе.
        if (hasFocus) checkClipboardForSignal()
    }

    /**
     * Если пользователь скопировал в мессенджере приглашение или ответ и вернулся
     * в приложение — подхватываем код сами, без ручной вставки.
     */
    private fun checkClipboardForSignal() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val description = clipboard.primaryClipDescription ?: return
            if (description.label == CLIP_LABEL) return // это мы сами скопировали
            val timestamp = description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp

            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            if (text.length > SignalCodec.MAX_SIGNAL_CHARS * 2) return
            if (!SignalCodec.looksLikeSignal(SignalCodec.extract(text) ?: return)) return
            when (CallManager.handleSignal(text, auto = true)) {
                SignalResult.AnswerApplied ->
                    Toast.makeText(this, getString(R.string.answer_received), Toast.LENGTH_SHORT).show()
                else -> {}
            }
        } catch (e: Exception) {
            // Буфер недоступен — пользователь вставит код вручную.
        }
    }

    private fun applyWindowMode(state: CallState) {
        val ringing = state == CallState.RINGING
        val active = state.isActive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(active)
            setTurnScreenOn(ringing)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (active) window.addFlags(flags) else window.clearFlags(flags)
        }
        if (ringing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        volumeControlStream =
            if (active && !ringing) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
    }

    private fun withMicPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingMicAction = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()
        fun want(permission: String) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needed += permission
            }
        }
        want(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) want(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) want(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) startupPermissions.launch(needed.toTypedArray())
    }

    companion object {
        const val ACTION_ANSWER = "com.dpcall.app.action.ANSWER"
        const val ACTION_SHOW_CALL = "com.dpcall.app.action.SHOW_CALL"
        const val EXTRA_ANSWER_TOKEN = "com.dpcall.app.extra.ANSWER_TOKEN"

        /** Метка наших собственных записей в буфере обмена. */
        const val CLIP_LABEL = "DP Call signal"
    }
}
