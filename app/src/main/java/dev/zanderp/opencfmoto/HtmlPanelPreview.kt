// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * DEV: runs the dash pipeline with the HTML panel WITHOUT a bike, so the panel can be developed and the
 * hot-reload verified at a desk (where `adb` exists) instead of at the bike (where it does not).
 *
 * It deliberately passes **no probe hooks**, so [VideoPipeline] takes the same branch it takes on a real
 * connection — `probePresentationContent == null && DashHtmlPanel.isEnabled(context)` →
 * [VideoPipeline.setupHtmlPanelPresentation]. Testing a copy of that path would prove nothing about it.
 *
 * What this CANNOT tell you: whether the dash accepts and paints the stream. That needs the bike. What it
 * DOES tell you, and what would otherwise be discovered on the roadside: whether the panel renders, and
 * whether editing the file updates it live.
 *
 * ```
 * MSYS_NO_PATHCONV=1 adb push panel.html \
 *   /sdcard/Android/data/dev.authoritforge.opencfmoto/files/paneles/panel.html
 * ```
 */
object HtmlPanelPreview {
    private val LOGTAG = VideoPipeline.PROBE_TAG
    private val main = Handler(Looper.getMainLooper())

    private const val W = 1024
    private const val H = 464
    /** Long enough to edit the panel a few times; still bounded so a forgotten preview cannot run all day. */
    private const val MAX_MS = 300_000L

    @Volatile private var running = false
    private var video: VideoPipeline? = null
    private var wake: PowerManager.WakeLock? = null

    val isRunning: Boolean get() = running

    fun toggle(context: Context): String = if (running) stop("user-tap") else start(context)

    fun start(context: Context): String {
        if (running) return "Vista previa ya en marcha"
        val ctx = context.applicationContext
        if (!DashHtmlPanel.isEnabled(ctx)) {
            return "Activa antes «Panel HTML en el tablero»"
        }
        val panel = DashHtmlPanel.ensureSeeded(ctx)
            ?: return "No se pudo crear la carpeta de paneles"
        running = true
        plog("=== vista previa del panel HTML (${W}x$H, max ${MAX_MS / 1000}s) — panel: ${panel.absolutePath} ===")

        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencfmoto:html-panel-preview").apply {
                setReferenceCounted(false)
                acquire(MAX_MS + 5_000L)
            }
        } catch (e: Exception) {
            plog("wakelock failed ($e)")
        }

        // No probe hooks on purpose: this must take the production branch, not a parallel one.
        val vp = VideoPipeline(
            context = ctx,
            width = W,
            height = H,
            log = LogBus::log,
            forceDump = true,
            probeFrameLog = true,
        )
        video = vp
        main.post {
            vp.start()
            if (!vp.isAlive) {
                plog("VideoPipeline no arrancó (encoder?) — abortando")
                stop("start-failed")
            }
        }
        main.postDelayed({ if (running) stop("auto-${MAX_MS / 1000}s") }, MAX_MS)
        return "Vista previa en marcha. Edita ${panel.name} y el panel se recarga solo."
    }

    fun stop(reason: String = "manual"): String {
        if (!running) return "no estaba en marcha"
        running = false
        plog("=== vista previa DETENIDA ($reason) ===")
        try { video?.stop() } catch (e: Exception) { plog("stop error: $e") }
        video = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        return "Vista previa detenida ($reason)"
    }

    private fun plog(msg: String) {
        android.util.Log.i(LOGTAG, msg)
        LogBus.log("[PANEL] $msg")
    }
}
