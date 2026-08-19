// SPDX-License-Identifier: AGPL-3.0-or-later
// Garage — each paired bike with its mode, its default map, and status. Tapping a bike sets its
// projection mode (CFMOTO / Android Auto); its map tag sets the per-bike default map provider
// (config-ownership design doc §2: the Garage is the one owner — the Map screen's live selector only
// seeds from it, never the other way around). Pairing a new bike triggers the mode choice.
package dev.zanderp.opencfmoto.ui.garage

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.NearbyDevices
import dev.zanderp.opencfmoto.QrScanActivity
import dev.zanderp.opencfmoto.SavedBike
import dev.zanderp.opencfmoto.connection.factory.ConnectorChoice
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.usesWifiDirect
import dev.zanderp.opencfmoto.settings.MapProvider
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.connection.ConnectorChoiceDialog
import dev.zanderp.opencfmoto.ui.connection.connectorRowLabel
import dev.zanderp.opencfmoto.ui.settings.Header
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

@Composable
fun GarageScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    // Keyed by refresh: unlike the per-bike tags below (whose VALUES change), removing a bike changes
    // the LIST itself — the row must disappear from THIS composition, not just on next navigation.
    val bikes = remember(refresh) { BikeMemory.devices(ctx) }
    val selected = remember(refresh) { BikeMemory.lastRaw(ctx) }
    var modeFor by remember { mutableStateOf<SavedBike?>(null) }
    var providerFor by remember { mutableStateOf<SavedBike?>(null) }
    var connectorFor by remember { mutableStateOf<SavedBike?>(null) }
    var removeFor by remember { mutableStateOf<SavedBike?>(null) }
    // The Android 13+ nearby-devices grant, asked when a rider PINS a Wi-Fi Direct connector here (Scan asks
    // at pairing). Declared at composable scope because a launcher must be; it only fires from that pick.
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        LogBus.log("[garage] nearby-devices permission ${if (ok) "granted" else "denied"}")
    }

    Column(
        Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header(stringResource(R.string.ovk_garage)) { nav.popBackStack() }
        Spacer(Modifier.size(2.dp))

        if (bikes.isEmpty()) {
            MonoLabel(stringResource(R.string.ovk_garage_empty), color = c.inkFaint)
        } else {
            bikes.forEach { bike ->
                BikeRow(
                    bike, current = bike.raw == selected, refreshKey = refresh,
                    onModeClick = { modeFor = bike },
                    onProviderClick = { providerFor = bike },
                    onConnectorClick = { connectorFor = bike },
                    onRemoveClick = { removeFor = bike },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                .border(1.5.dp, c.line, RoundedCornerShape(13.dp))
                .clickable { nav.navigate("scan") }
                .padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("＋  " + stringResource(R.string.ovk_garage_pair_another), color = c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        MonoLabel(stringResource(R.string.ovk_garage_tap_hint), color = c.inkFaint)
    }

    modeFor?.let { bike ->
        val ssid = bike.qr?.ssid ?: ""
        ModeDialog(
            bikeName = bike.name,
            onPick = { mode ->
                BikeMemory.setBikeMode(ctx, ssid, mode)
                refresh++
                modeFor = null
            },
            onDismiss = { modeFor = null },
        )
    }

    providerFor?.let { bike ->
        val current = remember(bike.raw, refresh) { bike.qr?.let { BikeMemory.specFor(ctx, it)?.defaultMapProvider } }
        MapProviderDialog(
            bikeName = bike.name,
            current = current,
            onPick = { picked ->
                // A bike whose raw QR no longer parses (corrupted/legacy entry) has no bikeId to key a
                // spec by — mirrors ModeDialog's own blank-ssid no-op above (BikeMemory.setBikeMode).
                bike.qr?.let { qr ->
                    // A bike with no spec yet gets the SAME auto-detected connector `save` would have
                    // seeded — a map-provider pick must never persist a different (bare-QR) connector.
                    val spec = BikeMemory.specFor(ctx, qr) ?: BikeMemory.autoDetectedSpec(ctx, qr)
                    BikeMemory.saveSpec(ctx, spec.copy(defaultMapProvider = picked))
                }
                refresh++
                providerFor = null
            },
            onDismiss = { providerFor = null },
        )
    }

    connectorFor?.let { bike ->
        val qr = bike.qr
        val current = remember(bike.raw, refresh) {
            qr?.let { BikeMemory.connectorChoice(ctx, it) } ?: ConnectorChoice.AUTO
        }
        // The detected hint needs a mode; a corrupt/legacy entry with no parseable QR has none — fall back
        // to SOFT_AP purely for the hint (onPick is a no-op for it, like the map/mode dialogs above).
        // `autoDetectedMode` is the connector AUTO really resolves to (QR + Setup preference + this bike's
        // learned winner); the bare `fromQr` guess used to be shown here, which made the hint lie about
        // DIRECT-* bikes whose P2P never forms on this phone.
        val detected = remember(bike.raw, refresh) {
            qr?.let { BikeMemory.autoDetectedMode(ctx, it) } ?: TransportKind.SOFT_AP
        }
        ConnectorChoiceDialog(
            bikeName = bike.name,
            current = current,
            detected = detected,
            onPick = { picked ->
                qr?.let {
                    BikeMemory.setConnectorChoice(ctx, it, picked)
                    // Pinning BLE or P2P here is the other moment a bike acquires a Wi-Fi Direct connector
                    // (Scan is the first). Ask for the Android 13+ nearby-devices grant NOW — deliberate,
                    // with the rider in a settings dialog — so the next Connect can work; without it every
                    // Wi-Fi Direct call is rejected instantly with the framework's generic ERROR.
                    if (usesWifiDirect(BikeMemory.effectiveMode(ctx, it)) && !NearbyDevices.granted(ctx)) {
                        LogBus.log("[garage] '$picked' needs Wi-Fi Direct — asking for the nearby-devices permission")
                        NearbyDevices.markAsked(ctx)
                        nearbyLauncher.launch(NearbyDevices.PERMISSION)
                    }
                }
                refresh++
                connectorFor = null
            },
            onDismiss = { connectorFor = null },
        )
    }

    removeFor?.let { bike ->
        RemoveBikeDialog(
            bikeName = bike.name,
            onConfirm = {
                BikeMemory.remove(ctx, bike.raw)
                refresh++
                removeFor = null
            },
            onDismiss = { removeFor = null },
        )
    }
}

@Composable
private fun BikeRow(
    bike: SavedBike,
    current: Boolean,
    refreshKey: Int,
    onModeClick: () -> Unit,
    onProviderClick: () -> Unit,
    onConnectorClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val ssid = remember(bike.raw) { bike.qr?.ssid ?: "" }
    val mode = remember(bike.raw, refreshKey) { BikeMemory.bikeMode(ctx, ssid) }
    val provider = remember(bike.raw, refreshKey) { bike.qr?.let { BikeMemory.specFor(ctx, it)?.defaultMapProvider } }
    val connector = remember(bike.raw, refreshKey) {
        bike.qr?.let { BikeMemory.connectorChoice(ctx, it) } ?: ConnectorChoice.AUTO
    }
    // Same AUTO-fallback reasoning as the connectorFor dialog below: a corrupt/legacy entry with no
    // parseable QR has no detected mode — SOFT_AP is purely a display fallback, never persisted from here.
    // What AUTO would really use, not the bare QR guess — see that dialog.
    val detected = remember(bike.raw, refreshKey) {
        bike.qr?.let { BikeMemory.autoDetectedMode(ctx, it) } ?: TransportKind.SOFT_AP
    }
    val borderColor = if (current) c.ignition.copy(alpha = 0.45f) else c.line
    Row(
        // The row itself still opens the mode picker (unchanged tap target/hint); the map tag below is
        // its own smaller, nested tap target for the (separate-owner) map-provider default.
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(c.surface1).border(1.dp, borderColor, RoundedCornerShape(13.dp)).clickable(onClick = onModeClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.ground).border(1.dp, c.line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("🏍", fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(bike.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                (if (ssid.isNotBlank()) "$ssid · " else "") + if (current) stringResource(R.string.ovk_garage_connected_recent) else stringResource(R.string.ovk_garage_saved),
                color = c.inkFaint, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (mode) {
                "ANDROID_AUTO" -> ModeTag("AUTO", aa = true)
                "CFMOTO" -> ModeTag("CFMOTO", aa = false)
                else -> ModeTag(stringResource(R.string.ovk_garage_no_mode), aa = true)
            }
            ProviderTag(
                text = provider?.let { mapProviderLabel(ctx, it) } ?: stringResource(R.string.ovk_garage_no_map),
                onClick = onProviderClick,
            )
            ConnectorTag(
                text = connectorRowLabel(connector, detected),
                pinned = connector != ConnectorChoice.AUTO,
                onClick = onConnectorClick,
            )
            RemoveTag(onClick = onRemoveClick)
        }
    }
}

@Composable
private fun ModeTag(text: String, aa: Boolean) {
    val c = LocalCockpitColors.current
    val fg = if (aa) c.inkFaint else c.ignition
    val bg = if (aa) c.ground else c.ignition.copy(alpha = 0.12f)
    val bd = if (aa) c.line else c.ignition.copy(alpha = 0.35f)
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Text(text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
}

/** The per-bike default map provider (config-ownership design doc §2) — its own small tap target. */
@Composable
private fun ProviderTag(text: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.ground).border(1.dp, c.line, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Text(text, color = c.inkFaint, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
}

/** The per-bike connection MECHANISM (own small tap target). Accented when the rider has PINNED a
 *  non-Automatic choice, so an override reads at a glance; faint when left on Automatic. */
@Composable
private fun ConnectorTag(text: String, pinned: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val fg = if (pinned) c.ignition else c.inkFaint
    val bg = if (pinned) c.ignition.copy(alpha = 0.12f) else c.ground
    val bd = if (pinned) c.ignition.copy(alpha = 0.35f) else c.line
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Text(text, color = fg, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Destructive per-bike action — forgets this bike and every setting tied to it (its own small tap
 *  target, same shape/size as the other tags, but in the fault color so it reads as destructive rather
 *  than another piece of state to tap through). Opens [RemoveBikeDialog]; never removes directly. */
@Composable
private fun RemoveTag(onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.ground).border(1.dp, c.fault.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Text(stringResource(R.string.ovk_garage_remove), color = c.fault, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
}

// Brand names stay literal; only the MIRROR ("Espejo") label is translated — mirrors the labeling this
// helper replaces in ui/settings/SettingsScreen.kt's now-removed global provider row.
private fun mapProviderLabel(ctx: Context, p: MapProvider) = when (p) {
    MapProvider.BUILTIN -> "Overtake"; MapProvider.GOOGLE -> "Google Maps"; MapProvider.WAZE -> "Waze"
    MapProvider.MIRROR -> ctx.getString(R.string.ovk_provider_mirror)
}

@Composable
private fun ModeDialog(bikeName: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.ovk_dlg_mode_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.ovk_garage_mode_subtitle), color = c.inkDim, fontSize = 12.5.sp)
                Spacer(Modifier.size(4.dp))
                ChoiceRow("CFMOTO", stringResource(R.string.ovk_dlg_mode_cfmoto_desc), primary = true) { onPick("CFMOTO") }
                ChoiceRow("Android Auto", stringResource(R.string.ovk_dlg_mode_aa_desc), primary = false) { onPick("ANDROID_AUTO") }
            }
        }
    }
}

/**
 * Confirm-before-destroy for [BikeMemory.remove]: names the bike and says plainly what is lost, so a
 * rider never removes a bike by a stray tap on [RemoveTag]. Mirrors [ModeDialog]'s shell (same frame,
 * same title/body styling) with a Cancelar/Quitar action row instead of [ChoiceRow]s, since this is a
 * yes/no confirm, not a pick-one-of-N choice.
 */
@Composable
private fun RemoveBikeDialog(bikeName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.ovk_dlg_remove_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.ovk_dlg_remove_body), color = c.inkDim, fontSize = 12.5.sp)
                Spacer(Modifier.size(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        stringResource(R.string.ovk_cancel), color = c.inkDim, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.ovk_garage_remove), color = c.fault, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.fault.copy(alpha = 0.12f))
                            .border(1.dp, c.fault.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .clickable(onClick = onConfirm).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Per-bike default map provider picker (config-ownership design doc §2: the Garage is the ONE owner of
 * this setting — removed from global Settings in the same task). [current] highlights today's default,
 * if any, mirroring [ModeDialog]'s layout so the Garage stays visually consistent.
 */
@Composable
private fun MapProviderDialog(
    bikeName: String,
    current: MapProvider?,
    onPick: (MapProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalCockpitColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = c.surface1, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, c.line)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.ovk_dlg_map_title, bikeName), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.ovk_garage_map_subtitle), color = c.inkDim, fontSize = 12.5.sp)
                Spacer(Modifier.size(4.dp))
                ChoiceRow("Overtake", stringResource(R.string.ovk_map_sub_builtin), primary = current == MapProvider.BUILTIN) { onPick(MapProvider.BUILTIN) }
                ChoiceRow("Google Maps", stringResource(R.string.ovk_map_sub_google), primary = current == MapProvider.GOOGLE) { onPick(MapProvider.GOOGLE) }
                ChoiceRow("Waze", stringResource(R.string.ovk_map_sub_waze), primary = current == MapProvider.WAZE) { onPick(MapProvider.WAZE) }
                ChoiceRow(stringResource(R.string.ovk_provider_mirror), stringResource(R.string.ovk_map_sub_mirror), primary = current == MapProvider.MIRROR) { onPick(MapProvider.MIRROR) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, primary: Boolean, onClick: () -> Unit) {
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
