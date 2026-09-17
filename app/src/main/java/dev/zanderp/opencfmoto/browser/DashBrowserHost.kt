// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.webkit.WebView
import android.widget.FrameLayout
import dev.zanderp.opencfmoto.AaCompositor
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.ScreenFit

/**
 * The browser, alive on its own.
 *
 * **The first version tied the browser's existence to the bike pipeline** — the WebView was created by
 * `VideoPipeline` when projection started, so with no bike there was no browser at all: typing a URL and
 * pressing Go did nothing. That is backwards. The rider wants a browser they can use like Chrome, which
 * *also* reaches the dash; the dash is one more output, not the browser's reason to exist.
 *
 * So the host owns everything and outlives any connection:
 *
 * ```
 *   WebView on a Presentation over a private VirtualDisplay (dash-sized)
 *                    |
 *              SurfaceTexture
 *                    |
 *              AaCompositor ──► preview surface  (phone, whenever the control screen is open)
 *                           └─► encoder surface  (bike, whenever a projection is running)
 *  ```
 *
 * Neither output is required. With no bike you still browse; with no control screen open the dash still
 * gets frames. Touches from both surfaces funnel into the same WebView through [GpxSession.dispatchTouch].
 */
object DashBrowserHost {

    /** Ride MO's NaviVirtualDisplay size — the canvas until a bike reports its own. */
    private const val DEFAULT_W = 1024
    private const val DEFAULT_H = 464

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var compositor: AaCompositor? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    @Volatile private var webView: WebView? = null
    @Volatile private var canvasW = DEFAULT_W
    @Volatile private var canvasH = DEFAULT_H

    val isRunning: Boolean get() = webView != null
    fun browser(): WebView? = webView
    fun compositor(): AaCompositor? = compositor
    fun canvasSize(): Pair<Int, Int> = canvasW to canvasH

