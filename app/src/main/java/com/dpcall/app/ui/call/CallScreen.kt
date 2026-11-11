package com.dpcall.app.ui.call

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpcall.app.webrtc.CallController
import com.dpcall.app.webrtc.CallState

@Composable
fun CallScreen(
    controller: CallController,
    onClose: () -> Unit
) {
    val state by controller.uiState.collectAsState()
    val context = LocalContext.current
    var pasteInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = state.contactName.ifBlank { "Звонок" },
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText(state.state),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.connectionState.isNotBlank() && state.state == CallState.CONNECTING) {
                Text(
                    text = state.connectionState,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = err, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            when (state.state) {
                CallState.CREATING_OFFER, CallState.CREATING_ANSWER -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Подготовка…")
                }
                CallState.WAITING_FOR_ANSWER -> {
                    Text(
                        text = "Скопируйте Offer и отправьте собеседнику.\nЗатем вставьте его Answer.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    state.signalToCopy?.let { signal ->
                        OutlinedButton(onClick = { copyToClipboard(context, signal) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Скопировать Offer")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        label = { Text("Answer от собеседника") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (!clip.isNullOrBlank()) pasteInput = clip
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentPaste, null)
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("Вставить")
                        }
                        Button(
                            onClick = { if (pasteInput.isNotBlank()) controller.applyRemoteAnswer(pasteInput) },
                            modifier = Modifier.weight(1f),
                            enabled = pasteInput.isNotBlank()
                        ) { Text("Применить") }
                    }
                }
                CallState.CONNECTING -> {
                    if (state.signalToCopy != null) {
                        Text(text = "Скопируйте Answer и отправьте собеседнику.", textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = { copyToClipboard(context, state.signalToCopy!!) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Скопировать Answer")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Соединяемся…")
                }
                CallState.CONNECTED -> {
                    Text(text = "Разговор", style = MaterialTheme.typography.titleLarge)
                }
                CallState.FAILED, CallState.ENDED -> {
                    Button(onClick = onClose) { Text("Закрыть") }
                }
                CallState.IDLE -> {}
            }
        }

        if (state.state !in listOf(CallState.IDLE, CallState.ENDED, CallState.FAILED)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { controller.setMuted(!state.isMuted) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (state.isMuted)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Микрофон",
                        modifier = Modifier.size(28.dp)
                    )
                }
                FilledIconButton(
                    onClick = { controller.hangup(); onClose() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Завершить",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

private fun statusText(state: CallState): String = when (state) {
    CallState.IDLE -> ""
    CallState.CREATING_OFFER -> "Создание Offer…"
    CallState.WAITING_FOR_ANSWER -> "Ожидание Answer"
    CallState.CREATING_ANSWER -> "Создание Answer…"
    CallState.CONNECTING -> "Соединение…"
    CallState.CONNECTED -> "На линии"
    CallState.ENDED -> "Звонок завершён"
    CallState.FAILED -> "Ошибка"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DP Call signal", text))
}
