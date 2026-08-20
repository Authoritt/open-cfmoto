// SPDX-License-Identifier: AGPL-3.0-or-later
// Scan — CameraX + ML Kit QR scanner as a Compose screen, styled to the cockpit mockup: corner
// reticle, zoom, scan-from-photo, manual Wi-Fi entry. A valid dash QR pairs the bike; a code we cannot
// use SAYS SO (it used to fail in complete silence) and scanning simply carries on.
//
// Scanning is also where a connector is PROVEN. A pairing that only writes the bike to memory has
// guaranteed nothing — the rider finds out on the road. So the scan ends with the mechanisms in plain
// sight and a Conectar that runs the very same path the dashboard runs; only a link that actually forms
// gets pinned as this bike's connector and sends the rider on.
package dev.zanderp.opencfmoto.ui.scan

import android.os.Build
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.ManualWifiPairing
import dev.zanderp.opencfmoto.NearbyDevices
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.connection.CfmotoConnect
import dev.zanderp.opencfmoto.connection.factory.ConnState
import dev.zanderp.opencfmoto.connection.factory.ConnectorChoice
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.usesWifiDirect
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.GhostButton
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.components.StatusKind
import dev.zanderp.opencfmoto.ui.connection.ConnectorChips
import dev.zanderp.opencfmoto.ui.connection.ConnectorHelpButton
import dev.zanderp.opencfmoto.ui.connection.ConnectorHelpDialog
import dev.zanderp.opencfmoto.ui.connection.connectorDescription
import dev.zanderp.opencfmoto.ui.connection.findActivity
import dev.zanderp.opencfmoto.ui.connection.rememberConnectionStatus
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

