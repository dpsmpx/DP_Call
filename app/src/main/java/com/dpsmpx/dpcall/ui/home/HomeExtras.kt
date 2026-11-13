package com.dpsmpx.dpcall.ui.home

import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpcall.R
import com.dpsmpx.dpcall.tor.TorBridges
import androidx.compose.material3.RadioButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpcall.tor.TorProxy
import androidx.compose.material3.Switch
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.data.CallLogEntry
import com.dpsmpx.dpcall.data.CallOutcome
import com.dpsmpx.dpcall.data.TurnConfig
import com.dpsmpx.dpcall.signaling.relay.RelayPool
import com.dpsmpx.dpcall.ui.components.Avatar
import com.dpsmpx.dpcall.ui.components.shareText
import com.dpsmpx.dpcall.update.UpdateChecker
import com.dpsmpx.dpcall.util.CodeValidator
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.util.Diagnostics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ QR-коды

private fun qrBitmap(text: String, size: Int = 720): Bitmap? = try {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
} catch (e: Exception) {
    null
}

/** QR со своим кодом — чтобы собеседник отсканировал его при встрече. */
@Composable
fun QrDialog(code: String, onScan: () -> Unit, onDismiss: () -> Unit) {
    QrCodeDialog(
        content = code,
        title = stringResource(R.string.your_code),
        hint = stringResource(R.string.qr_hint),
        onDismiss = onDismiss,
        // Встретились лично — обычно сканируют друг друга: второй код под рукой.
        action = {
            Button(onClick = onScan) {
                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_their_code))
            }
        }
    )
}

/**
 * Отсканированный или присланный код: знакомому контакту — сразу позвонить, новому коду —
 * позвонить или сохранить. Ничего не происходит без нажатия: код мог прийти из чужого
 * приложения через «Поделиться».
 */
@Composable
fun FoundCodeDialog(
    code: String,
    contact: Contact?,
    own: Boolean,
    onCall: (Contact) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val short = code.take(5) + " … " + code.takeLast(5)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    own -> stringResource(R.string.found_own_title)
                    contact != null -> contact.name
                    else -> stringResource(R.string.found_new_title)
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(short, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
                Text(
                    when {
                        own -> stringResource(R.string.found_own_text)
                        contact != null -> stringResource(R.string.found_known_text)
                        else -> stringResource(R.string.found_new_text)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (!own) {
                Button(onClick = { onCall(contact ?: Contact(code = code, name = code.take(5))) }) {
                    Icon(Icons.Default.Call, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.call_action))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = if (own) null else {
            {
                Row {
                    if (contact == null) TextButton(onClick = onSave) { Text(stringResource(R.string.found_save)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    )
}

/**
 * Приглашение установить DP Call: QR со ссылкой на скачивание — для того, кто рядом
 * (камера телефона откроет ссылку), и кнопка отправки ссылки — для всех остальных.
 */
@Composable
fun InviteDialog(onShareLink: () -> Unit, onDismiss: () -> Unit) {
    QrCodeDialog(
        content = UpdateChecker.DIRECT_DOWNLOAD,
        title = stringResource(R.string.invite_title),
        hint = stringResource(R.string.invite_qr_hint),
        onDismiss = onDismiss,
        action = {
            Button(onClick = onShareLink) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.invite_send_link))
            }
        }
    )
}

@Composable
private fun QrCodeDialog(
    content: String,
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    action: (@Composable () -> Unit)? = null
) {
    val bitmap = remember(content) { qrBitmap(content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_code),
                        modifier = Modifier
                            .size(260.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = action ?: { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = if (action != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
        } else {
            null
        }
    )
}

/** Возвращает функцию запуска сканера; [onCode] получает распознанный код собеседника. */
@Composable
fun rememberQrScanner(onCode: (String) -> Unit): () -> Unit {
    val prompt = stringResource(R.string.qr_scan_prompt)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onCode)
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(prompt)
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }
}

// ------------------------------------------------------------ журнал звонков

@Composable
fun CallLogRow(entry: CallLogEntry, displayName: String, onCall: (() -> Unit)?, onAdd: (() -> Unit)? = null) {
    val context = LocalContext.current
    val missed = entry.outcome == CallOutcome.MISSED
    val (icon, tint) = when {
        missed -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.error
        entry.incoming -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val result = when (entry.outcome) {
        CallOutcome.ANSWERED -> DateUtils.formatElapsedTime(entry.durationSec)
        CallOutcome.MISSED -> stringResource(R.string.log_missed)
        CallOutcome.DECLINED -> stringResource(R.string.log_declined)
        CallOutcome.NOT_CONNECTED -> if (entry.incoming) stringResource(R.string.log_not_connected) else stringResource(R.string.no_answer)
    }
    val time = DateUtils.formatDateTime(
        context, entry.startedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    )
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(name = displayName, seed = entry.code)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(14.dp), tint = tint)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$time · $result",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onAdd != null) {
                IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Default.PersonAdd, stringResource(R.string.add_to_contacts),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onCall != null) {
                IconButton(onClick = onCall) {
                    Icon(Icons.Default.Call, stringResource(R.string.call_action), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ------------------------------------------------- дополнительные настройки

/**
 * Раздел настроек для редких случаев: резервная копия, ручной приём приглашения,
 * свой TURN, релеи, журнал ошибок.
 */
@Composable
fun AdvancedSettings(onManualInvite: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.advanced), Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (!expanded) return

    val saved = stringResource(R.string.saved)
    val relaysInvalid = stringResource(R.string.relays_invalid)
    val turnInvalid = stringResource(R.string.turn_invalid)
    val reportTitle = stringResource(R.string.send_error_log)

    // --- Tor (только в сборке с Tor)
    if (TorProxy.available) {
        val tor by settings.torSignaling.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tor_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.tor_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = tor, onCheckedChange = {
                settings.setTorSignaling(it)
                RelaySignaling.restart(context)
            })
        }
        if (tor) TorBridgesSection()
        Spacer(Modifier.height(16.dp))
    }

    // --- резервная копия
    BackupSection()

    Spacer(Modifier.height(16.dp))

    // --- ручной приём приглашения
    Text(stringResource(R.string.manual_invite_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.manual_invite_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    FilledTonalButton(onClick = onManualInvite, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.manual_invite_button))
    }

    Spacer(Modifier.height(16.dp))

    // --- TURN
    var turn by remember { mutableStateOf(settings.turn) }
    var turnError by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.turn_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.turn_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = turn.url,
        onValueChange = { turn = turn.copy(url = it); turnError = null },
        label = { Text(stringResource(R.string.address)) },
        placeholder = { Text("turn:example.org:3478") },
        singleLine = true,
        isError = turnError != null,
        supportingText = turnError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = turn.username, onValueChange = { turn = turn.copy(username = it) },
            label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = turn.password, onValueChange = { turn = turn.copy(password = it) },
            label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    FilledTonalButton(
        onClick = {
            if (turn.url.isNotBlank() && !TurnConfig(turn.url.trim()).isValid) {
                turnError = turnInvalid
            } else {
                settings.turn = turn
                Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.turn_save)) }

    Spacer(Modifier.height(16.dp))

    // --- релеи
    var relays by remember { mutableStateOf(settings.relays.value.joinToString("\n")) }
    var relaysError by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.relays), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.relays_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = relays,
        onValueChange = { relays = it; relaysError = null },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        minLines = 3,
        maxLines = 8,
        isError = relaysError != null,
        supportingText = relaysError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                settings.resetRelays()
                relays = RelayPool.DEFAULT_RELAYS.joinToString("\n")
                relaysError = null
                RelaySignaling.restart(context)
            },
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.defaults)) }
        FilledTonalButton(
            onClick = {
                if (settings.setRelays(relays)) {
                    relays = settings.relays.value.joinToString("\n")
                    RelaySignaling.restart(context)
                    Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                } else {
                    relaysError = relaysInvalid
                }
            },
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.save)) }
    }

    Spacer(Modifier.height(16.dp))

    // --- диагностика
    Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.diagnostics_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = {
            scope.launch {
                val report = withContext(Dispatchers.IO) { Diagnostics.buildReport(context) }
                shareText(context, report, reportTitle)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.send_error_log)) }
}