    /**
     * Bring the browser up if it is not already. Safe to call repeatedly and from any thread; the
     * display/Presentation work is posted to the main thread because a Presentation is a Dialog.
     */
    fun ensureStarted(context: Context) {
        if (webView != null) return
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) startOnMain(app) else main.post { startOnMain(app) }
    }

    private fun startOnMain(app: Context) {
        if (webView != null) return
        try {
            val comp = AaCompositor(LogBus::log).also { it.start(bufferW = canvasW, bufferH = canvasH) }
            val input = comp.inputSurface ?: run {
                LogBus.log("[BROWSER] compositor gave no input surface — browser not started")
                comp.release(); return
            }
            compositor = comp

            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val densityDpi = 187 // Ride MO's own dpi for this canvas — keeps web text dash-sized
            val vd = dm.createVirtualDisplay("OpenCfMoto-Browser", canvasW, canvasH, densityDpi, input, flags)
            val display = vd?.display ?: run {
                LogBus.log("[BROWSER] no virtual display — browser not started")
                comp.release(); compositor = null; return
            }
            virtualDisplay = vd

            val pres = Presentation(app, display)
            val host = FrameLayout(pres.context)
            val wv = DashBrowser.create(pres.context) { title ->
                LogBus.log("[BROWSER] $title")
                // A navigation wipes the injected watcher, so it goes back in after every load.
                installFocusWatcher()
            }
            wv.addJavascriptInterface(KeyBridge(), "OvkKeys")
            wv.setBackgroundColor(Color.BLACK)
            host.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            pres.setContentView(host)
            pres.show()
            presentation = pres
            webView = wv
            wv.loadUrl(DashBrowser.HOME_URL)
            // Dash touches land 1:1 — the WebView is laid out at exactly the canvas size.
            GpxSession.setTouchTarget(wv)
            LogBus.log("[BROWSER] up on its own display ${canvasW}x$canvasH (no bike needed)")
        } catch (e: Exception) {
            LogBus.log("[BROWSER] start failed: $e")
            stop()
        }
    }

    /** Show the same frames on the phone. Called by the control screen's SurfaceView. */
    fun attachPreview(surface: Surface, w: Int, h: Int) {
        compositor?.setPreview(surface, w, h)
    }

    fun updatePreviewSize(w: Int, h: Int) {
        compositor?.updatePreviewSize(w, h)
    }

    fun detachPreview() {
        compositor?.clearPreview()
    }

    /**
     * Send the same frames to the bike. Called by `VideoPipeline` when a projection starts; the browser
     * does not wait for it and does not stop when it goes away.
     */
    fun attachEncoder(encoderSurface: Surface, w: Int, h: Int) {
        compositor?.setOutput(encoderSurface, w, h, canvasW, canvasH, ScreenFit.FIT)
        LogBus.log("[BROWSER] encoder attached ${w}x$h (browser canvas ${canvasW}x$canvasH)")
    }

    /**
     * Stop sending frames to the bike, WITHOUT stopping the browser.
     *
     * `VideoPipeline.stop()` releases the encoder input surface. Since the browser stopped belonging
     * to the pipeline, the compositor survives that release still holding an EGL surface on the dead
     * window -- and the next idle redraw would fail there and leave the GL context invalid for the
     * phone preview drawn in the same pass. So the encoder is handed back first.
     */
    fun detachEncoder() {
        compositor?.clearOutput()
        LogBus.log("[BROWSER] encoder detached (page stays)")
    }

    fun navigate(typed: String) {
        val url = DashBrowser.toNavigationUrl(typed)
        webView?.let { wv -> wv.post { wv.loadUrl(url) } }
    }

    fun home() = webView?.let { wv -> wv.post { wv.loadUrl(DashBrowser.HOME_URL) } }

    /**
     * Send a touch straight into the browser, in its own canvas coordinates.
     *
     * Deliberately NOT via `GpxSession.dispatchTouch`: that routes through a process-global touch
     * target which `VideoPipeline.stop()` clears, so any pipeline teardown anywhere silently left the
     * browser deaf to the phone. Measured: taps on the preview did nothing at all. The dash's own
     * touches still arrive through GpxSession (that is the PXC path), and [reassertDashTouchTarget]
     * keeps that pointing here.
     */
    fun dispatchTouch(action: Int, canvasX: Int, canvasY: Int) {
        val wv = webView ?: run {
            LogBus.log("[BROWSER] touch $action ($canvasX,$canvasY) DROPPED — no webview")
            return
        }
        val motion = when (action) {
            0 -> android.view.MotionEvent.ACTION_DOWN
            1 -> android.view.MotionEvent.ACTION_UP
            2 -> android.view.MotionEvent.ACTION_MOVE
            else -> return
        }
        // A gesture shorter than a blink is discarded as noise: Chromium's gesture detector wants
        // something that looks like a finger. Measured on the device, DOWN and UP arrived 4 ms apart
        // and Google Maps ignored the tap entirely even though the WebView reported handled=true.
        // Holding the UP back to a human-looking duration is what turns it into a click.
        val delay = if (motion == android.view.MotionEvent.ACTION_UP) {
            val held = android.os.SystemClock.uptimeMillis() - downAt
            (MIN_TAP_MS - held).coerceIn(0L, MIN_TAP_MS)
        } else {
            0L
        }

        val send = Runnable {
            val t = android.os.SystemClock.uptimeMillis()
            val down = downAt.takeIf { it != 0L } ?: t
            val ev = android.view.MotionEvent.obtain(
                down, t, motion, canvasX.toFloat(), canvasY.toFloat(), 0,
            )
            if (motion == android.view.MotionEvent.ACTION_DOWN) downAt = t
            if (motion == android.view.MotionEvent.ACTION_UP) downAt = 0L
            try { wv.dispatchTouchEvent(ev) } finally { ev.recycle() }
        }
        if (delay > 0L) wv.postDelayed(send, delay) else wv.post(send)
    }

    /** A gesture must share one down-time, and last long enough to read as a finger rather than noise. */
    @Volatile private var downAt = 0L
    private const val MIN_TAP_MS = 90L

    /** Point the dash's PXC touch path back at the browser (a pipeline stop clears it). */
    fun reassertDashTouchTarget() {
        webView?.let { GpxSession.setTouchTarget(it) }
    }

    /**
     * Called by the control screen when the rider set the page focus on something editable, so the
     * phone can raise the soft keyboard. Set to null when that screen goes away.
     */
    @Volatile var onEditableFocus: ((Boolean) -> Unit)? = null

    /** @return true if the page had history to go back through — the system Back key defers to this. */
    fun goBackIfPossible(): Boolean {
        val wv = webView ?: return false
        var went = false
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            if (wv.canGoBack()) { wv.goBack(); went = true }
            latch.countDown()
        }
        // The caller is a key handler and must answer now; a page step is a few ms.
        runCatching { latch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return went
    }

    /**
     * Put [text] into whatever the page has focused.
     *
     * Not a key event: a soft keyboard emits none for ordinary characters, and the page is on another
     * display so there is nothing to type into anyway. Inserting through the DOM also fires `input`,
     * which is what React/Angular-style pages (Google's included) actually listen to — setting `.value`
     * alone would leave their internal state untouched and the search box would "forget" what it shows.
     */
    fun typeText(text: String) = evalReporting(
        "typeText ${jsString(text)}",
        """
        (function(t){
          var e=document.activeElement;
          if(!e) return 'no-activeElement';
          var tag=(e.tagName||'?')+(e.type?('['+e.type+']'):'');
          if(e.isContentEditable){
            document.execCommand('insertText',false,t);
            return 'contentEditable '+tag+' ok';
          }
          if(typeof e.value!=='string') return 'not-editable '+tag;
          var s=(e.selectionStart==null)?e.value.length:e.selectionStart;
          var f=(e.selectionEnd==null)?s:e.selectionEnd;
          e.value=e.value.slice(0,s)+t+e.value.slice(f);
          try{ e.selectionStart=e.selectionEnd=s+t.length; }catch(_){}
          e.dispatchEvent(new Event('input',{bubbles:true}));
          return 'inserted '+tag+' value='+JSON.stringify(e.value);
        })(${jsString(text)});
        """.trimIndent(),
    )

    fun pressBackspace() = evalOnFocused(
        """
        (function(){
          var e=document.activeElement; if(!e) return;
          if(e.isContentEditable){ document.execCommand('delete',false,null); return; }
          if(typeof e.value!=='string') return;
          var s=(e.selectionStart==null)?e.value.length:e.selectionStart;
          var f=(e.selectionEnd==null)?s:e.selectionEnd;
          if(s===f){ if(s===0) return; e.value=e.value.slice(0,s-1)+e.value.slice(f); s=s-1; }
          else { e.value=e.value.slice(0,s)+e.value.slice(f); }
          try{ e.selectionStart=e.selectionEnd=s; }catch(_){}
          e.dispatchEvent(new Event('input',{bubbles:true}));
        })();
        """.trimIndent(),
    )

    /** Submit: many search boxes only act on a real Enter keydown, not on form.submit(). */
    fun pressEnter() = evalOnFocused(
        """
        (function(){
          var e=document.activeElement; if(!e) return;
          ['keydown','keypress','keyup'].forEach(function(t){
            e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
          });
          if(e.form && typeof e.form.requestSubmit==='function'){ try{ e.form.requestSubmit(); }catch(_){} }
        })();
        """.trimIndent(),
    )

    private fun evalOnFocused(js: String) {
        webView?.let { wv -> wv.post { runCatching { wv.evaluateJavascript(js, null) } } }
    }

    /**
     * Same, but the script returns what it found and did, and that lands in the log. Typing into a page
     * fails silently by nature — the call succeeds, the page simply ignores it — so the only way to tell
     * "no focused element" from "focused something unwritable" from "wrote and the page repainted over
     * it" is to have the page say so.
     */
    private fun evalReporting(what: String, js: String) {
        val wv = webView ?: run { LogBus.log("[BROWSER] $what — no webview"); return }
        wv.post {
            runCatching {
                wv.evaluateJavascript(js) { result -> LogBus.log("[BROWSER] $what -> $result") }
            }.onFailure { LogBus.log("[BROWSER] $what — eval failed: $it") }
        }
    }

    /** JSON-quotes a string so it can be pasted into injected JS without breaking it. */
    private fun jsString(s: String): String =
        org.json.JSONObject.quote(s)

    /**
     * The page's way to tell the phone that focus landed on (or left) something typeable. Runs on a
     * WebView thread, so it hops to the main thread before touching any UI.
     */
    private class KeyBridge {
        @android.webkit.JavascriptInterface
        fun editableFocus(focused: Boolean) {
            main.post { onEditableFocus?.invoke(focused) }
        }
    }

    /**
     * Watch the page for focus landing on an editable element. Injected after every page load, because
     * a navigation wipes it. Without this the keyboard would either never appear or always appear.
     */
    internal fun installFocusWatcher() {
        evalOnFocused(
            """
            (function(){
              if(window.__ovkFocusWatch) return; window.__ovkFocusWatch=1;
              function editable(e){
                if(!e) return false;
                if(e.isContentEditable) return true;
                var t=(e.tagName||'').toUpperCase();
                if(t==='TEXTAREA') return true;
                if(t!=='INPUT') return false;
                var k=(e.type||'text').toLowerCase();
                return ['text','search','url','email','tel','number','password'].indexOf(k)>=0;
              }
              document.addEventListener('focusin',function(ev){
                if(editable(ev.target)) OvkKeys.editableFocus(true);
              },true);
              document.addEventListener('focusout',function(){
                setTimeout(function(){ OvkKeys.editableFocus(editable(document.activeElement)); },50);
              },true);
            })();
            """.trimIndent(),
        )
    }

    fun stop() {
        main.post {
            try { GpxSession.clearTouchTarget() } catch (_: Exception) {}
            try {
                webView?.let {
                    it.loadUrl("about:blank")
                    (it.parent as? android.view.ViewGroup)?.removeView(it)
                    it.destroy()
                }
            } catch (_: Exception) {}
            webView = null
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { compositor?.release() } catch (_: Exception) {}
            compositor = null
            LogBus.log("[BROWSER] stopped")
        }
    }
}
