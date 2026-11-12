package com.dpcall.app.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpcall.app.call.ListenService
import com.dpcall.app.call.RelaySignaling
import com.dpcall.app.call.RelayStatus
import com.dpcall.app.call.SignalResult
import com.dpcall.app.data.AppSettings
import com.dpcall.app.util.CodeValidator
import com.dpcall.app.domain.Contact
import com.dpcall.app.ui.components.Avatar
import com.dpcall.app.ui.components.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOnboardingDone: () -> Unit,
    onCall: (Contact) -> Unit,
    onAnswerOffer: (raw: String, onResult: (SignalResult) -> Unit) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var contactToDelete by remember { mutableStateOf<Contact?>(null) }
    var contactToRename by remember { mutableStateOf<Contact?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val appSettings = remember { AppSettings.get(context) }
    val onboardingShown by appSettings.onboardingShown.collectAsStateWithLifecycle()
    val relayStatus by RelaySignaling.status.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DP Call", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    FilledTonalButton(
                        onClick = { viewModel.openIncomingDialog() },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PhoneCallback, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ответить")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!state.isLoading && state.error == null) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openAddDialog() },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    text = { Text("Контакт") }
                )
            }
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "status") { ConnectionStatus(relayStatus, onClick = { showSettings = true }) }
                    item(key = "own-code") {
                        OwnCodeCard(
                            code = state.identity?.value ?: "",
                            copied = state.copied,
                            onCopy = { viewModel.copyCodeToClipboard() },
                            onShare = {
                                val code = state.identity?.value ?: return@OwnCodeCard
                                shareText(context, code, "Отправить мой код")
                            }
                        )
                    }
                    item(key = "header") {
                        Text(
                            if (state.contacts.isEmpty()) "Контакты" else "Контакты · ${state.contacts.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)
                        )
                    }
                    if (state.contacts.isEmpty()) {
                        item(key = "empty") { EmptyContacts(onAdd = { viewModel.openAddDialog() }) }
                    } else {
                        items(state.contacts, key = { it.code }) { contact ->
                            ContactItem(
                                contact = contact,
                                onCall = { onCall(contact) },
                                onRename = { contactToRename = contact },
                                onDelete = { contactToDelete = contact }
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showAddDialog) {
        AddContactDialog(
            codeInput = state.addCodeInput,
            nameInput = state.addNameInput,
            error = state.addError,
            isAdding = state.isAdding,
            onCodeChange = viewModel::onAddCodeChange,
            onNameChange = viewModel::onAddNameChange,
            onPaste = viewModel::pasteCodeFromClipboard,
            onConfirm = viewModel::confirmAddContact,
            onDismiss = viewModel::closeAddDialog
        )
    }

    if (state.showIncomingDialog) {
        IncomingOfferDialog(
            offerInput = state.incomingOfferInput,
            error = state.incomingError,
            onOfferChange = viewModel::onIncomingOfferChange,
            onPaste = viewModel::pasteIncomingOffer,
            onAccept = {
                onAnswerOffer(state.incomingOfferInput) { result ->
                    when (result) {
                        SignalResult.Ringing, SignalResult.Ignored -> viewModel.closeIncomingDialog()
                        SignalResult.AnswerApplied -> viewModel.closeIncomingDialog()
                        SignalResult.Own -> viewModel.setIncomingError("Это ваше собственное приглашение — отправьте его собеседнику")
                        SignalResult.Busy -> viewModel.setIncomingError("Сначала завершите текущий звонок")
                        SignalResult.Unexpected -> viewModel.setIncomingError("Это ответ, а не приглашение. Ответ вставляют на экране исходящего звонка")
                        is SignalResult.Invalid -> viewModel.setIncomingError(result.message)
                    }
                }
            },
            onDismiss = viewModel::closeIncomingDialog
        )
    }

    if (showSettings) {
        SettingsDialog(
            relayStatus = relayStatus,
            onHelp = {
                showSettings = false
                showHelp = true
            },
            onDismiss = { showSettings = false }
        )
    }

    if (!onboardingShown || showHelp) {
        OnboardingDialog(
            firstRun = !onboardingShown,
            onDone = {
                showHelp = false
                if (!onboardingShown) {
                    appSettings.setOnboardingShown()
                    onOnboardingDone()
                }
            }
        )
    }

    contactToDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("Удалить контакт?") },
            text = { Text("«${contact.name}» будет удалён из списка. Код можно добавить снова.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeContact(contact.code)
                    contactToDelete = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { contactToDelete = null }) { Text("Отмена") } }
        )
    }

    contactToRename?.let { contact ->
        var name by remember(contact.code) { mutableStateOf(contact.name) }
        AlertDialog(
            onDismissRequest = { contactToRename = null },
            title = { Text("Переименовать") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameContact(contact.code, name)
                        contactToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { contactToRename = null }) { Text("Отмена") } }
        )
    }
}

/** Код разбивается на группы только для отображения — копируется он целиком. */
private fun formatCode(code: String): String = code.chunked(4).joinToString(" ")

private val OnlineGreen = Color(0xFF2E9E5B)

