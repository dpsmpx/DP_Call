package com.dpsmpx.dpcall.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpcall.R
import com.dpsmpx.dpcall.data.AppSettings
import com.dpsmpx.dpcall.util.Vendor
import com.dpsmpx.dpcall.util.VendorSettings

/**
 * Что разрешить в оболочке производителя, чтобы звонки доходили при закрытом
 * приложении. Сама оболочка не сообщает, сделано ли это, — поэтому «Готово»
 * нажимает пользователь.
 */
@Composable
fun VendorSetupDialog(vendor: Vendor, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vendor_title, vendor.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.vendor_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(vendor.steps), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.vendor_last_resort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = { VendorSettings.open(context, vendor) }) {
                    Text(stringResource(R.string.vendor_open_settings))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.setVendorSetupDone()
                onDismiss()
            }) { Text(stringResource(R.string.vendor_done)) }
        },
        dismissButton = { Row { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vendor_later)) } } }
    )
}
