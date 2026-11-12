package com.dpcall.app.ui.call

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpcall.app.call.CallManager
import com.dpcall.app.data.ContactsRepository
import com.dpcall.app.domain.Contact
import com.dpcall.app.util.CodeValidator
import kotlinx.coroutines.launch
import com.dpcall.app.call.CallUiState
import com.dpcall.app.call.LinkQuality
import com.dpcall.app.call.SignalResult
import com.dpcall.app.ui.MainActivity
import com.dpcall.app.ui.components.Avatar
import com.dpcall.app.ui.components.shareText
import com.dpcall.app.webrtc.AudioRoute
import com.dpcall.app.webrtc.CallState
import kotlinx.coroutines.delay

private val AcceptGreen = Color(0xFF2E9E5B)
private val DeclineRed = Color(0xFFD93B3B)

@Composable
fun CallScreen(state: CallUiState, onAccept: () -> Unit, onMinimize: () -> Unit) {
    val finished = state.state == CallState.ENDED || state.state == CallState.FAILED

    // «Назад» во время звонка сворачивает приложение — звонок продолжается.
    BackHandler { if (finished) CallManager.dismiss() else onMinimize() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopBar(state, finished, onMinimize)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                PulsingAvatar(
                    name = state.contactName.ifBlank { "?" },
                    seed = state.contactCode,
                    pulsing = state.state == CallState.RINGING || state.state == CallState.CONNECTING ||
                        state.isReconnecting ||
                        (state.state == CallState.WAITING_FOR_ANSWER && state.viaRelay && !state.peerUnreachable)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    state.contactName.ifBlank { "Звонок" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!state.isKnownContact && state.contactCode.isNotBlank()) {
                    Text(
                        state.contactCode.take(5) + " … " + state.contactCode.takeLast(5),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                StatusLine(state)
                if (!state.isKnownContact && CodeValidator.isValid(state.contactCode)) {
                    AddUnknownContact(state.contactCode)
                }
                Spacer(Modifier.height(24.dp))

                when (state.state) {
                    CallState.WAITING_FOR_ANSWER -> state.signalToCopy?.let { signal ->
                        ManualFallback(
                            automatic = state.viaRelay && !state.peerUnreachable,
                            label = "Отправить приглашение вручную",
                            hint = if (state.peerUnreachable) {
                                "Вызов не дошёл: приложение собеседника не на связи. " +
                                    "Отправьте приглашение через мессенджер — он ответит, когда увидит."
                            } else {
                                null
                            }
                        ) { OfferExchange(signal) }
                    }
                    CallState.CONNECTING -> state.signalToCopy?.let { signal ->
                        ManualFallback(
                            automatic = state.viaRelay,
                            label = "Отправить ответ вручную",
                            hint = null
                        ) { AnswerExchange(signal) }
                    }
                    CallState.FAILED -> state.errorMessage?.let { ErrorCard(it) }
                    else -> {}
                }
                Spacer(Modifier.height(16.dp))
            }

            when {
                state.state == CallState.RINGING -> IncomingControls(onAccept)
                state.state == CallState.FAILED ->
                    Button(onClick = { CallManager.dismiss() }, modifier = Modifier.widthIn(min = 200.dp)) {
                        Text("Закрыть")
                    }
                state.state == CallState.ENDED -> {}
                else -> InCallControls(state)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Звонок с кода, которого нет в контактах: предлагаем сохранить его под именем. */
@Composable
private fun AddUnknownContact(code: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    TextButton(onClick = { dialog = true }) {
        Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Добавить в контакты")
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("Новый контакт") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val contact = Contact(code = code, name = name.trim())
                        dialog = false
                        // Имя на экране обновится само: CallManager следит за списком контактов.
                        scope.launch { ContactsRepository(context.applicationContext).add(contact) }
                    },
                    enabled = name.isNotBlank()
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun TopBar(state: CallUiState, finished: Boolean, onMinimize: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!finished) {
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.KeyboardArrowDown, "Свернуть")
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.state == CallState.CONNECTED && state.quality != LinkQuality.UNKNOWN) {
            QualityChip(state.quality)
        }
    }
}

@Composable
private fun QualityChip(quality: LinkQuality) {
    val (label, color) = when (quality) {
        LinkQuality.GOOD -> "Отличная связь" to AcceptGreen
        LinkQuality.FAIR -> "Средняя связь" to Color(0xFFE0A100)
        else -> "Слабая связь" to DeclineRed
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SignalCellularAlt, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun PulsingAvatar(name: String, seed: String, pulsing: Boolean) {
    val size = 128.dp
    Box(Modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "pulse")
            listOf(0, 900).forEach { offset ->
                val progress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1800, delayMillis = offset, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ring$offset"
                )
                Box(
                    Modifier
                        .size(size)
                        .scale(1f + 0.45f * progress)
                        .alpha(0.28f * (1f - progress))
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Avatar(name = name, seed = seed, size = size)
    }
}

@Composable
private fun StatusLine(state: CallUiState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (state.state) {
        CallState.CONNECTED -> {
            CallTimer(state.connectedAt)
            val note = when {
                state.isOnHold -> "На удержании"
                state.isReconnecting -> "Связь прервалась, восстанавливаем…"
                state.isRemoteMuted -> "Собеседник выключил микрофон"
                state.isMuted -> "Ваш микрофон выключен"
                else -> null
            }
            AnimatedVisibility(visible = note != null) {
                Text(
                    note.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.isReconnecting) MaterialTheme.colorScheme.error else muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        CallState.ENDED -> {
            Text(state.statusMessage.ifBlank { "Звонок завершён" }, style = MaterialTheme.typography.titleMedium, color = muted)
            if (state.durationSec > 0) {
                Text(formatDuration(state.durationSec), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
        CallState.CREATING_OFFER, CallState.CREATING_ANSWER -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(state.statusMessage.ifBlank { "Подготовка…" }, style = MaterialTheme.typography.titleMedium, color = muted)
            }
        }
        CallState.RINGING -> Text("Входящий звонок DP Call", style = MaterialTheme.typography.titleMedium, color = muted)
        CallState.FAILED -> Text(
            state.statusMessage.ifBlank { "Ошибка" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        else -> Text(
            when {
                state.state == CallState.CONNECTING && (state.signalToCopy == null || state.viaRelay) -> "Соединяемся…"
                state.state == CallState.WAITING_FOR_ANSWER && state.viaRelay -> state.statusMessage.ifBlank { "Вызываем…" }
                state.isIncoming -> "Входящий звонок"
                else -> "Исходящий звонок"
            },
            style = MaterialTheme.typography.titleMedium,
            color = muted
        )
    }
}

@Composable
private fun CallTimer(connectedAt: Long) {
    var seconds by remember(connectedAt) {
        mutableLongStateOf(((SystemClock.elapsedRealtime() - connectedAt) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(connectedAt) {
        while (true) {
            seconds = ((SystemClock.elapsedRealtime() - connectedAt) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    Text(
        formatDuration(seconds),
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ------------------------------------------------------------- обмен кодами

/**
 * Пока вызов доставляется сам (через релеи), ручной обмен кодами спрятан за
 * кнопкой; если автоматическая доставка невозможна — показан сразу.
 */
@Composable
private fun ManualFallback(automatic: Boolean, label: String, hint: String?, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (automatic && !expanded) {
            TextButton(onClick = { expanded = true }) { Text(label) }
        } else {
            content()
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        number.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SendButtons(signal: String, what: String) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { shareText(context, signal, "Отправить собеседнику") }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Отправить", maxLines = 1)
        }
        FilledTonalButton(
            onClick = {
                copyToClipboard(context, signal)
                Toast.makeText(context, "$what: скопировано", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Копировать", maxLines = 1)
        }
    }
}

@Composable
private fun OfferExchange(signal: String) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit(raw: String) {
        error = when (val result = CallManager.handleSignal(raw)) {
            SignalResult.AnswerApplied, SignalResult.Ignored -> null
            SignalResult.Own -> "Это ваше приглашение. Нужен ответ собеседника"
            SignalResult.Ringing, SignalResult.Busy -> "Это приглашение, а нужен ответ собеседника"
            SignalResult.Unexpected -> "Ответ сейчас не ожидается"
            is SignalResult.Invalid -> result.message
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepCard(1, "Отправьте приглашение собеседнику") {
            SendButtons(signal, "Приглашение")
        }
        StepCard(2, "Получите его ответ") {
            Text(
                "Скопируйте ответ в мессенджере и вернитесь сюда — он подхватится сам. " +
                    "Или нажмите в мессенджере «Поделиться → DP Call».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                label = { Text("Ответ собеседника") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (clip.isNullOrBlank()) {
                            error = "Буфер обмена пуст"
                        } else {
                            input = clip
                            submit(clip)
                        }
                    }) { Icon(Icons.Default.ContentPaste, "Вставить из буфера") }
                }
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { submit(input) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Соединить") }
        }
    }
}

@Composable
private fun AnswerExchange(signal: String) {
    StepCard(1, "Отправьте ответ собеседнику") {
        SendButtons(signal, "Ответ")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Разговор начнётся, как только собеседник вставит ответ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            message,
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ------------------------------------------------------------------ кнопки

@Composable
private fun RoundAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    size: Dp = 60.dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content)
        ) {
            Icon(icon, label, Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    RoundAction(
        icon = icon,
        label = label,
        onClick = onClick,
        container = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        content = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun IncomingControls(onAccept: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Default.CallEnd, "Отклонить", { CallManager.declineIncoming() }, DeclineRed, Color.White, 72.dp)
        RoundAction(Icons.Default.Call, "Принять", onAccept, AcceptGreen, Color.White, 72.dp)
    }
}

@Composable
private fun InCallControls(state: CallUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToggleAction(
                icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (state.isMuted) "Микрофон выкл." else "Микрофон",
                active = state.isMuted,
                onClick = { CallManager.toggleMute() }
            )
            ToggleAction(
                icon = Icons.Default.GraphicEq,
                label = "Шумоподавление",
                active = state.noiseSuppression,
                onClick = { CallManager.setNoiseSuppression(!state.noiseSuppression) }
            )
            AudioRouteAction(state)
        }
        Spacer(Modifier.height(28.dp))
        RoundAction(Icons.Default.CallEnd, "Завершить", { CallManager.hangup() }, DeclineRed, Color.White, 72.dp)
    }
}

private fun routeIcon(route: AudioRoute): ImageVector = when (route) {
    AudioRoute.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.BLUETOOTH -> Icons.Default.Bluetooth
    AudioRoute.WIRED_HEADSET -> Icons.Default.Headset
    AudioRoute.EARPIECE -> Icons.Default.PhoneInTalk
}

private fun routeLabel(route: AudioRoute): String = when (route) {
    AudioRoute.SPEAKER -> "Динамик"
    AudioRoute.BLUETOOTH -> "Bluetooth"
    AudioRoute.WIRED_HEADSET -> "Гарнитура"
    AudioRoute.EARPIECE -> "Телефон"
}

/** Две цели — простой переключатель динамика; больше (есть Bluetooth) — меню выбора. */
@Composable
private fun AudioRouteAction(state: CallUiState) {
    var menuOpen by remember { mutableStateOf(false) }
    val routes = state.availableRoutes
    Box {
        if (routes.size > 2) {
            ToggleAction(
                icon = routeIcon(state.audioRoute),
                label = routeLabel(state.audioRoute),
                active = state.audioRoute != AudioRoute.EARPIECE,
                onClick = { menuOpen = true }
            )
        } else {
            ToggleAction(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = "Динамик",
                active = state.audioRoute == AudioRoute.SPEAKER,
                onClick = { CallManager.toggleSpeaker() }
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            routes.forEach { route ->
                DropdownMenuItem(
                    text = {
                        Text(
                            routeLabel(route),
                            fontWeight = if (route == state.audioRoute) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    leadingIcon = { Icon(routeIcon(route), null) },
                    onClick = {
                        menuOpen = false
                        CallManager.setAudioRoute(route)
                    }
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(MainActivity.CLIP_LABEL, text))
}
