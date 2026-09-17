// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.cockpit

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.VideoPipelineHolder
import dev.zanderp.opencfmoto.browser.DashBrowser
import dev.zanderp.opencfmoto.browser.PreviewTouchMap
import dev.zanderp.opencfmoto.ui.components.MonoLabel
import dev.zanderp.opencfmoto.ui.components.PrimaryButton
import dev.zanderp.opencfmoto.ui.components.StatusChip
import dev.zanderp.opencfmoto.ui.components.kind
import dev.zanderp.opencfmoto.ui.theme.LocalCockpitColors

/**
 * Drives the ONE browser that lives on the dash VirtualDisplay.
 *
 * This screen never hosts a second WebView. It shows the *same* frames, through the compositor's
 * preview surface, and forwards its touches into the *same* view — which is what keeps the phone and
 * the dash from ever showing different pages.
 *
 * The preview is a wide band, not a phone-shaped page, because the browser is laid out at the bike
 * canvas (~2.2:1). That is the price of what you touch being exactly what is transmitted.
 */
@Composable
fun BrowserControlScreen(nav: NavController, canvasW: Int, canvasH: Int) {
    val c = LocalCockpitColors.current
    var typed by remember { mutableStateOf("") }
    val conn by ConnectionState.flow.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(c.ground).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel("NAVEGADOR")
            // Spec §4: the rider must never drive a surface that is going nowhere.
            StatusChip(label = conn.phase.logLabel, kind = conn.phase.kind())
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Dirección o búsqueda") },
            )
            PrimaryButton(text = "Ir", onClick = {
                val url = DashBrowser.toNavigationUrl(typed)
                VideoPipelineHolder.browser()?.let { wv -> wv.post { wv.loadUrl(url) } }
            })
        }

        Box(
            Modifier.fillMaxWidth().aspectRatio(canvasW.toFloat() / canvasH),
            contentAlignment = Alignment.Center,
        ) {
            if (VideoPipelineHolder.compositor() == null) {
                // No pipeline: say so instead of showing a dead black rectangle that looks like a bug.
                Text(
                    "Conecta la moto para ver aquí lo que va al tablero.",
                    color = c.inkFaint,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    VideoPipelineHolder.compositor()?.setPreview(h.surface, width, height)
                                }

                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                    VideoPipelineHolder.compositor()?.updatePreviewSize(w, hh)
                                }

                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    // Leaving the screen must not keep the GL stage drawing into a dead
                                    // surface — the dash keeps its own output either way.
                                    VideoPipelineHolder.compositor()?.clearPreview()
                                }
                            })
                            setOnTouchListener { v, e ->
                                val action = when (e.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> 0
                                    MotionEvent.ACTION_UP -> 1
                                    MotionEvent.ACTION_MOVE -> 2
                                    else -> return@setOnTouchListener false
                                }
                                // The SAME funnel the dash touchscreen uses, so the two can never
                                // disagree about where a tap landed.
                                PreviewTouchMap
                                    .toCanvas(e.x, e.y, v.width, v.height, canvasW, canvasH)
                                    ?.let { (cx, cy) -> GpxSession.dispatchTouch(action, cx, cy) }
                                true
                            }
                        }
                    },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = "Atrás", modifier = Modifier.weight(1f), onClick = {
                VideoPipelineHolder.browser()?.let { wv -> wv.post { if (wv.canGoBack()) wv.goBack() } }
            })
            PrimaryButton(text = "Adelante", modifier = Modifier.weight(1f), onClick = {
                VideoPipelineHolder.browser()?.let { wv -> wv.post { if (wv.canGoForward()) wv.goForward() } }
            })
            PrimaryButton(text = "Recargar", modifier = Modifier.weight(1f), onClick = {
                VideoPipelineHolder.browser()?.let { wv -> wv.post { wv.reload() } }
            })
            PrimaryButton(text = "Mapa", modifier = Modifier.weight(1f), onClick = {
                VideoPipelineHolder.browser()?.let { wv -> wv.post { wv.loadUrl(DashBrowser.HOME_URL) } }
            })
        }

        PrimaryButton(text = "Volver", modifier = Modifier.fillMaxWidth(), onClick = { nav.popBackStack() })
    }
}
