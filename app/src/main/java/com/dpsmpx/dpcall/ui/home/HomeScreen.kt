package com.dpsmpx.dpcall.ui.home

import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpcall.R
import com.dpsmpx.dpcall.call.CallManager
import com.dpsmpx.dpcall.call.NetworkRestriction
import com.dpsmpx.dpcall.call.Restriction
import com.dpsmpx.dpcall.webrtc.CallState
import com.dpsmpx.dpcall.webrtc.PathCheck
import com.dpsmpx.dpcall.webrtc.PathProbe
import com.dpsmpx.dpcall.tor.TorState
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpsmpx.dpcall.call.CallNotifications
import com.dpsmpx.dpcall.call.ListenService
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.call.RelayStatus
import com.dpsmpx.dpcall.call.SignalResult
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.util.CodeValidator
import com.dpsmpx.dpcall.util.Vendor
import com.dpsmpx.dpcall.domain.Contact
import com.dpsmpx.dpcall.ui.components.Avatar
import com.dpsmpx.dpcall.ui.components.shareText
import com.dpsmpx.dpcall.update.UpdateChecker
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

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
    var showCarrier by remember { mutableStateOf(false) }
    val carrierStatus by com.dpsmpx.dpcall.carrier.CarrierTransport.status.collectAsStateWithLifecycle()
    val roomActive = carrierStatus.phase != "off"
    var showQr by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    val tab = state.tab
    val callLog by viewModel.callLog.collectAsStateWithLifecycle()
    val scanQr = rememberQrScanner { scanned -> viewModel.onCodeFound(scanned, fillAddDialog = viewModel.uiState.value.showAddDialog) }
    var showHelp by remember { mutableStateOf(false) }
    val appSettings = remember { AppSettings.get(context) }
    val onboardingShown by appSettings.onboardingShown.collectAsStateWithLifecycle()
    val relayStatus by RelaySignaling.status.collectAsStateWithLifecycle()
    val background by appSettings.backgroundListening.collectAsStateWithLifecycle()
    val unseenMissed by appSettings.unseenMissed.collectAsStateWithLifecycle()
    val storedUpdate by appSettings.availableUpdate.collectAsStateWithLifecycle()
    val update = remember(storedUpdate) { UpdateChecker.pendingUpdate(context, storedUpdate) }
    // Разрешения и ограничения меняются в системных настройках — перечитываем при возврате.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val pathCheck by PathProbe.result.collectAsStateWithLifecycle()
    val restriction by NetworkRestriction.state.collectAsStateWithLifecycle()
    var showConnectionCheck by remember { mutableStateOf(false) }
    // Проверка маршрутов (STUN/TURN) — при открытии и после смены сети, не чаще раза в 10 минут.
    LaunchedEffect(resumes, roomActive) {
        PathProbe.runIfStale(context, busy = CallManager.uiState.value.state != CallState.IDLE)
    }
    val vendorSetupDone by appSettings.vendorSetupDone.collectAsStateWithLifecycle()
    var showVendorSetup by remember { mutableStateOf(false) }
    val readiness = remember(relayStatus.online, background, resumes, pathCheck, vendorSetupDone) {
        readinessOf(context, relayStatus.online, background, pathCheck, vendorSetupDone)
    }
    // Журнал открыт — пропущенные просмотрены.
    LaunchedEffect(tab, unseenMissed) {
        if (tab == 1 && unseenMissed > 0) {
            appSettings.clearUnseenMissed()
            CallNotifications.clearMissedCalls(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DP Call", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    // Без приложения собеседнику не дозвониться — ссылка на него всегда под рукой.
                    FilledTonalButton(
                        onClick = { showInvite = true },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        Icon(Icons.Default.GroupAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.invite))
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
                    item(key = "status") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!roomActive) {
                            ReadinessBanner(readiness, pathCheck, relayStatus.tor, restriction) {
                                when (readiness) {
                                    Readiness.NO_NOTIFICATIONS -> openNotificationSettings(context)
                                    Readiness.BATTERY_RESTRICTED -> requestBatteryExemption(context)
                                    Readiness.VENDOR_SETUP -> showVendorSetup = true
                                    Readiness.READY, Readiness.NO_PATH, Readiness.OFFLINE -> showConnectionCheck = true
                                    else -> showSettings = true
                                }
                            }
                        }
                        if (roomActive || showCarrier) {
                            com.dpsmpx.dpcall.carrier.CarrierPanel { code ->
                                onCall(state.contacts.firstOrNull { it.code == code } ?: Contact(name = code.take(5), code = code))
                            }
                        }
                        if (!roomActive) TextButton(onClick = { showCarrier = !showCarrier }) {
                            Text(stringResource(if (showCarrier) R.string.close else R.string.carrier_title))
                        }
                        }
                    }
                    update?.let { available ->
                        item(key = "update") {
                            UpdateBanner(
                                version = available.version,
                                onDownload = { openUrl(context, available.url) },
                                onDismiss = { appSettings.dismissUpdate() }
                            )
                        }
                    }
                    item(key = "own-code") {
                        OwnCodeCard(
                                                        copied = state.copied,
                            onQr = { showQr = true },
                            onCopy = { viewModel.copyCodeToClipboard() },
                            onShare = {
                                val code = state.identity?.value ?: return@OwnCodeCard
                                // Получатель должен понять, что это и где взять приложение.
                                shareText(
                                    context,
                                    context.getString(R.string.share_code_text, code, UpdateChecker.DIRECT_DOWNLOAD, UpdateChecker.RELEASES_PAGE),
                                    context.getString(R.string.share_my_code)
                                )
                            }
                        )
                    }
                    item(key = "tabs") {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = Color.Transparent,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        ) {
                            Tab(selected = tab == 0, onClick = { viewModel.selectTab(0) }, text = {
                                Text(if (state.contacts.isEmpty()) stringResource(R.string.contacts) else stringResource(R.string.contacts_count, state.contacts.size))
                            })
                            Tab(selected = tab == 1, onClick = { viewModel.selectTab(1) }, text = {
                                if (unseenMissed > 0 && tab != 1) {
                                    Text(
                                        stringResource(R.string.call_log_missed_count, unseenMissed),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text(stringResource(R.string.call_log))
                                }
                            })
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
                                    },
                                    // Незнакомого абонента можно сохранить прямо из журнала.
                                    onAdd = if (contact == null && CodeValidator.isValid(entry.code)) {
                                        { viewModel.openAddDialog(entry.code) }
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
                        item(key = "empty") {
                            EmptyContacts(onAdd = { viewModel.openAddDialog() }, onInvite = { showInvite = true })
                        }
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
        QrDialog(
            code = state.identity?.value.orEmpty(),
            onScan = {
                showQr = false
                scanQr()
            },
            onDismiss = { showQr = false }
        )
    }

    state.foundCode?.let { code ->
        FoundCodeDialog(
            code = code,
            contact = state.contacts.firstOrNull { it.code.equals(code, ignoreCase = true) },
            own = code == state.identity?.value,
            onCall = { contact ->
                viewModel.dismissFoundCode()
                onCall(contact)
            },
            onSave = viewModel::saveFoundCode,
            onDismiss = viewModel::dismissFoundCode
        )
    }

    if (showInvite) {
        InviteDialog(
            onShareLink = {
                showInvite = false
                shareAppLink(context)
            },
            onDismiss = { showInvite = false }
        )
    }

    if (showVendorSetup) {
        Vendor.current()?.let { vendor -> VendorSetupDialog(vendor, onDismiss = { showVendorSetup = false }) }
    }

    if (showConnectionCheck) {
        ConnectionCheckDialog(relayStatus, pathCheck, onDismiss = { showConnectionCheck = false })
    }

    if (showSettings) {
        SettingsDialog(
            relayStatus = relayStatus,
            onManualInvite = {
                showSettings = false
                viewModel.openIncomingDialog()
            },
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

/** Насколько приложение готово принять звонок. Порядок — от самого серьёзного препятствия. */
private enum class Readiness { OFFLINE, NO_PATH, NO_NOTIFICATIONS, BACKGROUND_OFF, BATTERY_RESTRICTED, VENDOR_SETUP, READY }

/**
 * Соединения с релеями мало: звонок не дойдёт, если Android усыпит приложение, и останется
 * незамеченным без уведомлений, а без прямого пути и TURN не соединится. Плашка зелёная,
 * только когда выполнено всё.
 */
private fun readinessOf(
    context: Context,
    online: Boolean,
    background: Boolean,
    path: PathCheck?,
    vendorSetupDone: Boolean
): Readiness = when {
    !online -> Readiness.OFFLINE
    path?.blocked == true -> Readiness.NO_PATH
    !CallNotifications.canPostNotifications(context) -> Readiness.NO_NOTIFICATIONS
    !background -> Readiness.BACKGROUND_OFF
    !isBatteryUnrestricted(context) -> Readiness.BATTERY_RESTRICTED
    // Оболочки Xiaomi, Huawei и др. закрывают приложение и без экономии батареи.
    !vendorSetupDone && Vendor.current() != null -> Readiness.VENDOR_SETUP
    else -> Readiness.READY
}

@Composable
private fun ReadinessBanner(
    readiness: Readiness,
    path: PathCheck?,
    tor: TorState?,
    restriction: Restriction,
    onClick: () -> Unit
) {
    val warning = if (isSystemInDarkTheme()) Color(0xFFFFC04D) else Color(0xFF8A5A00)
    val color = when (readiness) {
        Readiness.READY -> OnlineGreen
        // Tor ещё строит цепочку — это ожидание, а не поломка.
        Readiness.OFFLINE -> if (tor == TorState.STARTING) warning else MaterialTheme.colorScheme.error
        else -> warning
    }
    val text = when (readiness) {
        Readiness.READY -> stringResource(if (tor != null) R.string.online_banner_tor else R.string.online_banner)
        Readiness.OFFLINE -> when {
            tor == TorState.STARTING -> stringResource(R.string.tor_starting)
            tor == TorState.FAILED -> stringResource(R.string.tor_failed)
            restriction == Restriction.MOBILE_WHITELIST -> stringResource(R.string.restricted_mobile)
            restriction == Restriction.RELAYS_BLOCKED -> stringResource(R.string.restricted_relays)
            else -> stringResource(R.string.listen_offline)
        }
        Readiness.NO_PATH -> stringResource(R.string.ready_no_path)
        Readiness.NO_NOTIFICATIONS -> stringResource(R.string.ready_no_notifications)
        Readiness.BACKGROUND_OFF -> stringResource(R.string.ready_background_off)
        Readiness.BATTERY_RESTRICTED -> stringResource(R.string.ready_battery_restricted)
        Readiness.VENDOR_SETUP -> stringResource(R.string.ready_vendor_setup, Vendor.current()?.title.orEmpty())
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = color.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = color)
                // Готов, но без TURN: в сетях, где прямой путь закрыт, звонок может не соединиться.
                if (readiness == Readiness.READY && path != null && !path.turn) {
                    Text(
                        stringResource(R.string.ready_no_turn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (readiness != Readiness.READY && readiness != Readiness.OFFLINE) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.fix), Modifier.size(18.dp), tint = color)
            }
        }
    }
}

@Composable
private fun UpdateBanner(version: String, onDownload: () -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.update_available, version),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.update_later), Modifier.size(18.dp))
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // Браузера нет — адрес остаётся в описании выпуска и в руководстве.
    }
}

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Exception) {
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
private fun SettingsDialog(relayStatus: RelayStatus, onManualInvite: () -> Unit, onHelp: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings.get(context) }
    val carrierStatus by com.dpsmpx.dpcall.carrier.CarrierTransport.status.collectAsStateWithLifecycle()
    val roomActive = carrierStatus.phase != "off"
    val background by settings.backgroundListening.collectAsStateWithLifecycle()
    val contactsOnly by settings.contactsOnly.collectAsStateWithLifecycle()
    val missedNotices by settings.missedNotices.collectAsStateWithLifecycle()
    val updateCheck by settings.updateCheck.collectAsStateWithLifecycle()
    val hardBackground by settings.hardBackground.collectAsStateWithLifecycle()
    var confirmHard by remember { mutableStateOf(false) }
    var showVendorSetup by remember { mutableStateOf(false) }
    val vendor = remember { Vendor.current() }
    var checking by remember { mutableStateOf(false) }
    // Перечитывается при каждом открытии диалога и после возврата из системных настроек.
    val unrestricted = isBatteryUnrestricted(context)
    val upToDate = stringResource(R.string.update_none)
    val checkFailed = stringResource(R.string.update_check_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (roomActive) stringResource(R.string.carrier_title)
                    else if (relayStatus.online) stringResource(R.string.relays_connected, relayStatus.connected, relayStatus.total)
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
                if (background && !unrestricted) {
                    Text(
                        stringResource(R.string.battery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { requestBatteryExemption(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.battery_button))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (background && vendor != null) {
                    FilledTonalButton(onClick = { showVendorSetup = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.vendor_button, vendor.title))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (background) {
                    SettingRow(
                        title = stringResource(R.string.setting_hard_background),
                        description = stringResource(R.string.setting_hard_background_desc),
                        checked = hardBackground,
                        onChange = { enable ->
                            if (enable) {
                                confirmHard = true
                            } else {
                                settings.setHardBackground(false)
                                ListenService.sync(context)
                                RelaySignaling.restart(context)
                            }
                        }
                    )
                }
                SettingRow(
                    title = stringResource(R.string.setting_contacts_only),
                    description = stringResource(R.string.setting_contacts_only_desc),
                    checked = contactsOnly,
                    onChange = { settings.setContactsOnly(it) }
                )
                if (!roomActive) {
                    SettingRow(
                        title = stringResource(R.string.setting_missed_notices),
                        description = stringResource(R.string.setting_missed_notices_desc),
                        checked = missedNotices,
                        onChange = {
                            settings.setMissedNotices(it)
                            // Подписка на отметки задаётся при подключении к релеям.
                            RelaySignaling.restart(context)
                        }
                    )
                    SettingRow(
                        title = stringResource(R.string.setting_update_check),
                        description = stringResource(
                            R.string.setting_update_check_desc, UpdateChecker.installedVersion(context)
                        ),
                        checked = updateCheck,
                        onChange = { settings.setUpdateCheck(it) }
                    )
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            scope.launch {
                                val result = UpdateChecker.check(context)
                                checking = false
                                when (result) {
                                    // О найденной версии скажет плашка на главном экране.
                                    UpdateChecker.Result.UPDATE_AVAILABLE -> onDismiss()
                                    UpdateChecker.Result.UP_TO_DATE -> Toast.makeText(context, upToDate, Toast.LENGTH_SHORT).show()
                                    UpdateChecker.Result.FAILED -> Toast.makeText(context, checkFailed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(if (checking) R.string.update_checking else R.string.update_check_now)) }
                }
                AdvancedSettings(onManualInvite = onManualInvite)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onHelp) { Text(stringResource(R.string.how_to_use)) } }
    )

    if (confirmHard) {
        AlertDialog(
            onDismissRequest = { confirmHard = false },
            title = { Text(stringResource(R.string.setting_hard_background)) },
            text = { Text(stringResource(R.string.hard_background_warning)) },
            confirmButton = {
                Button(onClick = {
                    confirmHard = false
                    settings.setHardBackground(true)
                    ListenService.sync(context)
                    // Частота проверки соединения задаётся при подключении к релеям.
                    RelaySignaling.restart(context)
                }) { Text(stringResource(R.string.hard_background_enable)) }
            },
            dismissButton = { TextButton(onClick = { confirmHard = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showVendorSetup && vendor != null) {
        VendorSetupDialog(vendor, onDismiss = { showVendorSetup = false })
    }
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
private fun EmptyContacts(onAdd: () -> Unit, onInvite: () -> Unit) {
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
        TextButton(onClick = onInvite) {
            Text(stringResource(R.string.invite_empty))
        }
    }
}

/** Приглашение установить DP Call: прямая ссылка на APK и страница выпусков. */
private fun shareAppLink(context: Context) {
    shareText(
        context,
        context.getString(R.string.invite_text, UpdateChecker.DIRECT_DOWNLOAD, UpdateChecker.RELEASES_PAGE),
        context.getString(R.string.invite_title)
    )
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
    // Код вставлен или отсканирован целиком — остаётся только ввести имя.
    val nameFocus = remember { FocusRequester() }
    val codeComplete = CodeValidator.isValid(CodeValidator.normalize(codeInput))
    LaunchedEffect(codeComplete) {
        if (codeComplete) runCatching { nameFocus.requestFocus() }
    }
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
                    singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
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
