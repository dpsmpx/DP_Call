package com.dpsmpx.dpcall.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpcall.R
import com.dpsmpx.dpcall.data.BackupCodec
import com.dpsmpx.dpcall.data.BackupFormatException
import com.dpsmpx.dpcall.data.BackupManager
import com.dpsmpx.dpcall.data.BackupPasswordException
import kotlinx.coroutines.launch

/**
 * Резервная копия кода и контактов: сохранение в файл под паролем и восстановление.
 * Единственный способ не потерять код при смене телефона или переустановке.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var askExportPassword by remember { mutableStateOf(false) }
    // Пароль ждёт, пока пользователь выберет, куда сохранить файл.
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var restoreFile by remember { mutableStateOf<ByteArray?>(null) }

    val saved = stringResource(R.string.backup_saved)
    val saveFailed = stringResource(R.string.backup_save_failed)
    val notBackup = stringResource(R.string.backup_not_a_backup)

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val password = exportPassword
        exportPassword = null
        if (uri != null && password != null) {
            scope.launch {
                val ok = runCatching { BackupManager.export(context, uri, password.toCharArray()) }.isSuccess
                Toast.makeText(context, if (ok) saved else saveFailed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching { BackupManager.read(context, uri) }
                    .onSuccess { restoreFile = it }
                    .onFailure { Toast.makeText(context, notBackup, Toast.LENGTH_LONG).show() }
            }
        }
    }

    Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.backup_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { askExportPassword = true }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.backup_save), maxLines = 1)
        }
        TextButton(onClick = { openFile.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.backup_restore), maxLines = 1)
        }
    }

    if (askExportPassword) {
        ExportPasswordDialog(
            onConfirm = { password ->
                askExportPassword = false
                exportPassword = password
                createFile.launch(BackupCodec.FILE_NAME)
            },
            onDismiss = { askExportPassword = false }
        )
    }
    restoreFile?.let { file ->
        RestoreDialog(file = file, onDone = { restoreFile = null })
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ExportPasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < BackupCodec.MIN_PASSWORD_LENGTH
    val mismatch = repeat.isNotEmpty() && repeat != password
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_save_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.backup_save_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    password, { password = it }, stringResource(R.string.password),
                    error = if (tooShort) stringResource(R.string.backup_password_short, BackupCodec.MIN_PASSWORD_LENGTH) else null
                )
                PasswordField(
                    repeat, { repeat = it }, stringResource(R.string.backup_password_repeat),
                    error = if (mismatch) stringResource(R.string.backup_password_mismatch) else null
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.length >= BackupCodec.MIN_PASSWORD_LENGTH && repeat == password
            ) { Text(stringResource(R.string.backup_choose_file)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun RestoreDialog(file: ByteArray, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val wrongPassword = stringResource(R.string.backup_wrong_password)
    val notBackup = stringResource(R.string.backup_not_a_backup)
    val restoreFailed = stringResource(R.string.backup_restore_failed)
    val restored = stringResource(R.string.backup_restored)

    AlertDialog(
        // Пока идёт расшифровка и замена ключа, диалог не закрывается.
        onDismissRequest = { if (!working) onDone() },
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.backup_restore_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                PasswordField(password, { password = it; error = null }, stringResource(R.string.password), error)
            }
        },
        confirmButton = {
            Button(
                enabled = !working && password.isNotEmpty(),
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            val content = BackupManager.decrypt(file, password.toCharArray())
                            BackupManager.restore(context, content)
                            Toast.makeText(context, restored, Toast.LENGTH_LONG).show()
                            onDone()
                        } catch (e: BackupPasswordException) {
                            error = wrongPassword
                        } catch (e: BackupFormatException) {
                            error = notBackup
                        } catch (e: Exception) {
                            error = restoreFailed
                        } finally {
                            working = false
                        }
                    }
                }
            ) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.backup_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDone, enabled = !working) { Text(stringResource(R.string.cancel)) }
        }
    )
}