@Composable
fun ScanScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    val handled = remember { AtomicBoolean(false) }
    // A code we read but cannot use is the rider's only feedback that anything happened at all: `onQr`
    // used to just re-arm the scanner, so pointing the phone at a sticker, a parking QR or another bike's
    // dash did NOTHING — no message, no sound, nothing to distinguish "wrong QR" from "camera is broken".
    // [notice] is that message; it never blocks the camera, which keeps scanning underneath it.
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeAt by remember { mutableLongStateOf(0L) }
    val msgQrUnknown = stringResource(R.string.ovk_scan_qr_unknown)
    val msgQrNoneInPhoto = stringResource(R.string.ovk_scan_qr_none_in_photo)
    // The camera re-reads the SAME unusable code many times a second. Re-raising the message on each read
    // would restart the auto-hide below every frame (flicker); ignoring the repeats would hide it while the
    // rider is still aiming at the offending code. So: the same text within [NOTICE_KEEPALIVE_MS] just keeps
    // the banner alive, and it clears [NOTICE_VISIBLE_MS] after the LAST read — steady while aiming, gone
    // shortly after looking away.
    fun showNotice(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text == notice && now - noticeAt < NOTICE_KEEPALIVE_MS) return
        notice = text
        noticeAt = now
    }
    LaunchedEffect(notice, noticeAt) {
        if (notice != null) {
            delay(NOTICE_VISIBLE_MS)
            notice = null
        }
    }
    val scanner = remember { BarcodeScanning.getClient() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    // After a successful scan the screen turns into the connect step: the bike, every mechanism in plain
    // sight, and Conectar. Null = still scanning → the scan controls show as before.
    var pairedQr by remember { mutableStateOf<QrData?>(null) }
    var showConnectorHelp by remember { mutableStateOf(false) }
    var connectorRefresh by remember { mutableStateOf(0) }
    // The live connection, read exactly as the dashboard gauge reads it (ui/connection/ConnectionStatus.kt):
    // Conectando… / Reconectando n/3 / the real failure reason, in the same words as the cockpit.
    val status = rememberConnectionStatus()
    // The connector THIS tap is proving; non-null = an attempt is in flight. One attempt per tap: nothing
    // here ever retries by itself, and nothing ever switches to a different connector behind the rider's
    // back — a connector that silently substitutes another is exactly what makes a failure unexplainable.
    var attempt by remember { mutableStateOf<ConnectorChoice?>(null) }
    // True from the tap until the connect is SEEN to start (a busy reading). Until then, whatever is on the
    // connection right now belongs to the PREVIOUS attempt, and both stale readings lie in opposite
    // directions: a link still up from the dashboard would be read as proof that THIS connector works, and
    // the error left by the attempt that just failed would be read as this one failing before it began.
    // (The state flows arrive a frame late, so both are reachable on the very tap that starts the connect.)
    var awaitingStart by remember { mutableStateOf(false) }
    // The reason the last attempt failed, kept on screen with the options so the next tap is an informed one.
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); runCatching { scanner.close() } } }

    fun onQr(raw: String) {
        val qr = QrData.parse(raw)
        if (qr != null) {
            BikeMemory.save(ctx, raw, qr)
            pairedQr = qr // show the connection step instead of leaving immediately
        } else {
            // Not a dash pairing QR. Say so and KEEP SCANNING (re-arm) — the rider is holding a phone up to
            // a bike, and the useful answer is "that's the wrong code, here's where the right one is",
            // not a dialog to dismiss.
            showNotice(msgQrUnknown)
            handled.set(false)
        }
    }
    /** Leave for the cockpit — back to the dashboard already on the stack, or straight to it. */
    fun leaveToDashboard() {
        if (!nav.popBackStack(Routes.DASHBOARD, false)) nav.navigate(Routes.DASHBOARD)
    }

    // If the connect never even STARTS — the phone's Wi-Fi is off and Android is asking about it, so the
    // flow returns before touching the connection state — don't leave the rider staring at "Conectando…"
    // forever: fall back to the Conectar button so a second tap is possible. Not a retry: nothing reconnects
    // on its own, the rider decides.
    LaunchedEffect(attempt, awaitingStart) {
        if (attempt != null && awaitingStart) {
            delay(CONNECT_START_TIMEOUT_MS)
            if (awaitingStart) attempt = null
        }
    }

    // The Android 13+ "nearby devices" grant, asked HERE and nowhere else. Wi-Fi Direct is refused without
    // it — WifiP2pManager answers the generic ERROR (0) instantly, which is exactly the failure the Rieju
    // owner's log showed — and the permission had never been requested at runtime at all, only declared.
    //
    // Why at pairing: this screen is where the connector is DECIDED and PROVEN, the rider is deliberately in
    // a pairing flow, and an Activity is guaranteed. Mid-connect (on the road, or at a headless auto-connect
    // with no UI) is the worst possible moment, so the transports only CHECK it there and say what to do.
    // Only for the connectors that actually create/join a Wi-Fi Direct group (usesWifiDirect): a rider whose
    // bike is SoftAP is never asked for something they will not use.
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        LogBus.log("[scan] nearby-devices permission ${if (ok) "granted" else "denied"}")
    }
    // The BLE connector additionally SCANS for the dash and opens a GATT connection, which on Android 12+
    // are two more runtime permissions. Skipping them is not theoretical: on the real Rieju the scan threw
    // and the log read "could not start the BLE scan", after which the blind dial to the QR address sat
    // silent for 25 s. Asked here for the same reason as the nearby-devices grant — pairing is the moment
    // the rider is deliberately setting this bike up, with an Activity in hand.
    val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        LogBus.log("[scan] bluetooth permissions: " + res.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
    }
    LaunchedEffect(pairedQr, connectorRefresh) {
        val qr = pairedQr ?: return@LaunchedEffect
        val mode = BikeMemory.effectiveMode(ctx, qr)
        if (usesWifiDirect(mode) && !NearbyDevices.granted(ctx)) {
            LogBus.log("[scan] '$mode' needs Wi-Fi Direct — asking for the nearby-devices permission before Connect")
            NearbyDevices.markAsked(ctx)
            nearbyLauncher.launch(NearbyDevices.PERMISSION)
            return@LaunchedEffect
        }
        if (mode == TransportKind.PHONE_HOTSPOT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                LogBus.log("[scan] this bike hands its Wi-Fi credentials over Bluetooth — asking for ${missing.size} bluetooth permission(s)")
                bleLauncher.launch(missing.toTypedArray())
            }
        }
    }

    // Run the connect. Deliberately NOT a new connection path: prepareFreeRide + startCfmotoMap with
    // preferFactory = true is exactly what the dashboard's Conectar does, so whatever the rider proves here
    // is what will happen on the road. The connector was already persisted when they tapped its chip, so the
    // factory picks it up from the bike's stored spec.
    fun connectNow(qr: QrData) {
        val activity = ctx.findActivity() ?: return
        failure = null
        awaitingStart = true
        attempt = BikeMemory.connectorChoice(ctx, qr)
        GpxSession.prepareFreeRide()
        CfmotoConnect.startCfmotoMap(activity, preferFactory = true)
    }

    // The outcome of the attempt in flight. `linkUp` — not the gauge's colour — is what counts as proof
    // (see ConnectionStatus.linkUp: startCfmotoMap marks "projecting to the dash" before it has joined
    // anything, so the colour turns green a millisecond after the tap with nothing connected).
    LaunchedEffect(status.linkUp, status.kind, status.factory, attempt, awaitingStart) {
        val pending = attempt ?: return@LaunchedEffect
        val qr = pairedQr ?: return@LaunchedEffect
        if (awaitingStart) {
            if (status.kind == StatusKind.BUSY) awaitingStart = false
            return@LaunchedEffect
        }
        when {
            status.linkUp -> {
                // It WORKED. Pin the connector that actually formed the link, so this bike carries a proven
                // choice instead of a guess to be re-derived (and re-lost) on every ride — then hand the
                // rider over to the cockpit, connected.
                val formed = (status.factory as? ConnState.Connected)?.endpoint?.kind
                BikeMemory.setConnectorChoice(ctx, qr, provenChoice(ctx, qr, pending, formed))
                attempt = null
                failure = null
                leaveToDashboard()
            }
            status.kind == StatusKind.FAULT -> {
                // Stay. The reason goes on screen next to the options, because the fix for a wrong connector
                // is the rider picking another one — never the app trying one behind their back.
                failure = status.text
                attempt = null
            }
        }
    }

    fun setZoom(r: Float) {
        zoom = r
        val cam = camera ?: return
        val st = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(r.coerceIn(st.minZoomRatio, st.maxZoomRatio))
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && handled.compareAndSet(false, true)) {
            // Two different failures, two different messages: an image with NO code in it is the rider's
            // photo problem ("no QR found"), while an image whose code we can read but not use falls through
            // to onQr's "not a compatible bike". Collapsing them would send the rider hunting for a better
            // photo of a QR that was never going to work.
            runCatching {
                scanner.process(InputImage.fromFilePath(ctx, uri))
                    .addOnSuccessListener { bs ->
                        val qr = bs.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                        if (qr != null) {
                            onQr(qr)
                        } else {
                            showNotice(msgQrNoneInPhoto)
                            handled.set(false)
                        }
                    }
                    .addOnFailureListener { showNotice(msgQrNoneInPhoto); handled.set(false) }
            }.onFailure { showNotice(msgQrNoneInPhoto); handled.set(false) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val pv = PreviewView(c)
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy -> analyzeProxy(proxy, scanner, handled) { raw -> onQr(raw) } }
                        runCatching {
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(c))
                    pv
                },
            )
            // The reticle belongs to scanning; once a bike is paired the connect panel owns the screen.
            if (pairedQr == null) Box(Modifier.fillMaxSize().padding(horizontal = 52.dp).padding(top = 120.dp, bottom = 220.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val len = 34.dp.toPx(); val sw = 5.dp.toPx(); val w = size.width; val h = size.height
                        drawLine(c.ignition, Offset(0f, 0f), Offset(len, 0f), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(0f, 0f), Offset(0f, len), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, 0f), Offset(w - len, 0f), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, 0f), Offset(w, len), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(0f, h), Offset(len, h), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(0f, h), Offset(0f, h - len), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, h), Offset(w - len, h), sw, StrokeCap.Round)
                        drawLine(c.ignition, Offset(w, h), Offset(w, h - len), sw, StrokeCap.Round)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.ovk_scan_perm_title), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.ovk_scan_perm_body), color = c.inkDim, fontSize = 13.sp)
            }
        }

        // Top bar
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(10.dp)).clickable { nav.popBackStack() },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = c.inkDim, fontSize = 15.sp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.ovk_tile_scan), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                MonoLabel(stringResource(R.string.ovk_scan_subtitle), color = c.inkDim)
            }
        }

        // Bottom controls — hidden once a bike is paired (the connect step takes over).
        if (pairedQr == null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                notice?.let { ScanNotice(it) }
                Text(stringResource(R.string.ovk_scan_aim), color = c.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZoomPill("1×", zoom == 1f) { setZoom(1f) }
                    ZoomPill("2×", zoom == 2f) { setZoom(2f) }
                    ZoomPill("3×", zoom == 3f) { setZoom(3f) }
                    ZoomPill("◲ " + stringResource(R.string.ovk_scan_photo), false) { handled.set(false); photoLauncher.launch("image/*") }
                }
                GhostButton(stringResource(R.string.ovk_scan_manual_wifi), {
                    (ctx as? AppCompatActivity)?.let { act ->
                        ManualWifiPairing.show(act) { raw, _ ->
                            if (handled.compareAndSet(false, true)) onQr(raw)
                        }
                    }
                }, Modifier.fillMaxWidth())
            }
        }

        // The connect step: the bike, every mechanism in plain sight, and Conectar. This is the guarantee —
        // the rider does not leave this screen believing a connector works until one actually has.
        pairedQr?.let { qr ->
            val current = remember(qr, connectorRefresh) { BikeMemory.connectorChoice(ctx, qr) }
            // What AUTO really resolves to on this phone (QR + Setup preference + this bike's learned
            // winner), so the "Automático" line can name the mechanism it will actually use.
            val detected = remember(qr, connectorRefresh) { BikeMemory.autoDetectedMode(ctx, qr) }
            val bikeName = qr.name?.takeIf { it.isNotBlank() } ?: qr.ssid
            val busy = attempt != null
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.ground.copy(alpha = 0.94f)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            MonoLabel(stringResource(R.string.ovk_scan_paired), color = c.inkDim)
                            Text(
                                bikeName, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        ConnectorHelpButton(onClick = { showConnectorHelp = true })
                    }
                    if (busy) {
                        // Until the connect is seen starting, the connection is still reporting the PREVIOUS
                        // attempt (flows arrive a frame late) — saying "Conectando…" is simply what is true.
                        Text(
                            if (awaitingStart) stringResource(R.string.conn_joining_wifi) else status.text,
                            color = c.ignition, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                        )
                    } else {
                        failure?.let { ScanNotice(it) }
                    }
                    MonoLabel(stringResource(R.string.ovk_scan_how_it_connects), color = c.inkFaint)
                    ConnectorChips(current = current, enabled = !busy) { picked ->
                        BikeMemory.setConnectorChoice(ctx, qr, picked)
                        connectorRefresh++
                        failure = null
                    }
                    Text(connectorDescription(current, detected), color = c.inkDim, fontSize = 11.5.sp)
                }
                PrimaryButton(
                    text = if (busy) stringResource(R.string.ovk_cancel) else stringResource(R.string.ovk_connect),
                    onClick = {
                        if (busy) {
                            // Cancel is the rider's, not ours: we never abandon an attempt on our own.
                            CfmotoConnect.stop(ctx)
                            attempt = null
                        } else {
                            connectNow(qr)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostButton(stringResource(R.string.ovk_scan_done), { nav.popBackStack() }, Modifier.fillMaxWidth())
            }
        }

        if (showConnectorHelp) {
            ConnectorHelpDialog(onDismiss = { showConnectorHelp = false })
        }
    }
}

