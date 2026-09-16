// SPDX-License-Identifier: AGPL-3.0-or-later
// THE Compose copy of the Android-Auto preview-surface contract — there must never be a second one.
//
// The decoded AA frame is drawn by [dev.zanderp.opencfmoto.AaCompositor] into whatever Surface the
// pipeline was handed; attaching/detaching that Surface at the right moment is the highest-regression
// -risk code in the app (get it wrong and the dash goes black, or the GL surface outlives the window).
// So the callback bodies below are a VERBATIM port of the classic
// [dev.zanderp.opencfmoto.HudViewActivity]'s live path: attach on `surfaceChanged` when the pipeline
// is live, `updatePreviewSize` on later size changes, `clearPreviewSurface()` + reset the flag on
// `surfaceDestroyed`. Nothing here touches the video core — it only ATTACHES, exactly as the classic
// screen does, so both can host the preview (never at the same time: one screen is visible at a time).
//
// The ONE deliberate omission is the classic's `if (gpxPreview) return` guard: this composable has no
// map/GPX mode to fall back to. That is the point — with Google Maps / Waze the bike dash is painted
// by Android Auto, so our own map is not a preview of anything (see DashViewScreen).
package dev.zanderp.opencfmoto.ui.dash

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeProfileHolder
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.aa.AaInput

/**
 * Mutable attach latch shared by the surface callbacks and `onDispose` — the Compose stand-in for
 * HudViewActivity's `private var attached` field. Also carries the classic's toast throttle.
 */
private class AaPreviewAttach {
    var attached = false
    var noSessionToastAt = 0L
}

/**
 * The LIVE Android Auto video, mirrored on the phone: the very pixels Android Auto is painting on the
 * bike dash. Host it only while [AaVideoBridge.pipeline] is non-null — when there is no live video
 * there is nothing to show, and showing anything else would be a fabrication.
 *
 * Touches are forwarded to Android Auto exactly like the classic Dash view (already in AA source
 * space, via [AaVideoBridge.previewTouchSink]); [onNoSession] fires — at most once every 3 s, the
 * classic's throttle — when a touch arrives with no AA session behind it.
 */
@Composable
fun AaDashVideo(
    modifier: Modifier = Modifier,
    onNoSession: () -> Unit = {},
) {
    val attach = remember { AaPreviewAttach() }
    val noSession by rememberUpdatedState(onNoSession)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        val pipe = AaVideoBridge.pipeline
                        if (pipe == null) {
                            LogBus.log("[dash-view] no AA pipeline — preview will show once Android Auto is live")
                            return
                        }
                        if (!attach.attached) {
                            pipe.setPreviewSurface(holder.surface, width, height)
                            attach.attached = true
                            LogBus.log("[dash-view] preview surface attached ${width}x$height")
                        } else {
                            pipe.updatePreviewSize(width, height)
                        }
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (attach.attached) {
                            AaVideoBridge.pipeline?.clearPreviewSurface()
                            attach.attached = false
                            LogBus.log("[dash-view] preview surface detached")
                        }
                    }
                })
                setOnTouchListener { v, e ->
                    onSurfaceTouch(v.width, v.height, e, attach) { noSession() }
                    true
                }
            }
        },
    )

    // Belt and braces: the composable can leave the composition before the platform delivers
    // `surfaceDestroyed` (route change, host teardown). Same body, guarded by the same flag, so a
    // double detach is impossible.
    DisposableEffect(Unit) {
        onDispose {
            if (attach.attached) {
                AaVideoBridge.pipeline?.clearPreviewSurface()
                attach.attached = false
                LogBus.log("[dash-view] preview surface detached (dispose)")
            }
        }
    }
}

/** Ported verbatim from HudViewActivity.onSurfaceTouch (minus its GPX-preview guard). */
private fun onSurfaceTouch(
    viewW: Int,
    viewH: Int,
    e: MotionEvent,
    attach: AaPreviewAttach,
    onNoSession: () -> Unit,
) {
    val sink = AaVideoBridge.previewTouchSink
    if (sink == null) {
        val now = System.currentTimeMillis()
        if (now - attach.noSessionToastAt > 3000) {
            attach.noSessionToastAt = now
            onNoSession()
        }
        return
    }
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
            forward(sink, viewW, viewH, e, e.actionIndex, AaInput.ACTION_DOWN)
        MotionEvent.ACTION_MOVE ->
            for (i in 0 until e.pointerCount) forward(sink, viewW, viewH, e, i, AaInput.ACTION_MOVE)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
            forward(sink, viewW, viewH, e, e.actionIndex, AaInput.ACTION_UP)
        MotionEvent.ACTION_CANCEL ->
            for (i in 0 until e.pointerCount) forward(sink, viewW, viewH, e, i, AaInput.ACTION_UP)
    }
}

/** Ported verbatim from HudViewActivity.forward. */
private fun forward(
    sink: (Int, Int, Int, Int) -> Unit,
    viewW: Int,
    viewH: Int,
    e: MotionEvent,
    index: Int,
    action: Int,
) {
    val src = mapToSource(viewW, viewH, e.getX(index), e.getY(index)) ?: return
    sink(action, e.getPointerId(index), src.first, src.second)
}

/** Ported verbatim from HudViewActivity.mapToSource (the view size is passed in, not read off a field). */
private fun mapToSource(vw: Int, vh: Int, vx: Float, vy: Float): Pair<Int, Int>? {
    val sw = BikeProfileHolder.aaUsableWidth
    val sh = BikeProfileHolder.aaUsableHeight
    if (vw == 0 || vh == 0 || sw == 0 || sh == 0) return null

    val srcAspect = sw.toFloat() / sh
    val viewAspect = vw.toFloat() / vh
    val rectW: Int
    val rectH: Int
    if (srcAspect < viewAspect) {
        rectH = vh; rectW = Math.round(vh * srcAspect)
    } else {
        rectW = vw; rectH = Math.round(vw / srcAspect)
    }
    val rectX = (vw - rectW) / 2
    val rectY = (vh - rectH) / 2

    val rx = vx - rectX
    val ry = vy - rectY
    if (rx < 0 || ry < 0 || rx >= rectW || ry >= rectH) return null
    val sx = (rx * sw / rectW).toInt().coerceIn(0, sw - 1)
    val sy = (ry * sh / rectH).toInt().coerceIn(0, sh - 1)
    return sx to sy
}
