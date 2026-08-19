// SPDX-License-Identifier: AGPL-3.0-or-later
// Dash view (Android Auto) — the cockpit's window onto the REAL bike dash.
//
// With Google Maps / Waze the dash is painted by ANDROID AUTO, not by us: our own map is not a preview
// of it, it is a different map with a route we computed ourselves. Showing that here told the rider a
// story about their dash that wasn't true (the rider set a destination, opened Dash view, and saw our
// map with our route — while the dash showed something else, or nothing). So this screen shows the
// live Android Auto video or it shows NOTHING but the honest reason: no map, no stand-in, no fallback.
//
// The video surface itself is [AaDashVideo] — the single Compose copy of HudViewActivity's attach
// contract. The classic HudViewActivity is untouched and keeps working exactly as before.
package dev.zanderp.opencfmoto.ui.dash

import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.VideoPipeline
import dev.zanderp.opencfmoto.connection.CfmotoConnect
import dev.zanderp.opencfmoto.ui.Routes
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.components.StatusKind
import dev.zanderp.opencfmoto.ui.connection.findActivity
import dev.zanderp.opencfmoto.ui.connection.rememberConnectionStatus
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors
import kotlinx.coroutines.delay

/**
 * How often we re-read [AaVideoBridge.pipeline]. It is a plain `@Volatile` field with no flow behind
 * it (the AA service assigns it), so the classic Dash view simply CHECKS it — on `onResume`, on a new
 * intent. We check it the same way, on a tick as well, so a bike that connects (or drops) while this
 * screen is open is picked up without the rider having to leave and come back. Same poll shape the
 * cockpit already uses elsewhere (LogScreen 200 ms, the nav card 1 s).
 */
private const val PIPELINE_POLL_MS = 500L

@Composable
fun DashViewScreen(nav: NavController) {
    val c = LocalCockpitColors.current
    val ctx = LocalContext.current
    val status = rememberConnectionStatus()

    // The live pipeline INSTANCE, not just "is it live": if a session ends and a new one starts between
    // two ticks, the key below rebuilds the SurfaceView so we attach to the new pipeline instead of
    // holding a surface on the dead one (a black preview).
    var pipeline by remember { mutableStateOf<VideoPipeline?>(AaVideoBridge.pipeline) }
    LaunchedEffect(Unit) {
        while (true) {
            pipeline = AaVideoBridge.pipeline
            delay(PIPELINE_POLL_MS)
        }
    }
    // …and immediately on resume, exactly like HudViewActivity.onResume → refreshMode().
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { pipeline = AaVideoBridge.pipeline }

    // Watching the dash on the phone shouldn't let the screen sleep (classic Dash view does the same).
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Conectar: the SAME Android-Auto connect the dashboard runs (CfmotoConnect.startAaConnect) — the
    // rider is in Google/Waze mode, so this is the projection that makes a dash image exist at all.
    fun connect() {
        val host = ctx.findActivity() ?: run {
            LogBus.log("[dash-view] Conectar: sin Activity host — no se puede conectar")
            return
        }
        val qr = BikeMemory.lastQr(ctx) ?: run { nav.navigate(Routes.SCAN); return }
        CfmotoConnect.startAaConnect(host, qr)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val live = pipeline
        if (live != null) {
            // The real thing. key(pipeline): a new session = a new surface = a fresh attach.
            key(live) {
                AaDashVideo(
                    modifier = Modifier.fillMaxSize(),
                    onNoSession = {
                        Toast.makeText(ctx, ctx.getString(R.string.ovk_dash_connect_first), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        } else {
            // NO fabricated preview: never our own map here. Just the honest reason + the way out.
            Column(
                Modifier.fillMaxSize().background(c.ground).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.ovk_dash_no_video),
                    color = c.ink,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(18.dp))
                // The shared reading of the connection (same rules as the dashboard gauge), so a connect
                // already in flight reads "Conectando…" / "Reconectando 1/3" instead of inviting a retap.
                Text(status.text, color = c.inkDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                if (status.kind != StatusKind.BUSY) {
                    Spacer(Modifier.size(14.dp))
                    PrimaryButton(
                        text = stringResource(R.string.ovk_connect),
                        onClick = { connect() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Top bar — close (✕), the screen's name, and a live pill when video is really flowing.
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlyphBox("✕") { nav.popBackStack() }
            Text(
                stringResource(R.string.ovk_dash_view),
                color = c.ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (pipeline != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.live.copy(alpha = 0.16f))
                        .border(1.dp, c.live.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("●", color = c.live, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ovk_dash_live), color = c.ink, fontSize = 12.sp)
                }
            }
        }

        // Bottom bar — the D-pad/rotary that drives Android Auto without touching the dash.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarButton("✥ " + stringResource(R.string.ovk_tile_controls), Modifier.weight(1f)) {
                nav.navigate(Routes.CONTROLS)
            }
            BarButton("■ " + stringResource(R.string.ovk_close), Modifier.weight(1f)) { nav.popBackStack() }
        }
    }
}

@Composable
private fun BarButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(c.surface1.copy(alpha = 0.92f))
            .border(1.dp, c.line, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
}

@Composable
private fun GlyphBox(glyph: String, onClick: () -> Unit) {
    val c = LocalCockpitColors.current
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface1.copy(alpha = 0.92f))
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = c.inkDim, fontSize = 15.sp) }
}