/**
 * The connector to REMEMBER after a connect that worked — a VALIDATED pin, not a guess the app has to
 * re-derive (and can re-derive differently) on every ride.
 *
 * A pinned choice just proved itself, so it stays. AUTO is the interesting one, and the honest answer is
 * [formed]: the transport that ACTUALLY carried the link, straight off the connection's own endpoint. The
 * bike's stored spec would seem the obvious source, but the driver publishes `Connected` BEFORE its
 * persistence hook writes that spec (`DefaultBikeConnection.ensureConnected`), so reading it here can lose
 * the race and pin the mode that was stored *before* this connect — the very stale value the connect just
 * disproved. The spec is only the fallback for the classic (no-factory) path, which has no endpoint to ask.
 */
private fun provenChoice(
    ctx: Context,
    qr: QrData,
    attempted: ConnectorChoice,
    formed: TransportKind?,
): ConnectorChoice = when {
    attempted != ConnectorChoice.AUTO -> attempted
    formed != null -> ConnectorChoice.forTransport(formed)
    else -> ConnectorChoice.forTransport(BikeMemory.specFor(ctx, qr)?.mode ?: BikeMemory.autoDetectedMode(ctx, qr))
}

/** How long to wait for a tapped connect to be seen starting before offering Conectar again. */
private const val CONNECT_START_TIMEOUT_MS = 8_000L

