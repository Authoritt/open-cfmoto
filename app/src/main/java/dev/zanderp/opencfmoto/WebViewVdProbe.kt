// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * THROWAWAY DEV PROBE — spike only, not production. Reachable solely from the developer settings row.
 *
 * ## The question it settles
 * The bike dash is fed by a [Presentation] on a PRIVATE VirtualDisplay whose Surface drives a
 * MediaCodec H.264 encoder ([VideoPipeline] own-content mode). [MapLibreVdProbe] already proved that
 * pipeline keeps producing frames with the phone SCREEN OFF (~60 -> ~115 fps, clean stream).
 *
 * This probe swaps the ONE variable — the renderer becomes a [WebView] — to answer whether an HTML
 * panel could be the dash surface. A WebView is our OWN View in our OWN process, so it is not blocked
 * by the ADD_TRUSTED_DISPLAY wall that stops third-party apps from being projected.
 *
 * ## The failure mode this is built to catch
 * "Frames keep flowing" is NOT the answer. Chromium can keep compositing while it throttles timers and
 * rAF for a page it believes is hidden — the encoder rate would look perfect and the dash would be a
 * PHOTOGRAPH. So the page carries THREE independent liveness signals plus a native control:
 *
 *  1. **CSS animation** — a sweeping bar. No JS at all: tests pure compositing.
 *  2. **rAF counter** — driven by `requestAnimationFrame`: tests the compositor callback.
 *  3. **setInterval counter** — driven by a 100 ms timer: tests background timer throttling.
 *  4. **Native counter** (a [TextView] drawn next to the WebView) — the control. If it advances while
 *     all three web signals freeze, the stall is Chromium's, not the pipeline's.
 *
 * The page also prints `document.visibilityState`, which says outright what Chromium thinks.
 *
 * **No keep-alive mitigation is applied on purpose** (no `resumeTimers()`, no `onResume()` forcing).
 * Applying one here would hide the very thing being measured; if the default throttles, the mitigation
 * is the follow-up, not the experiment.
 *
 * ## How to run it
 *  1. Dev settings -> "WebView VD probe". Runs ~60 s, then stops itself.
 *  2. `adb logcat -s ${VideoPipeline.PROBE_TAG}:*` — encoder fps AND a twice-per-second line with the
 *     three web counters, so the timeline is readable without decoding video.
 *  3. Screen off mid-run: `adb shell input keyevent 26` (back on: 26 again).
 *  4. `adb pull <dumpPath>` and decode: the same counters are burned into the pixels.
 *
 * No bike needed: the encoder drains to a file regardless of any connection.
 */
object WebViewVdProbe {
    private val LOGTAG = VideoPipeline.PROBE_TAG
    private val main = Handler(Looper.getMainLooper())

    // Same canvas as the MapLibre probe so the two runs are comparable (Ride MO NaviVirtualDisplay).
    private const val PROBE_W = 1024
    private const val PROBE_H = 464
    private const val RUN_MS = 60_000L
    private const val NATIVE_TICK_MS = 100L

    @Volatile private var running = false
    private var video: VideoPipeline? = null
    private var web: WebView? = null
    private var nativeLabel: TextView? = null
    private var wake: PowerManager.WakeLock? = null
    private var nativeTicks = 0
    private var startedAt = 0L

    val isRunning: Boolean get() = running

    /**
     * Absolute path the probe's H.264 dump lands at (for `adb pull`). [VideoPipeline.maybeStartDump]
     * derives the name from the probe hook being set, so it is the SAME file the MapLibre probe uses —
     * run one probe at a time.
     */
    fun dumpPath(context: Context): String =
        java.io.File(java.io.File(context.applicationContext.getExternalFilesDir(null), "video"), "opencfmoto-video-probe.h264")
            .absolutePath

    /** Dev-settings action: start when idle, stop when running. Returns a short status for a Toast. */
    fun toggle(context: Context): String = if (running) stop("user-tap") else start(context)

