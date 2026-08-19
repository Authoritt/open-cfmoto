// SPDX-License-Identifier: AGPL-3.0-or-later
// Per-bike connection MECHANISM picker, shared by the Garage (durable home) and the Scan step (where the
// choice gets PROVEN). Every option is named by mechanism — SoftAP / P2P / BLE / Hotspot — never by brand.
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

/**
 * The rider-facing name of a [ConnectorChoice] — the MECHANISM, never a brand ("SoftAP", not "CFMoto
 * Wi-Fi"). Used by the Garage tag, the Scan chips and the failure message the factory injects, so the
 * connector the rider picks, the one the app blames and the one the "?" sheet documents are ONE name.
 */
@Composable
fun connectorShortLabel(choice: ConnectorChoice): String = when (choice) {
    ConnectorChoice.AUTO -> stringResource(R.string.ovk_conn_auto)
    ConnectorChoice.SOFT_AP -> stringResource(R.string.ovk_conn_softap)
    ConnectorChoice.P2P -> stringResource(R.string.ovk_conn_p2p)
    ConnectorChoice.BLE -> stringResource(R.string.ovk_conn_ble)
    ConnectorChoice.HOTSPOT -> stringResource(R.string.ovk_conn_hotspot)
}

/** The one-line "what it is" for a [ConnectorChoice] — plain language, no jargon, no brand. */
@Composable
fun connectorDescription(choice: ConnectorChoice, detected: TransportKind): String = when (choice) {
    ConnectorChoice.AUTO -> stringResource(R.string.ovk_conn_auto_desc, connectorNameForTransport(detected))
    ConnectorChoice.SOFT_AP -> stringResource(R.string.ovk_conn_softap_desc)
    ConnectorChoice.P2P -> stringResource(R.string.ovk_conn_p2p_desc)
    ConnectorChoice.BLE -> stringResource(R.string.ovk_conn_ble_desc)
    ConnectorChoice.HOTSPOT -> stringResource(R.string.ovk_conn_hotspot_desc)
}

/**
 * The rider-facing connector NAME for a DETECTED [TransportKind] — the same mechanism names
 * [connectorShortLabel] uses for a pinned [ConnectorChoice], so an Automatic row can say which connector it
 * actually picked instead of leaving the rider to guess.
 *
 * Note the deliberate crossing: [TransportKind.PHONE_HOTSPOT] is the BLE mechanism (the phone hosts the
 * network and passes the password over Bluetooth) and [TransportKind.TETHER] is the Hotspot mechanism (the
 * rider switches the phone hotspot on). [TransportKind] is internal plumbing and keeps its own names;
 * [ConnectorChoice.forTransport] is the single place the two vocabularies meet.
 */
@Composable
fun connectorNameForTransport(mode: TransportKind): String =
    connectorShortLabel(ConnectorChoice.forTransport(mode))

/**
 * The rider-facing connector row/tag text (Scan's pill, Garage's per-bike tag). For [ConnectorChoice.AUTO]
 * this names the connector that will actually be used — e.g. "Automático · P2P" — because "Automático" alone
 * tells the rider nothing when a connection fails. A pinned choice still shows just its own name, unchanged.
 */
@Composable
fun connectorRowLabel(choice: ConnectorChoice, detected: TransportKind): String =
    if (choice == ConnectorChoice.AUTO) {
        stringResource(R.string.ovk_conn_auto_detail, connectorNameForTransport(detected))
    } else {
        connectorShortLabel(choice)
    }

/**
 * The connectors as the rider sees them, in picker order: the default first, then the four mechanisms.
 * ONE list, so the Garage dialog and the Scan chips can never drift apart or hide a mechanism from one of
 * the two places a rider looks for it.
 */
val CONNECTOR_OPTIONS: List<ConnectorChoice> = listOf(
    ConnectorChoice.AUTO,
    ConnectorChoice.SOFT_AP,
    ConnectorChoice.P2P,
    ConnectorChoice.BLE,
    ConnectorChoice.HOTSPOT,
)

/**
 * Per-bike connection-mechanism picker. [current] highlights today's choice; [detected] is
 * `BikeMemory.autoDetectedMode(qr)` — the connector AUTO really resolves to — surfaced on the
 * Automatic row so the rider sees what AUTO would use. The rows come from [CONNECTOR_OPTIONS], so every
 * mechanism is always offered, in one order, with one name.
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
                for (option in CONNECTOR_OPTIONS) {
                    ConnRow(
                        connectorShortLabel(option),
                        connectorDescription(option, detected),
                        primary = current == option,
                    ) { onPick(option) }
                }
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
 * Small "?" affordance that opens [ConnectorHelpDialog] (what each mechanism is + which bikes it is proven
 * on). Shared by the Scan row and [ConnectorChoiceDialog]'s header, so one implementation covers both
 * places a rider might wonder "what does this mean?".
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
 * "How does my bike connect?" sheet: the four mechanisms, each with a plain-language explanation and the
 * brands/models it has been confirmed on. UI/copy only — no connection logic lives here. The BLE row is
 * marked unproven ON PURPOSE (deliberate honesty, not a bug): only flip its "tested on" line once a bike has
 * actually confirmed it.
 */
@Composable
fun ConnectorHelpDialog(onDismiss: () -> Unit) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ovk_conn_help_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_softap),
                        stringResource(R.string.ovk_conn_help_softap_desc),
                        stringResource(R.string.ovk_conn_help_softap_tested),
                    )
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_p2p),
                        stringResource(R.string.ovk_conn_help_p2p_desc),
                        stringResource(R.string.ovk_conn_help_p2p_tested),
                    )
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_ble),
                        stringResource(R.string.ovk_conn_help_ble_desc),
                        stringResource(R.string.ovk_conn_help_ble_tested),
                        warnTested = true,
                        note = stringResource(R.string.ovk_conn_help_ble_try),
                    )
                    ConnectorHelpRow(
                        stringResource(R.string.ovk_conn_hotspot),
                        stringResource(R.string.ovk_conn_help_hotspot_desc),
                        stringResource(R.string.ovk_conn_help_hotspot_tested),
                    )
                }
                Text(stringResource(R.string.ovk_conn_help_footer), color = c.inkDim, fontSize = 11.5.sp)
            }
        }
    }
}

/**
 * One connector's plain-language row in [ConnectorHelpDialog]: what it does + WHICH BRANDS AND MODELS it is
 * confirmed on. The brands live here and nowhere else — the picker names the mechanism, this sheet carries
 * the evidence — so a rider can answer "will this one work on MY bike?" without the mechanism itself
 * pretending to belong to a marque.
 *
 * [warnTested] renders the "tested on" line in the theme's warning accent (never a raw red/[c.fault]) — used
 * for BLE's honest "not yet tested on a bike" line, which is deliberate, not an error state. [note] is an
 * optional extra line: today it invites the rider to TRY BLE on a non-Rieju Carbit dash, because the
 * credential push it uses belongs to the dash software, not to the badge on the tank — the app can't test
 * that from here, but a rider with the bike in front of them can.
 */
@Composable
private fun ConnectorHelpRow(
    title: String,
    desc: String,
    tested: String,
    warnTested: Boolean = false,
    note: String? = null,
) {
    val c = LocalCockpitColors.current
    Column {
        Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(desc, color = c.inkDim, fontSize = 11.5.sp)
        Text(tested, color = if (warnTested) c.warn else c.inkFaint, fontSize = 11.sp)
        if (note != null) {
            Spacer(Modifier.size(3.dp))
            Text(note, color = c.inkDim, fontSize = 11.sp)
        }
    }
}