/** How long an unusable-code message stays up after the LAST read of that code. */
private const val NOTICE_VISIBLE_MS = 3_500L

/** Repeats of the SAME message inside this window keep the banner alive instead of re-raising it. */
private const val NOTICE_KEEPALIVE_MS = 900L

/**
 * Non-blocking "that code is no use here" banner. Deliberately NOT a dialog: the camera keeps scanning
 * underneath, so the rider can simply move the phone to the right QR without dismissing anything.
 */
@Composable
private fun ScanNotice(text: String) {
    val c = LocalCockpitColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface1)
            .border(1.dp, c.warn.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = c.warn, fontSize = 13.sp)
        Spacer(Modifier.width(9.dp))
        Text(text, color = c.ink, fontSize = 12.sp)
    }
}

@Composable
private fun ZoomPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    val fg = if (selected) c.ignition else c.inkDim
    val bg = if (selected) c.ignition.copy(alpha = 0.14f) else c.surface1
    val bd = if (selected) c.ignition.copy(alpha = 0.4f) else c.line
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).border(1.dp, bd, RoundedCornerShape(999.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
}

@SuppressLint("UnsafeOptInUsageError")
private fun analyzeProxy(proxy: ImageProxy, scanner: BarcodeScanner, handled: AtomicBoolean, onQr: (String) -> Unit) {
    if (handled.get()) { proxy.close(); return }
    val media = proxy.image
    if (media == null) { proxy.close(); return }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val qr = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
            if (qr != null && handled.compareAndSet(false, true)) onQr(qr)
        }
        .addOnCompleteListener { proxy.close() }
}