/**
 * Мосты Tor: в России прямое подключение к Tor заблокировано. Встроенные мосты — как в Tor
 * Browser; свои можно получить у @GetBridgesBot или на bridges.torproject.org.
 */
@Composable
private fun TorBridgesSection() {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val mode by settings.torBridgesMode.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(settings.torCustomBridges) }
    var error by remember { mutableStateOf<String?>(null) }
    val noBridges = stringResource(R.string.tor_bridges_invalid)
    val saved = stringResource(R.string.saved)

    fun apply(newMode: TorBridges.Mode, text: String = custom) {
        settings.setTorBridges(newMode, text)
        // Мосты читаются при запуске Tor — перезапускаем его.
        TorProxy.stop(context)
        RelaySignaling.restart(context)
    }

    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.tor_bridges_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.tor_bridges_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    listOf(
        TorBridges.Mode.BUILTIN to R.string.tor_bridges_builtin,
        TorBridges.Mode.CUSTOM to R.string.tor_bridges_custom,
        TorBridges.Mode.NONE to R.string.tor_bridges_none
    ).forEach { (option, label) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(selected = mode == option, onClick = {
                error = null
                if (option == TorBridges.Mode.CUSTOM && TorBridges.parse(custom).isEmpty()) {
                    // Своих мостов ещё нет — сначала поле для них, переключимся после «Сохранить».
                    settings.setTorBridges(TorBridges.Mode.CUSTOM, custom)
                } else if (option != mode) {
                    apply(option)
                }
            })
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (mode == TorBridges.Mode.CUSTOM) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it; error = null },
            label = { Text(stringResource(R.string.tor_bridges_field)) },
            placeholder = { Text("webtunnel [2001:db8::1]:443 … url=…") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        FilledTonalButton(onClick = {
            if (TorBridges.parse(custom).isEmpty()) {
                error = noBridges
            } else {
                apply(TorBridges.Mode.CUSTOM, custom)
                Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }
}
