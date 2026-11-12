package com.dpsmpx.dpcall.ui.home

import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpcall.R
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
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpsmpx.dpcall.call.ListenService
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.call.RelayStatus
import com.dpsmpx.dpcall.call.SignalResult
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.util.CodeValidator
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.ui.components.Avatar
import com.dpsmpx.dpcall.ui.components.shareText

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
    var showQr by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val callLog by viewModel.callLog.collectAsStateWithLifecycle()
    val scanQr = rememberQrScanner { scanned -> viewModel.onAddCodeChange(scanned) }
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
                        Text(stringResource(R.string.answer_action))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            if (!state.isLoading && state.error == null) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.openAddDialog() },
                    icon = { Icon(Icons.Default.PersonAdd, null) },
                    text = { Text(stringResource(R.string.contact)) }
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
                                                        copied = state.copied,
                            onQr = { showQr = true },
                            onCopy = { viewModel.copyCodeToClipboard() },
                            onShare = {
                                val code = state.identity?.value ?: return@OwnCodeCard
                                shareText(context, code, context.getString(R.string.share_my_code))
                            }
                        )
                    }
                    item(key = "tabs") {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = Color.Transparent,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        ) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                                Text(if (state.contacts.isEmpty()) stringResource(R.string.contacts) else stringResource(R.string.contacts_count, state.contacts.size))
                            })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.call_log)) })
                        }
                    }
                    if (tab == 1) {
                        if (callLog.isEmpty()) {
                            item(key = "log-empty") {
                                Text(
                                    stringResource(R.string.call_log_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
                                )
                            }
                        } else {
                            items(callLog, key = { "log-${it.startedAt}-${it.code}" }) { entry ->
                                // Имя берём из контактов, если он ещё есть; иначе — сохранённое в журнале.
                                val contact = state.contacts.firstOrNull { it.code.equals(entry.code, ignoreCase = true) }
                                val name = contact?.name ?: entry.name
                                CallLogRow(
                                    entry = entry,
                                    displayName = name,
                                    onCall = if (CodeValidator.isValid(entry.code)) {
                                        { onCall(Contact(code = entry.code, name = name.ifBlank { entry.code.take(5) })) }
                                    } else {
                                        null
                                    }
                                )
                            }
                            item(key = "log-clear") {
                                TextButton(onClick = { viewModel.clearCallLog() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.call_log_clear))
                                }
                            }
                        }
                    } else if (state.contacts.isEmpty()) {
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
            onScan = scanQr,
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
                        SignalResult.Own -> viewModel.setIncomingError(context.getString(R.string.own_invite))
                        SignalResult.Busy -> viewModel.setIncomingError(context.getString(R.string.finish_current_call))
                        SignalResult.Unexpected -> viewModel.setIncomingError(context.getString(R.string.answer_not_invite))
                        is SignalResult.Invalid -> viewModel.setIncomingError(result.message)
                    }
                }
            },
            onDismiss = viewModel::closeIncomingDialog
        )
    }

    if (showQr) {
        QrDialog(code = state.identity?.value.orEmpty(), onDismiss = { showQr = false })
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
            title = { Text(stringResource(R.string.delete_contact_title)) },
            text = { Text(stringResource(R.string.delete_contact_text, contact.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeContact(contact.code)
                    contactToDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { contactToDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    contactToRename?.let { contact ->
        var name by remember(contact.code) { mutableStateOf(contact.name) }
        AlertDialog(
            onDismissRequest = { contactToRename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameContact(contact.code, name)
                        contactToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { contactToRename = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private val OnlineGreen = Color(0xFF2E9E5B)

@Composable
private fun ConnectionStatus(status: RelayStatus, onClick: () -> Unit) {
    val color = if (status.online) OnlineGreen else MaterialTheme.colorScheme.error
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                if (status.online) stringResource(R.string.online_banner) else stringResource(R.string.listen_offline),
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
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (relayStatus.online) stringResource(R.string.relays_connected, relayStatus.connected, relayStatus.total)
                    else stringResource(R.string.relays_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (relayStatus.online) OnlineGreen else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    title = stringResource(R.string.setting_background),
                    description = stringResource(R.string.setting_background_desc),
                    checked = background,
                    onChange = {
                        settings.setBackgroundListening(it)
                        ListenService.sync(context)
                    }
                )
                SettingRow(
                    title = stringResource(R.string.setting_contacts_only),
                    description = stringResource(R.string.setting_contacts_only_desc),
                    checked = contactsOnly,
                    onChange = { settings.setContactsOnly(it) }
                )
                if (background && !unrestricted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.battery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { requestBatteryExemption(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.battery_button))
                    }
                }
                Spacer(Modifier.height(8.dp))
                AdvancedSettings()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onHelp) { Text(stringResource(R.string.how_to_use)) } }
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
        title = { Text(if (firstRun) stringResource(R.string.welcome) else stringResource(R.string.how_to_use)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.onboarding_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OnboardingStep(
                    1, stringResource(R.string.onboarding_1_title),
                    stringResource(R.string.onboarding_1_text)
                )
                OnboardingStep(
                    2, stringResource(R.string.onboarding_2_title),
                    stringResource(R.string.onboarding_2_text)
                )
                OnboardingStep(
                    3, stringResource(R.string.onboarding_3_title),
                    stringResource(R.string.onboarding_3_text)
                )
                OnboardingStep(
                    4, stringResource(R.string.onboarding_4_title),
                    stringResource(R.string.onboarding_4_text)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val version = appVersion(context)
                if (!firstRun && version.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.version_fmt, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDone) { Text(if (firstRun) stringResource(R.string.start) else stringResource(R.string.got_it)) } }
    )
}

@Composable
private fun OwnCodeCard(copied: Boolean, onQr: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        // Сам код не показывается: он длинный и нужен только для передачи собеседнику.
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Поля уже стандартных: иначе «Копировать» не помещается на узких экранах.
            val padding = PaddingValues(horizontal = 12.dp)
            Button(onClick = onCopy, modifier = Modifier.weight(1f), contentPadding = padding) {
                Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), maxLines = 1)
            }
            FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f), contentPadding = padding) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.send), maxLines = 1)
            }
            FilledTonalIconButton(onClick = onQr) {
                Icon(Icons.Default.QrCode2, stringResource(R.string.show_qr))
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
        Text(stringResource(R.string.contacts_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.contacts_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_contact))
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
                        stringResource(R.string.legacy_code),
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
                    Icon(Icons.Default.Call, stringResource(R.string.call_contact, contact.name))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
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
    onPaste: () -> Unit, onScan: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_contact)) },
        text = {
            Column {
                OutlinedTextField(
                    value = codeInput, onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.peer_code)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = onScan) { Icon(Icons.Default.QrCodeScanner, stringResource(R.string.scan_qr)) }
                            IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, stringResource(R.string.paste)) }
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameInput, onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isAdding && codeInput.isNotBlank()) {
                if (isAdding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
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
        title = { Text(stringResource(R.string.answer_call_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.answer_call_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = offerInput, onValueChange = onOfferChange,
                    label = { Text(stringResource(R.string.invitation)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, stringResource(R.string.paste)) } }
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = offerInput.isNotBlank()) { Text(stringResource(R.string.answer_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
