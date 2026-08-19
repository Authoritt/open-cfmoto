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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    TransportKind.TETHER -> "Tether"
}

/**
 * The rider-facing connector NAME for a detected [TransportKind] — the same plain-language names
 * [connectorShortLabel] uses for a pinned [ConnectorChoice], so an Automatic row can say which
 * connector it actually picked instead of leaving the rider to guess.
 */
@Composable
fun connectorNameForTransport(mode: TransportKind): String = when (mode) {
    TransportKind.SOFT_AP -> stringResource(R.string.ovk_conn_softap)
    TransportKind.P2P -> stringResource(R.string.ovk_conn_p2p)
    TransportKind.PHONE_HOTSPOT -> stringResource(R.string.ovk_conn_rieju)
    TransportKind.TETHER -> stringResource(R.string.ovk_conn_tether)
}

/**
 * The rider-facing connector row/tag text (Scan's confirm pill, Garage's per-bike tag). For
 * [ConnectorChoice.AUTO] this names the connector that will actually be used — e.g.
 * "Automatic · CFMoto Direct (P2P)" — because "Automatic" alone tells the rider nothing when a
 * connection fails. A pinned choice still shows just its own name, unchanged.
 */
@Composable
fun connectorRowLabel(choice: ConnectorChoice, detected: TransportKind): String =
    if (choice == ConnectorChoice.AUTO) {
        stringResource(R.string.ovk_conn_auto_detail, connectorNameForTransport(detected), detectedTransportToken(detected))
    } else {
        connectorShortLabel(choice)
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
    var showHelp by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ovk_dlg_conn_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(stringResource(R.string.ovk_garage_conn_subtitle), color = c.inkDim, fontSize = 12.5.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    ConnectorHelpButton(onClick = { showHelp = true })
                }
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
    if (showHelp) {
        ConnectorHelpDialog(onDismiss = { showHelp = false })
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

/**
 * Small "?" affordance that opens [ConnectorHelpDialog] (a plain-language explanation of the four
 * connectors). Shared by the Scan confirm row and [ConnectorChoiceDialog]'s header, so one
 * implementation covers both places a rider might wonder "what does this mean?".
 */
@Composable
fun ConnectorHelpButton(onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(999.dp)).background(c.groundHi).border(1.dp, c.line, RoundedCornerShape(999.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("?", color = c.inkDim, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

/**
 * "How does my bike connect?" sheet: the four connectors, each with a plain-language explanation and
 * which bikes it has been confirmed on. UI/copy only — no connection logic lives here. The Rieju row
 * is marked unproven ON PURPOSE (deliberate honesty, not a bug): only flip its "tested on" line once a
 * bike has actually confirmed it.
 */
@Composable
fun ConnectorHelpDialog(onDismiss: () -> Unit) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ovk_conn_help_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_opt_softap),
                        stringResource(R.string.ovk_conn_help_softap_desc),
                        stringResource(R.string.ovk_conn_help_softap_tested),
                    )
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_opt_p2p),
                        stringResource(R.string.ovk_conn_help_p2p_desc),
                        stringResource(R.string.ovk_conn_help_p2p_tested),
                    )
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_help_rieju_title),
                        stringResource(R.string.ovk_conn_help_rieju_desc),
                        stringResource(R.string.ovk_conn_help_rieju_tested),
                        warnTested = true,
                    )
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_tether),
                        stringResource(R.string.ovk_conn_help_tether_desc),
                        stringResource(R.string.ovk_conn_help_tether_tested),
                    )
                }
                Text(stringResource(R.string.ovk_conn_help_footer), color = c.inkDim, fontSize = 11.5.sp)
            }
        }
    }
}

/**
 * One connector's plain-language row in [ConnectorHelpDialog]: what it does + which bikes confirmed
 * it. [warnTested] renders the "tested on" line in the theme's warning accent (never a raw red/[c.fault])
 * — used for Rieju's honest "not yet tested on a bike" line, which is deliberate, not an error state.
 */
@Composable
private fun ConnectorHelpRow(title: String, desc: String, tested: String, warnTested: Boolean = false) {
    val c = LocalCockpitColors.current
    Column {
        Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(desc, color = c.inkDim, fontSize = 11.5.sp)
        Text(tested, color = if (warnTested) c.warn else c.inkFaint, fontSize = 11.sp)
    }
}
