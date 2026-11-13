package com.dpsmpx.dpcall.carrier

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpcall.R
import com.dpsmpx.dpcall.call.CallManager
import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.util.CodeValidator
import com.dpsmpx.dpcall.webrtc.CallState

@Composable
fun CarrierPanel(onCall: (String) -> Unit) {
    val context = LocalContext.current
    val status by CarrierTransport.status.collectAsStateWithLifecycle()
    val call by CallManager.uiState.collectAsStateWithLifecycle()
    var room by remember { mutableStateOf("") }
    var peer by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    val busy = call.state.isActive
    val normalizedPeer = CodeValidator.extract(peer) ?: CodeValidator.normalize(peer)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.carrier_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.carrier_disclosure), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(when (status.phase) {
                "off" -> R.string.carrier_off
                "failed" -> R.string.carrier_failed
                "peer_ready" -> R.string.carrier_ready
                "captcha" -> R.string.carrier_captcha
                "room_connected", "waiting_peer" -> R.string.carrier_waiting
                else -> R.string.carrier_connecting
            }))
            if (status.phase == "off" || status.phase == "failed") {
                OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text(stringResource(R.string.carrier_room)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                OutlinedTextField(value = peer, onValueChange = { peer = it }, label = { Text(stringResource(R.string.carrier_peer)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
                Row {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !busy)
                    Text(stringResource(R.string.carrier_consent), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { CarrierTransport.connect(context, room, normalizedPeer) },
                    enabled = !busy && accepted && CarrierRoom.parse(room) != null && IdentityGenerator.publicKeyFromCode(normalizedPeer) != null) {
                    Text(stringResource(R.string.carrier_connect))
                }
            } else {
                if (status.captchaUrl != null) TextButton(onClick = {
                    context.startActivity(Intent().setClassName(context, "com.dpsmpx.dpcall.carrier.CarrierCaptchaActivity")
                        .putExtra("url", status.captchaUrl))
                }) { Text(stringResource(R.string.carrier_captcha)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCall(status.peerCode) }, enabled = !busy && CarrierTransport.ready) {
                        Text(stringResource(R.string.call))
                    }
                    TextButton(onClick = { CarrierTransport.disconnect() }, enabled = !busy) { Text(stringResource(R.string.carrier_disconnect)) }
                }
            }
        }
    }
}