    fun start(context: Context): String {
        if (running) return "WebView VD probe already running (~stops at 60 s)"
        val ctx = context.applicationContext
        running = true
        nativeTicks = 0
        startedAt = System.currentTimeMillis()
        plog("=== WebView-on-VD probe START (${PROBE_W}x$PROBE_H, ~${RUN_MS / 1000}s) ===")
        plog("signals: cssBar (no JS) | raf | timer(100ms) | native (control). NO keep-alive applied on purpose.")

        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencfmoto:webview-vd-probe").apply {
                setReferenceCounted(false)
                acquire(RUN_MS + 5_000L)
            }
            plog("partial wakelock held — a mid-run screen-off cannot freeze the CPU")
        } catch (e: Exception) {
            plog("wakelock failed ($e) — screen-off could pause the CPU and muddy the result")
        }

        val path = dumpPath(ctx)
        plog("H.264 dump -> $path")
        plog("watch: adb logcat -s $LOGTAG:*   |   screen off mid-run: adb shell input keyevent 26")

        val vp = VideoPipeline(
            context = ctx,
            width = PROBE_W,
            height = PROBE_H,
            log = LogBus::log,
            forceDump = true,
            probeFrameLog = true,
            probePresentationContent = { host, presCtx, _ -> onPresentationReady(host, presCtx) },
            onProbeStop = { releaseWeb() },
        )
        video = vp
        main.post {
            vp.start()
            if (!vp.isAlive) {
                plog("VideoPipeline failed to start (encoder?) — aborting")
                stop("start-failed")
            }
        }
        main.postDelayed({ if (running) stop("auto-60s") }, RUN_MS)
        return "WebView probe started (~${RUN_MS / 1000}s). Dump: $path"
    }

    fun stop(reason: String = "manual"): String {
        if (!running) return "probe not running"
        running = false
        plog("=== probe STOP ($reason) — native ticks=$nativeTicks ===")
        main.removeCallbacks(nativeTick)
        try { video?.stop() } catch (e: Exception) { plog("VideoPipeline.stop error: $e") }
        video = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null
        return "WebView VD probe stopped ($reason)"
    }

    /** Runs on the main thread (VideoPipeline invokes the content factory there). */
    private fun onPresentationReady(host: FrameLayout, presCtx: Context) {
        try {
            val wv = WebView(presCtx)
            wv.setBackgroundColor(Color.BLACK)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.addJavascriptInterface(Bridge(), "Probe")
            host.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            wv.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
            web = wv

            // Native control counter, drawn by the platform next to the web content.
            val tv = TextView(presCtx).apply {
                setTextColor(Color.YELLOW)
                textSize = 34f
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                setPadding(16, 8, 16, 8)
            }
            host.addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END))
            nativeLabel = tv

            plog("WebView created on the VD Presentation; page loading")
            main.removeCallbacks(nativeTick)
            main.post(nativeTick)
        } catch (e: Exception) {
            plog("WebView init FAILED on the VD: $e")
        }
    }

    /** The control signal: a platform-drawn counter that does not go through Chromium at all. */
    private val nativeTick = object : Runnable {
        override fun run() {
            if (!running) return
            nativeTicks++
            nativeLabel?.text = "native $nativeTicks"
            main.postDelayed(this, NATIVE_TICK_MS)
        }
    }

    private fun releaseWeb() {
        main.removeCallbacks(nativeTick)
        try {
            web?.let { it.loadUrl("about:blank"); (it.parent as? FrameLayout)?.removeView(it); it.destroy() }
        } catch (_: Exception) {}
        web = null
        nativeLabel = null
        plog("WebView released (no leak)")
    }

    /** JS -> native, twice a second, so the timeline is in logcat and not only in the pixels. */
    private class Bridge {
        @JavascriptInterface
        fun report(raf: Int, timer: Int, visibility: String, pageMs: Double) {
            val wall = System.currentTimeMillis() - startedAt
            plog("web t=${wall}ms raf=$raf timer=$timer vis=$visibility pageClock=${pageMs.toLong()}ms")
        }
    }

    private fun plog(msg: String) {
        android.util.Log.i(LOGTAG, msg)
        LogBus.log("[WEBPROBE] $msg")
    }

    /**
     * Everything is inline: no network, no external asset — the bike has no internet and the real
     * panel would be served from the phone itself. Big type on purpose: these numbers have to be
     * readable in a decoded 1024x464 H.264 frame.
     */
    private val PAGE = """
        <!doctype html><html><head><meta charset="utf-8">
        <style>
          html,body{margin:0;height:100%;background:#000;color:#fff;
                    font-family:monospace;overflow:hidden}
          .row{font-size:44px;padding:2px 10px}
          #raf{color:#4ade80} #timer{color:#60a5fa} #vis{color:#fbbf24} #clock{color:#f87171}
          #barwrap{position:absolute;bottom:0;left:0;width:100%;height:54px;background:#111}
          /* Signal 1: pure CSS, no JS at all. If this moves and the counters do not,
             the compositor is alive and only the script engine was throttled. */
          #bar{width:120px;height:100%;background:#e11d48;
               animation:sweep 2s linear infinite}
          @keyframes sweep{from{transform:translateX(0)}to{transform:translateX(904px)}}
        </style></head><body>
          <div class="row" id="raf">raf 0</div>
          <div class="row" id="timer">timer 0</div>
          <div class="row" id="vis">vis ?</div>
          <div class="row" id="clock">clock 0</div>
          <div id="barwrap"><div id="bar"></div></div>
        <script>
          var rafN = 0, timerN = 0, t0 = Date.now();
          // Signal 2: requestAnimationFrame — tied to the compositor's frame callback.
          function frame(){ rafN++; document.getElementById('raf').textContent = 'raf ' + rafN;
                            requestAnimationFrame(frame); }
          requestAnimationFrame(frame);
          // Signal 3: a plain timer — this is what background throttling hits first.
          setInterval(function(){
            timerN++;
            document.getElementById('timer').textContent = 'timer ' + timerN;
            document.getElementById('vis').textContent   = 'vis ' + document.visibilityState;
            document.getElementById('clock').textContent = 'clock ' + (Date.now() - t0) + 'ms';
          }, 100);
          // Report to native twice a second so logcat carries the timeline too.
          setInterval(function(){
            try { Probe.report(rafN, timerN, document.visibilityState, Date.now() - t0); } catch(e){}
          }, 500);
          document.addEventListener('visibilitychange', function(){
            try { Probe.report(rafN, timerN, 'CHANGE:' + document.visibilityState, Date.now() - t0); } catch(e){}
          });
        </script></body></html>
    """.trimIndent()
}
