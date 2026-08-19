// SPDX-License-Identifier: AGPL-3.0-or-later
// Per-bike connection MECHANISM picker, shared by the Garage (durable home) and the Scan confirm step.
// Mirrors GarageScreen's ModeDialog/MapProviderDialog layout so the cockpit stays visually consistent.
// The rider pins how a bike connects (or leaves it Automatic); persisted via BikeMemory.setConnectorChoice.
package dev.zanderp.opencfmoto.ui.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.connection.factory.ConnectorChoice
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/** The rider-facing short label for a [ConnectorChoice] (the Garage tag / Scan affordance). */
@Composable
fun connectorShortLabel(choice: ConnectorChoice): String = when (choice) {
    ConnectorChoice.AUTO -> stringResource(R.string.ovk_conn_auto)
    ConnectorChoice.SOFT_AP -> stringResource(R.string.ovk_conn_softap)
    ConnectorChoice.P2P -> stringResource(R.string.ovk_conn_p2p)
    ConnectorChoice.RIEJU_BLE -> stringResource(R.string.ovk_conn_rieju)
    ConnectorChoice.TETHER -> stringResource(R.string.ovk_conn_tether)
}

/**
 * The auto-detected mechanism as a short, locale-independent token for the "detected: …" hint (e.g. "P2P").
 * Deliberately technical: it is a DETECTION RESULT shown as a hint, not one of the plain-language picker
 * options.
 */
fun detectedTransportToken(mode: TransportKind): String = when (mode) {
    TransportKind.SOFT_AP -> "SoftAP"
    TransportKind.P2P -> "P2P"
    TransportKind.PHONE_HOTSPOT -> "Hotspot"
}

/**
 * Per-bike connection-mechanism picker. [current] highlights today's choice; [detected] is
 * `ConnectionSpec.fromQr(qr).mode`, surfaced on the Automatic row so the rider sees what AUTO would use.
 */
@Composable
fun ConnectorChoiceDialog(
    bikeName: String,
    current: ConnectorChoice,
    detected: TransportKind,
    onPick: (ConnectorChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalCockpitColors.current
    val detectedToken = detectedTransportToken(detected)
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.ovk_dlg_conn_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.ovk_garage_conn_subtitle), color = c.inkDim, fontSize = 12.5.sp)
                Spacer(Modifier.size(4.dp))
                ConnRow(
                    stringResource(R.string.ovk_conn_opt_auto),
                    stringResource(R.string.ovk_conn_detected, detectedToken),
                    primary = current == ConnectorChoice.AUTO,
                ) { onPick(ConnectorChoice.AUTO) }
                ConnRow(
                    stringResource(R.string.ovk_conn_opt_softap),
                    stringResource(R.string.ovk_conn_softap_desc),
                    primary = current == ConnectorChoice.SOFT_AP,
                ) { onPick(ConnectorChoice.SOFT_AP) }
                ConnRow(
                    stringResource(R.string.ovk_conn_opt_p2p),
                    stringResource(R.string.ovk_conn_p2p_desc),
                    primary = current == ConnectorChoice.P2P,
                ) { onPick(ConnectorChoice.P2P) }
                ConnRow(
                    stringResource(R.string.ovk_conn_opt_rieju),
                    stringResource(R.string.ovk_conn_rieju_desc),
                    primary = current == ConnectorChoice.RIEJU_BLE,
                ) { onPick(ConnectorChoice.RIEJU_BLE) }
                ConnRow(
                    stringResource(R.string.ovk_conn_tether),
                    stringResource(R.string.ovk_conn_tether_desc),
                    primary = current == ConnectorChoice.TETHER,
                ) { onPick(ConnectorChoice.TETHER) }
            }
        }
    }
}

/** A single option row (title + subtitle + chevron), highlighted when it is the current choice. */
@Composable
private fun ConnRow(title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val bd = if (primary) c.ignition.copy(alpha = 0.55f) else c.line
    val bg = if (primary) c.ignition.copy(alpha = 0.12f) else c.groundHi
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, color = c.inkDim, fontSize = 11.sp)
        }
        Text("›", color = c.inkFaint, fontSize = 18.sp)
    }
}