@Composable
private fun ConnectionStatus(status: RelayStatus, onClick: () -> Unit) {
    val color = if (status.online) OnlineGreen else MaterialTheme.colorScheme.error
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                if (status.online) "На связи — вам можно позвонить" else "Нет связи — звонки не дойдут",
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
        }
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife") // для звонилки без push это единственный способ не пропускать вызовы
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (ignored: Exception) {
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsDialog(relayStatus: RelayStatus, onHelp: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val background by settings.backgroundListening.collectAsStateWithLifecycle()
    val contactsOnly by settings.contactsOnly.collectAsStateWithLifecycle()
    // Перечитывается при каждом открытии диалога и после возврата из системных настроек.
    val unrestricted = isBatteryUnrestricted(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column {
                Text(
                    if (relayStatus.online) "На связи: ${relayStatus.connected} из ${relayStatus.total} релеев"
                    else "Нет связи с релеями. Проверьте интернет и VPN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (relayStatus.online) OnlineGreen else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    title = "Принимать звонки в фоне",
                    description = "Приложение остаётся на связи, когда закрыто. В шторке висит тихое уведомление — его можно скрыть в настройках Android",
                    checked = background,
                    onChange = {
                        settings.setBackgroundListening(it)
                        ListenService.sync(context)
                    }
                )
                SettingRow(
                    title = "Только от контактов",
                    description = "Вызовы с незнакомых кодов молча отбрасываются",
                    checked = contactsOnly,
                    onChange = { settings.setContactsOnly(it) }
                )
                if (background && !unrestricted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android может усыплять приложение и задерживать звонки. Чтобы этого не было, разрешите работу без ограничений батареи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { requestBatteryExemption(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Не ограничивать в фоне")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onHelp) { Text("Как пользоваться") } }
    )
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

@Composable
private fun OnboardingStep(number: Int, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Вводная инструкция: показывается при первом запуске и по кнопке «Как пользоваться». */
@Composable
private fun OnboardingDialog(firstRun: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        // При первом запуске закрывается только кнопкой — чтобы инструкцию не смахнули случайно.
        onDismissRequest = { if (!firstRun) onDone() },
        icon = { Icon(Icons.Default.Call, null) },
        title = { Text(if (firstRun) "Добро пожаловать в DP Call" else "Как пользоваться") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Голосовые звонки напрямую между телефонами: без регистрации, номера и серверов.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OnboardingStep(
                    1, "Обменяйтесь кодами",
                    "Отправьте собеседнику «Ваш код» с главного экрана и получите его код — через любой мессенджер."
                )
                OnboardingStep(
                    2, "Добавьте друг друга",
                    "Кнопка «Контакт». Добавить должны оба: звонки с незнакомых кодов по умолчанию не принимаются."
                )
                OnboardingStep(
                    3, "Звоните",
                    "Нажмите на трубку рядом с контактом — у собеседника зазвонит телефон, даже если приложение закрыто."
                )
                OnboardingStep(
                    4, "Оставайтесь на связи",
                    "Разрешите микрофон и уведомления. Чтобы не пропускать звонки, в настройках (шестерёнка) " +
                        "включите «Не ограничивать в фоне». Зелёная плашка «На связи» значит, что вам можно позвонить."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Если собеседник не на связи, приглашение можно отправить вручную — кнопка появится на экране звонка.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val version = appVersion(context)
                if (!firstRun && version.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Версия $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDone) { Text(if (firstRun) "Начать" else "Понятно") } }
    )
}

@Composable
private fun OwnCodeCard(code: String, copied: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                "Ваш код",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                formatCode(code),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    lineHeight = 30.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Передайте его собеседнику, чтобы он добавил вас в контакты",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Скопировано" else "Копировать", maxLines = 1)
                }
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Отправить", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EmptyContacts(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Group, null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text("Пока никого нет", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Добавьте собеседника по его коду, чтобы позвонить",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Добавить контакт")
        }
    }
}

@Composable
private fun ContactItem(contact: Contact, onCall: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val legacy = !CodeValidator.isValid(contact.code)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(name = contact.name, seed = contact.code)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (legacy) {
                    Text(
                        "Старый код — попросите новый и добавьте заново",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                } else {
                    Text(
                        contact.code.take(5) + " … " + contact.code.takeLast(5),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (legacy) {
                Icon(
                    Icons.Default.Warning, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            } else {
                FilledTonalIconButton(onClick = onCall) {
                    Icon(Icons.Default.Call, "Позвонить: ${contact.name}")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Ещё", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddContactDialog(
    codeInput: String, nameInput: String, error: String?, isAdding: Boolean,
    onCodeChange: (String) -> Unit, onNameChange: (String) -> Unit,
    onPaste: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый контакт") },
        text = {
            Column {
                OutlinedTextField(
                    value = codeInput, onValueChange = onCodeChange,
                    label = { Text("Код собеседника") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Вставить из буфера") } }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameInput, onValueChange = onNameChange,
                    label = { Text("Имя") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isAdding && codeInput.isNotBlank()) {
                if (isAdding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun IncomingOfferDialog(
    offerInput: String, error: String?, onOfferChange: (String) -> Unit,
    onPaste: () -> Unit, onAccept: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.PhoneCallback, null) },
        title = { Text("Ответить на звонок") },
        text = {
            Column {
                Text(
                    "Вставьте приглашение, которое прислал собеседник. " +
                        "Быстрее: в мессенджере нажмите «Поделиться → DP Call» или просто скопируйте его и откройте приложение.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = offerInput, onValueChange = onOfferChange,
                    label = { Text("Приглашение") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Вставить из буфера") } }
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = offerInput.isNotBlank()) { Text("Ответить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
