package com.dpcall.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpcall.app.domain.Contact
import com.dpcall.app.ui.call.CallScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    if (state.activeCall != null) {
        CallScreen(controller = state.activeCall!!, onClose = { viewModel.closeCall() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DP Call") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    TextButton(onClick = { viewModel.openIncomingDialog() }) {
                        Icon(Icons.Default.Call, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Входящий")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!state.isLoading) {
                FloatingActionButton(onClick = { viewModel.openAddDialog() }) {
                    Icon(Icons.Default.Add, "Добавить контакт")
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Column(Modifier.fillMaxSize().padding(padding), Arrangement.Center, Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Загрузка…")
                }
            }
            state.error != null -> {
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OwnCodeCard(
                            code = state.identity?.value ?: "",
                            copied = state.copied,
                            onCopy = { viewModel.copyCodeToClipboard() }
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.contacts.isEmpty()) "Контакты" else "Контакты (${state.contacts.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.contacts.isEmpty()) {
                        item {
                            Text(
                                "Пока пусто.\nНажмите + чтобы добавить контакт по коду.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                            )
                        }
                    } else {
                        items(state.contacts, key = { it.code }) { contact ->
                            ContactItem(
                                contact = contact,
                                onCall = { viewModel.startCall(contact) },
                                onDelete = { viewModel.removeContact(contact.code) }
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
            onOfferChange = viewModel::onIncomingOfferChange,
            onPaste = viewModel::pasteIncomingOffer,
            onAccept = viewModel::acceptIncomingOffer,
            onDismiss = viewModel::closeIncomingDialog
        )
    }
}

@Composable
private fun OwnCodeCard(code: String, copied: Boolean, onCopy: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ваш код", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                code,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (copied) "Скопировано" else "Скопировать")
            }
        }
    }
}

@Composable
private fun ContactItem(contact: Contact, onCall: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    contact.code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCall) {
                Icon(Icons.Default.Phone, "Позвонить", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
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
        title = { Text("Добавить контакт") },
        text = {
            Column {
                OutlinedTextField(
                    value = codeInput, onValueChange = onCodeChange,
                    label = { Text("Код") }, placeholder = { Text("Вставьте код собеседника") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Вставить") } }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameInput, onValueChange = onNameChange,
                    label = { Text("Имя") }, placeholder = { Text("Как отображать") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isAdding) {
                if (isAdding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun IncomingOfferDialog(
    offerInput: String, onOfferChange: (String) -> Unit,
    onPaste: () -> Unit, onAccept: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Входящий Offer") },
        text = {
            Column {
                Text("Вставьте Offer-строку, полученную от собеседника.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = offerInput, onValueChange = onOfferChange,
                    label = { Text("Offer") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    trailingIcon = { IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Вставить") } }
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = offerInput.isNotBlank()) { Text("Принять") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
