package com.dpsmpx.dpcall.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpcall.R
import com.dpsmpx.dpcall.call.CallManager
import com.dpsmpx.dpcall.call.RelaySignaling
import com.dpsmpx.dpcall.call.RelayStatus
import com.dpsmpx.dpcall.webrtc.CallState
import com.dpsmpx.dpcall.webrtc.PathCheck
import com.dpsmpx.dpcall.webrtc.PathProbe

/** Подробности плашки готовности: релеи, прямой путь, TURN — и что с этим делать. */
@Composable
fun ConnectionCheckDialog(relayStatus: RelayStatus, path: PathCheck?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val running by PathProbe.running.collectAsStateWithLifecycle()
    val spares = remember(relayStatus) { RelaySignaling.relayStates().count { it.spare && it.active } }
    val good = Color(0xFF2E9E5B)
    val bad = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CheckRow(
                    stringResource(R.string.check_relays),
                    stringResource(R.string.check_relays_value, relayStatus.connected, relayStatus.total) +
                        if (spares > 0) stringResource(R.string.check_relays_spare) else "",
                    if (relayStatus.online) good else bad
                )
                if (path == null || running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.check_running), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    CheckRow(
                        stringResource(R.string.check_direct),
                        stringResource(if (path.direct) R.string.check_direct_yes else R.string.check_direct_no),
                        if (path.direct) good else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CheckRow(
                        stringResource(R.string.check_turn),
                        stringResource(if (path.turn) R.string.check_turn_yes else R.string.check_turn_no),
                        if (path.turn) good else bad
                    )
                    path.ownTurn?.let { works ->
                        CheckRow(
                            stringResource(R.string.check_own_turn),
                            stringResource(if (works) R.string.check_own_turn_yes else R.string.check_own_turn_no),
                            if (works) good else bad
                        )
                    }
                    val advice = when {
                        !relayStatus.online -> R.string.check_advice_offline
                        path.blocked -> R.string.check_advice_blocked
                        !path.turn -> R.string.check_advice_no_turn
                        else -> R.string.check_advice_ok
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(advice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(
                            R.string.check_time,
                            DateUtils.getRelativeTimeSpanString(path.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            val busy = CallManager.uiState.value.state != CallState.IDLE
            TextButton(onClick = { PathProbe.run(context, busy) }, enabled = !running && !busy) {
                Text(stringResource(R.string.check_again))
            }
        }
    )
}

@Composable
private fun CheckRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}
