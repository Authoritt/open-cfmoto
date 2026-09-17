<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Browser on the dash — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax
> for tracking.

**Goal:** Add a `MapProvider.WEB` whose dash content is a browser rendered at the bike canvas size, whose
pixels also reach a live, interactive preview on the phone, so a touch on either surface lands on the
same page.

**Architecture:** One `WebView` in a `Presentation` on the dash `VirtualDisplay`. The VD renders into
`AaCompositor`'s `inputSurface` (a `SurfaceTexture`) instead of straight into the encoder; the compositor
fans out to the encoder input surface and, optionally, to a `SurfaceView` on the phone. Touches from both
surfaces funnel through `GpxSession.dispatchTouch`, which already dispatches `MotionEvent`s onto any
`View`.

**Tech Stack:** Kotlin, Jetpack Compose (cockpit UI), Android `WebView`, EGL/GLES via the existing
`AaCompositor`, JUnit4 + `TemporaryFolder` for unit tests, Gradle 9.4.1 / AGP 9.2.1 on JDK 17.

**Spec:** [`docs/superpowers/specs/2026-09-17-browser-on-the-dash-design.md`](../specs/2026-09-17-browser-on-the-dash-design.md)

## Global Constraints

- **Build with JDK 17.** `export JAVA_HOME="C:/Program Files/Java/jdk-17.0.5"`. The `java` on PATH is 8
  and cannot run AGP 9.
- **The map path must stay byte-for-byte unchanged.** The compositor is inserted **only** when the active
  provider is `WEB`. `BUILTIN` / Android Auto / mirror keep rendering straight into the encoder surface.
- **`MapProvider` is a persisted enum.** Append `WEB` at the end; never reorder or rename existing
  constants. The reader already tolerates unknown names
  (`runCatching { MapProvider.valueOf(...) }.getOrDefault(MapProvider.BUILTIN)`).
- **One pre-existing test failure is expected and is not yours:**
  `YunmoFrameTest.parseOkDimension_xCape1200Payload` (`expected:<2048> but was:<1024>`), failing since
  `9f77a95`. A green run is `N-1` passing with only that one red.
- **Search engine is Google**, hardcoded: `https://www.google.com/search?q=`. No preference screen.
- **Do not edit sources while a Gradle run is in flight** — a green from a half-written tree belongs to no
  tree.
- **`adb push` to `/sdcard/...` from Git Bash needs `MSYS_NO_PATHCONV=1`**, or the shell rewrites the path
  under the Git install and `adb` fails with a misleading `secure_mkdirs()` error.

---

### Task 1: Decide the networking route by measurement

The spec has two candidate solutions and says which one is right is a measurement. Do this first: Task 6
is written twice and you must know which half to implement.

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/BikeLink.kt` (temporary, reverted in step 5)

**Interfaces:**
- Consumes: nothing.
- Produces: a written verdict appended to this plan under "Task 1 result", which Task 6 reads.

- [ ] **Step 1: Find the process pin**

`BikeLink.maybeStartProber` calls `BikeWifi.rebindProcessToBike(ctx)` once AA video is steady and the
network is ready. Read it and confirm that is the only place the process gets pinned during a CFMOTO
session.

- [ ] **Step 2: Disable the pin behind a dev flag**

In `BikeLink.kt`, guard that single call:

```kotlin
appContext?.let { ctx ->
    if (!DashHtmlPanel.skipProcessPin(ctx)) {
        if (BikeWifi.rebindProcessToBike(ctx)) {
            LogBus.log("→ process bound to bike Wi-Fi (AA video is live)")
        }
    } else {
        LogBus.log("→ process pin SKIPPED (dev flag) — measuring whether PXC survives")
    }
}
```

Add the flag next to the existing panel flag in `DashHtmlPanel.kt`:

```kotlin
private const val KEY_SKIP_PIN = "skip_process_pin"

fun skipProcessPin(context: Context): Boolean =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_SKIP_PIN, false)

fun setSkipProcessPin(context: Context, on: Boolean) {
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_SKIP_PIN, on).apply()
}
```

Add a dev settings row beside "Panel HTML en el tablero" in `SettingsScreen.kt` that toggles it.

- [ ] **Step 3: Build and install**

```bash
cd E:/Desarrollo/Activos/opencfmoto-cockpit
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.5"
./gradlew :app:assembleRelease -PbuildNumber=81 --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
```

- [ ] **Step 4: Measure on the bike**

Turn the flag on, connect, and watch. Two questions, both answerable from the log:

1. **Does PXC still work?** Look for `framesSent` climbing in the `[EasyConnBikeLink] hb#N` heartbeats.
   `framesSent=0` with `video=true` means it does not.
2. **Does the WebView get internet?** Enable the HTML panel, then push this probe panel and read it on
   the dash:

```bash
MSYS_NO_PATHCONV=1 adb push net-probe.html \
  /sdcard/Android/data/dev.authoritforge.opencfmoto/files/paneles/panel.html
```

```html
<!doctype html><html><head><meta charset="utf-8"><style>
 body{margin:0;background:#000;color:#fff;font:34px system-ui;padding:24px}
 .ok{color:#4ade80} .bad{color:#f87171}
</style></head><body>
<div id="r">probando…</div>
<script>
  var t0 = Date.now();
  fetch('https://www.gstatic.com/generate_204', {cache:'no-store'})
    .then(function(x){ document.getElementById('r').innerHTML =
      '<span class="ok">HAY INTERNET</span><br>' + x.status + ' en ' + (Date.now()-t0) + ' ms'; })
    .catch(function(e){ document.getElementById('r').innerHTML =
      '<span class="bad">SIN INTERNET</span><br>' + e; });
</script></body></html>
```

- [ ] **Step 5: Record the verdict and revert the temporary flag**

Append to this file:

```markdown
## Task 1 result
- PXC without the process pin: WORKS / BREAKS (evidence: `hb#N framesSent=…`)
- WebView internet without the pin: YES / NO (evidence: the probe panel said …)
- Therefore Task 6 implements: **6A (no pin)** / **6B (intercept)**
```

Then `git revert` or hand-revert the `BikeLink.kt` guard — it was an instrument, not a feature. Keep the
`DashHtmlPanel` flag only if 6A is chosen (Task 6A makes it permanent and provider-scoped).

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans/2026-09-17-browser-on-the-dash.md
git commit -m "Task 1: measure whether PXC survives without the process pin"
```

---

### Task 2: `MapProvider.WEB` and its persistence safety

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/settings/SettingsStore.kt:19`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/MapProviderPersistenceTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `MapProvider.WEB`; `SettingsStore.setMapProvider(MapProvider)` already exists and is unchanged.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/dev/zanderp/opencfmoto/MapProviderPersistenceTest.kt
package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.settings.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `mapProvider` is persisted BY NAME, so the stored string of an already-paired install must keep
 * meaning what it meant. Renaming or reordering a constant silently repoints every existing install.
 */
class MapProviderPersistenceTest {

    @Test
    fun `the persisted names never change`() {
        assertEquals(
            listOf("BUILTIN", "GOOGLE", "WAZE", "MIRROR", "WEB"),
            MapProvider.entries.map { it.name },
        )
    }

    @Test
    fun `WEB is appended last so existing ordinals are untouched`() {
        assertEquals(MapProvider.entries.size - 1, MapProvider.WEB.ordinal)
    }

    @Test
    fun `an unknown stored name degrades to BUILTIN instead of throwing`() {
        val decoded = runCatching { MapProvider.valueOf("FROM_A_NEWER_BUILD") }
            .getOrDefault(MapProvider.BUILTIN)
        assertEquals(MapProvider.BUILTIN, decoded)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.5"
./gradlew :app:testDebugUnitTest --tests "*MapProviderPersistenceTest*" --console=plain
```
Expected: FAIL — `MapProvider.WEB` does not resolve.

- [ ] **Step 3: Add the constant**

`SettingsStore.kt:19`, appended last:

```kotlin
enum class MapProvider { BUILTIN, GOOGLE, WAZE, MIRROR, WEB }
```

- [ ] **Step 4: Run it and watch it pass**

Same command. Expected: 3 tests, 0 failures. Confirm the count in
`app/build/test-results/testDebugUnitTest/TEST-dev.zanderp.opencfmoto.MapProviderPersistenceTest.xml` —
a `--tests` filter that matches nothing also reports BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/settings/SettingsStore.kt \
        app/src/test/java/dev/zanderp/opencfmoto/MapProviderPersistenceTest.kt
git commit -m "Add MapProvider.WEB, with the persisted-name contract pinned by tests"
```

---

### Task 3: `DashBrowser` — the browser itself, and its pure parts

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowser.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/browser/DashBrowserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `DashBrowser.HOME_URL: String`
  - `DashBrowser.toNavigationUrl(typed: String): String` — pure; URL or Google search
  - `DashBrowser.errorPageHtml(url: String, reason: String): String` — pure
  - `DashBrowser.create(context: Context, onTitle: (String) -> Unit): WebView`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/dev/zanderp/opencfmoto/browser/DashBrowserTest.kt
package dev.zanderp.opencfmoto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashBrowserTest {

    @Test
    fun `a bare host becomes https`() {
        assertEquals("https://openstreetmap.org", DashBrowser.toNavigationUrl("openstreetmap.org"))
    }

    @Test
    fun `an explicit scheme is left alone`() {
        assertEquals("http://192.168.49.1:8080/", DashBrowser.toNavigationUrl("http://192.168.49.1:8080/"))
    }

    @Test
    fun `words become a Google search`() {
        assertEquals(
            "https://www.google.com/search?q=taller%20de%20motos",
            DashBrowser.toNavigationUrl("taller de motos"),
        )
    }

    /** "cali, colombia" has a dot-free comma: it is a phrase, not a host. */
    @Test
    fun `a phrase with punctuation is still a search`() {
        assertTrue(DashBrowser.toNavigationUrl("cali, colombia").startsWith("https://www.google.com/search"))
    }

    /** A lone dotted word with a space is a phrase, not a host. */
    @Test
    fun `dotted text containing a space is a search`() {
        assertTrue(DashBrowser.toNavigationUrl("ver mapa.com aqui").startsWith("https://www.google.com/search"))
    }

    @Test
    fun `blank input goes home rather than to an empty search`() {
        assertEquals(DashBrowser.HOME_URL, DashBrowser.toNavigationUrl("   "))
    }

    @Test
    fun `the error page names the url and the reason`() {
        val html = DashBrowser.errorPageHtml("https://x.test/a", "net::ERR_NAME_NOT_RESOLVED")
        assertTrue(html.contains("https://x.test/a"))
        assertTrue(html.contains("net::ERR_NAME_NOT_RESOLVED"))
    }

    /** The dash renders this at ~1024x464 seen from a metre away: it must not inherit body defaults. */
    @Test
    fun `the error page sets its own large type`() {
        assertTrue(DashBrowser.errorPageHtml("https://x.test", "boom").contains("font-size"))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*DashBrowserTest*" --console=plain
```
Expected: FAIL — unresolved reference `DashBrowser`.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowser.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder

/**
 * The browser projected to the bike dash. Rendered at the bike canvas size on the dash
 * VirtualDisplay, so every dimension here is read at a metre's distance.
 */
object DashBrowser {

    const val HOME_URL = "https://maps.google.com/"
    private const val SEARCH = "https://www.google.com/search?q="

    /** Looks like a host: no whitespace, has a dot or a scheme, and is not a sentence. */
    fun toNavigationUrl(typed: String): String {
        val t = typed.trim()
        if (t.isEmpty()) return HOME_URL
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.endsWith(".")
        return if (looksLikeHost) "https://$t"
        else SEARCH + URLEncoder.encode(t, "UTF-8").replace("+", "%20")
    }

    /** Chromium's own error page is unreadable on the dash; this one is not. */
    fun errorPageHtml(url: String, reason: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><style>
          html,body{margin:0;height:100%;background:#000;color:#fff;
                    font-family:system-ui,-apple-system,sans-serif;display:flex;
                    align-items:center;justify-content:center;text-align:center}
          .w{padding:0 40px}
          .t{font-size:46px;font-weight:700;color:#FF6A2C}
          .u{font-size:24px;color:#8a8a8a;margin-top:14px;word-break:break-all}
          .r{font-size:20px;color:#5a5a5a;margin-top:10px}
        </style></head><body><div class="w">
          <div class="t">No se pudo abrir</div>
          <div class="u">$url</div>
          <div class="r">$reason</div>
        </div></body></html>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, onTitle: (String) -> Unit): WebView =
        WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    view.loadDataWithBaseURL(
                        null,
                        errorPageHtml(request.url.toString(), error.description.toString()),
                        "text/html", "utf-8", null,
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    onTitle(view.title ?: url)
                }
            }
        }
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*DashBrowserTest*" --console=plain
```
Expected: 8 tests, 0 failures. Verify the count in the XML report.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowser.kt \
        app/src/test/java/dev/zanderp/opencfmoto/browser/DashBrowserTest.kt
git commit -m "DashBrowser: the dash-sized browser, with URL-vs-search and a readable error page"
```

---

### Task 4: Touch mapping from the phone preview

Pure arithmetic, extracted so it can be tested without a device. Wrong here does not crash: every touch
lands a few centimetres off, which is invisible by inspection.

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/browser/PreviewTouchMap.kt`
- Test: `app/src/test/java/dev/zanderp/opencfmoto/browser/PreviewTouchMapTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `PreviewTouchMap.toCanvas(previewX: Float, previewY: Float, previewW: Int, previewH: Int, canvasW: Int, canvasH: Int): Pair<Int, Int>?`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/dev/zanderp/opencfmoto/browser/PreviewTouchMapTest.kt
package dev.zanderp.opencfmoto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewTouchMapTest {

    /** The preview is letterboxed: same aspect as the canvas, so the map is a pure scale. */
    @Test
    fun `centre maps to centre`() {
        val p = PreviewTouchMap.toCanvas(256f, 116f, 512, 232, 1024, 464)
        assertEquals(512 to 232, p)
    }

    @Test
    fun `the top-left corner maps to the origin`() {
        assertEquals(0 to 0, PreviewTouchMap.toCanvas(0f, 0f, 512, 232, 1024, 464))
    }

    /** The last pixel must stay inside the canvas, not land one past the edge. */
    @Test
    fun `the bottom-right corner clamps to the last pixel`() {
        assertEquals(1023 to 463, PreviewTouchMap.toCanvas(512f, 232f, 512, 232, 1024, 464))
    }

    @Test
    fun `a touch outside the preview is rejected rather than clamped`() {
        assertNull(PreviewTouchMap.toCanvas(-1f, 10f, 512, 232, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(10f, -1f, 512, 232, 1024, 464))
    }

    @Test
    fun `a zero-sized preview yields null instead of dividing by zero`() {
        assertNull(PreviewTouchMap.toCanvas(1f, 1f, 0, 232, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(1f, 1f, 512, 0, 1024, 464))
    }

    /** A preview larger than the canvas (big phone, small dash) must still map correctly. */
    @Test
    fun `an upscaled preview maps back down`() {
        assertEquals(512 to 232, PreviewTouchMap.toCanvas(1024f, 464f, 2048, 928, 1024, 464))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*PreviewTouchMapTest*" --console=plain
```
Expected: FAIL — unresolved reference `PreviewTouchMap`.

- [ ] **Step 3: Implement**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/browser/PreviewTouchMap.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.browser

/**
 * Phone-preview coordinates → bike-canvas coordinates.
 *
 * The preview is laid out at the canvas aspect ratio (letterboxed by the layout, not by this map), so
 * this is a pure scale. Kept separate from the UI purely so it can be tested: a wrong scale here does
 * not crash, it just puts every touch a few centimetres from where the rider aimed.
 */
object PreviewTouchMap {

    fun toCanvas(
        previewX: Float,
        previewY: Float,
        previewW: Int,
        previewH: Int,
        canvasW: Int,
        canvasH: Int,
    ): Pair<Int, Int>? {
        if (previewW <= 0 || previewH <= 0 || canvasW <= 0 || canvasH <= 0) return null
        if (previewX < 0f || previewY < 0f || previewX > previewW || previewY > previewH) return null
        val x = (previewX / previewW * canvasW).toInt().coerceIn(0, canvasW - 1)
        val y = (previewY / previewH * canvasH).toInt().coerceIn(0, canvasH - 1)
        return x to y
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Expected: 6 tests, 0 failures. Verify the count in the XML report.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/browser/PreviewTouchMap.kt \
        app/src/test/java/dev/zanderp/opencfmoto/browser/PreviewTouchMapTest.kt
git commit -m "PreviewTouchMap: phone preview coordinates to the bike canvas"
```

---

### Task 5: Render the browser on the dash through the compositor

**Files:**
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt` — the own-content branch
  (`setupHtmlPanelPresentation` neighbourhood) and `createOwnVirtualDisplay`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt` — `stop()` teardown

**Interfaces:**
- Consumes: `DashBrowser.create`, `DashBrowser.HOME_URL`, `AaCompositor`.
- Produces: `VideoPipeline.browserView: WebView?` (internal, read by Task 7 to drive navigation) and
  `VideoPipeline.browserCompositor: AaCompositor?` (internal, read by Task 7 to attach the preview).

- [ ] **Step 1: Add the branch and the fields**

In the content-selection chain, ahead of the GPX branch and beside the HTML-panel branch:

```kotlin
} else if (probePresentationContent == null && DashBrowserPrefs.isEnabled(context)) {
    log("[VIDEO] browser mode (Presentation via compositor)")
    if (Looper.myLooper() == Looper.getMainLooper()) setupBrowserPresentation()
    else main.post { setupBrowserPresentation() }
}
```

Fields beside `htmlPanelView`:

```kotlin
/** DEV/WEB provider: the projected browser and the GL stage that fans it out. */
internal var browserView: android.webkit.WebView? = null
internal var browserCompositor: AaCompositor? = null

/**
 * The negotiated bike canvas, so UI can size a preview without guessing. `BikeProfile` does NOT
 * carry a canvas size — the bike negotiates its dimensions at connect time, so the live pipeline is
 * the only place that knows them.
 */
internal val canvasWidth: Int get() = width
internal val canvasHeight: Int get() = height
```

- [ ] **Step 2: Write `setupBrowserPresentation`**

```kotlin
/**
 * Own-content mode where the Presentation hosts a browser, and — unlike every other own-content
 * path — the VirtualDisplay does NOT render straight into the encoder. It renders into an
 * [AaCompositor] input SurfaceTexture, so the same frames can also reach a phone preview
 * ([AaCompositor.setPreview]). That fan-out is the whole point of the WEB provider: one browser,
 * two surfaces, which is why the phone and the dash can never show different pages.
 */
private fun setupBrowserPresentation() {
    try {
        val comp = AaCompositor(log)
        comp.start(bufferW = width, bufferH = height)
        val input = comp.inputSurface ?: run {
            log("[VIDEO] browser: compositor gave no input surface — falling back to native content")
            comp.release(); setupDisplayAndPresentation(); return
        }
        val encSurface = inputSurface ?: run {
            log("[VIDEO] browser: no encoder surface yet — falling back to native content")
            comp.release(); setupDisplayAndPresentation(); return
        }
        comp.setOutput(encSurface, width, height, width, height, ScreenFit.STRETCH)
        browserCompositor = comp

        val display = createOwnVirtualDisplay(target = input) ?: run {
            comp.release(); browserCompositor = null; return
        }
        val pres = Presentation(context, display)
        val host = FrameLayout(pres.context)
        val wv = dev.zanderp.opencfmoto.browser.DashBrowser.create(pres.context) { title ->
            log("[VIDEO] browser title: $title")
        }
        host.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pres.setContentView(host)
        pres.show()
        presentation = pres
        browserView = wv
        wv.loadUrl(dev.zanderp.opencfmoto.browser.DashBrowser.HOME_URL)
        // Dash touches land here 1:1 — the WebView is laid out at exactly the canvas size.
        GpxSession.setTouchTarget(wv)
        // Same wake the map takes: without it the WifiNetworkSpecifier request is released the
        // moment the app stops being visible and every PXC socket dies at once.
        AndroidAutoService.setGpxScreenWake(context, true)
        log("[VIDEO] browser shown on virtual display ${width}x$height")
    } catch (e: Exception) {
        log("[VIDEO] browser failed: $e — falling back to native content")
        try { browserCompositor?.release() } catch (_: Exception) {}
        browserCompositor = null
        setupDisplayAndPresentation()
    }
}
```

- [ ] **Step 3: Let `createOwnVirtualDisplay` take a target surface**

It currently hardcodes `inputSurface`. Give it a default so every existing caller is unchanged:

```kotlin
private fun createOwnVirtualDisplay(target: Surface? = null): android.view.Display? {
    // …unchanged…
    val surface = target ?: inputSurface
    val vd = dm.createVirtualDisplay("OpenCfMoto", width, height, densityDpi, surface, flags)
    // …unchanged…
}
```

- [ ] **Step 4: Tear it down in `stop()`**

Beside the HTML-panel teardown, and **before** `presentation?.dismiss()`:

```kotlin
// Clear the touch target first: a MotionEvent posted after the WebView is destroyed would land
// on a dead view.
try { GpxSession.clearTouchTarget() } catch (_: Exception) {}
try {
    browserView?.let { it.loadUrl("about:blank"); (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
} catch (_: Exception) {}
browserView = null
try { browserCompositor?.release() } catch (_: Exception) {}
browserCompositor = null
```

Extend the wake release condition to include the browser:

```kotlin
if (abandonNavigation || gpxDashUi != null || htmlPanelView != null || browserView != null) {
    AndroidAutoService.setGpxScreenWake(context, false)
}
```

- [ ] **Step 5: Add `DashBrowserPrefs`**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowserPrefs.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.browser

import android.content.Context

/**
 * Whether the projected content is the browser. Read by `VideoPipeline` on a non-UI path, so it is a
 * plain SharedPreferences flag rather than the DataStore `mapProvider` Flow — the pipeline cannot
 * suspend. `MapProvider.WEB` is the user-facing truth; Task 7 mirrors it here on every change.
 */
object DashBrowserPrefs {
    private const val PREFS = "dash_browser"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
```

- [ ] **Step 6: Build**

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.5"
./gradlew :app:assembleDebug --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Verify on the phone, with no bike**

Enable the flag, run the existing `HtmlPanelPreview` equivalent for the browser (or temporarily point
`HtmlPanelPreview` at the browser branch), pull the dump and decode a frame:

```bash
MSYS_NO_PATHCONV=1 adb pull \
  /sdcard/Android/data/dev.authoritforge.opencfmoto/files/video/opencfmoto-video-own.h264 b.h264
ffmpeg -v error -i b.h264 -frames:v 1 -update 1 b.png
```
Expected: `b.png` shows Google Maps web, not a blue placeholder.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/VideoPipeline.kt \
        app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowserPrefs.kt
git commit -m "Project the browser through the compositor so its frames can fan out to the phone"
```

---

### Task 6: Give the browser a route to the internet

**Implement only the half Task 1 selected.** Read "Task 1 result" before starting.

**Files:**
- 6A: Modify `app/src/main/java/dev/zanderp/opencfmoto/BikeLink.kt`
- 6B: Modify `app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowser.kt`

#### 6A — do not pin the process while the browser is the provider

- [ ] **Step 1: Make the skip provider-scoped, not a dev flag**

In `BikeLink.maybeStartProber`, replace the temporary Task 1 guard with:

```kotlin
appContext?.let { ctx ->
    // The browser provider needs the default route to reach the internet; PXC does not depend on
    // the process pin because EasyConnProber and YunmoLink bind their own sockets. Measured in
    // Task 1 of the browser-on-the-dash plan.
    if (dev.zanderp.opencfmoto.browser.DashBrowserPrefs.isEnabled(ctx)) {
        LogBus.log("→ process pin skipped (browser provider): PXC keeps its own socket binds")
    } else if (BikeWifi.rebindProcessToBike(ctx)) {
        LogBus.log("→ process bound to bike Wi-Fi (AA video is live)")
    }
}
```

- [ ] **Step 2: Build, install, connect, confirm both halves still hold**

`hb#N framesSent` climbing **and** the net-probe panel reporting internet. Paste both into the commit
message — this is the claim the whole provider rests on.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/BikeLink.kt
git commit -m "Leave the process route free while the browser is projecting"
```

#### 6B — resolve every request over the internet uplink

- [ ] **Step 1: Add the interceptor to `DashBrowser.create`**

```kotlin
override fun shouldInterceptRequest(
    view: WebView,
    request: android.webkit.WebResourceRequest,
): android.webkit.WebResourceResponse? {
    if (!request.method.equals("GET", ignoreCase = true)) return null  // no body available: let it try
    return try {
        val req = okhttp3.Request.Builder()
            .url(request.url.toString())
            .apply { request.requestHeaders.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        val resp = dev.zanderp.opencfmoto.AppHttp.mapLibreOkHttpClient().newCall(req).execute()
        val ct = resp.header("Content-Type") ?: "text/plain"
        val mime = ct.substringBefore(';').trim()
        val charset = ct.substringAfter("charset=", "utf-8").trim()
        android.webkit.WebResourceResponse(mime, charset, resp.body?.byteStream())
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 2: Record the limitation where it will be read**

Add to the KDoc of `DashBrowser`:

```kotlin
/**
 * NOTE: `WebResourceRequest` exposes no POST body, so non-GET requests are passed through
 * unintercepted and will fail while the process is pinned to the bike network. Google Maps web
 * leans on POST/XHR, so the default home page may not work under 6B — that is the known cost of
 * this route and the reason 6A is preferred.
 */
```

- [ ] **Step 3: Build, install, and record what actually loads**

Try: a plain page, an image-heavy page, and Google Maps. Write the three outcomes into the commit
message. Do not claim it works because one of them did.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/browser/DashBrowser.kt
git commit -m "Resolve browser GETs over the pinned internet uplink"
```

---

### Task 7: The phone control screen

**Files:**
- Create: `app/src/main/java/dev/zanderp/opencfmoto/ui/cockpit/BrowserControlScreen.kt`
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/CockpitActivity.kt:284` (add the route) and its
  `NavHost` (add the composable)
- Modify: `app/src/main/java/dev/zanderp/opencfmoto/ui/cockpit/CockpitScreen.kt:129` (the provider
  selector)

**Interfaces:**
- Consumes: `VideoPipeline.browserView`, `VideoPipeline.browserCompositor`, `PreviewTouchMap.toCanvas`,
  `DashBrowser.toNavigationUrl`, `GpxSession.dispatchTouch`, `DashBrowserPrefs.setEnabled`.
- Produces: `Routes.BROWSER = "browser"`.

- [ ] **Step 1: Add the route**

In `object Routes` (`CockpitActivity.kt:284`):

```kotlin
const val BROWSER = "browser"
```

- [ ] **Step 2: Mirror the provider into the prefs flag**

Wherever the map provider is chosen (`CockpitScreen.kt`), after `store.setMapProvider(p)`:

```kotlin
DashBrowserPrefs.setEnabled(ctx, p == MapProvider.WEB)
if (p == MapProvider.WEB) nav.navigate(Routes.BROWSER)
```

- [ ] **Step 3: Write the screen**

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/ui/cockpit/BrowserControlScreen.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.ui.cockpit

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zanderp.opencfmoto.GpxSession
import dev.zanderp.opencfmoto.browser.DashBrowser
import dev.zanderp.opencfmoto.browser.PreviewTouchMap

/**
 * Drives the ONE browser that lives on the dash VirtualDisplay. This screen never hosts a second
 * WebView: it shows the same frames (through the compositor's preview surface) and forwards touches
 * into the same view, which is what keeps the phone and the dash from drifting apart.
 */
@Composable
fun BrowserControlScreen(canvasW: Int, canvasH: Int) {
    val ctx = LocalContext.current
    var typed by remember { mutableStateOf("") }
    // Spec §4: the rider must never drive a surface that is going nowhere.
    val conn by dev.zanderp.opencfmoto.ConnectionState.flow
        .collectAsStateWithLifecycle(initialValue = null)

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                conn == null -> "Sin conectar"
                conn!!.phase.busy -> "Conectando…"
                else -> "En el tablero"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                label = { Text("Dirección o búsqueda") },
            )
            Button(onClick = {
                val url = DashBrowser.toNavigationUrl(typed)
                dev.zanderp.opencfmoto.VideoPipelineHolder.browser()?.post {
                    dev.zanderp.opencfmoto.VideoPipelineHolder.browser()?.loadUrl(url)
                }
            }) { Text("Ir") }
        }

        // The preview keeps the dash aspect ratio: what you touch is what is transmitted.
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(canvasW.toFloat() / canvasH),
            factory = { c ->
                SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            dev.zanderp.opencfmoto.VideoPipelineHolder.compositor()
                                ?.setPreview(h.surface, width, height)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                            dev.zanderp.opencfmoto.VideoPipelineHolder.compositor()
                                ?.updatePreviewSize(w, hh)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            dev.zanderp.opencfmoto.VideoPipelineHolder.compositor()?.clearPreview()
                        }
                    })
                    setOnTouchListener { v, e ->
                        val action = when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> 0
                            MotionEvent.ACTION_UP -> 1
                            MotionEvent.ACTION_MOVE -> 2
                            else -> return@setOnTouchListener false
                        }
                        PreviewTouchMap.toCanvas(e.x, e.y, v.width, v.height, canvasW, canvasH)
                            ?.let { (cx, cy) -> GpxSession.dispatchTouch(action, cx, cy) }
                        true
                    }
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                dev.zanderp.opencfmoto.VideoPipelineHolder.browser()?.let { if (it.canGoBack()) it.goBack() }
            }) { Text("Atrás") }
            Button(onClick = {
                dev.zanderp.opencfmoto.VideoPipelineHolder.browser()?.let { if (it.canGoForward()) it.goForward() }
            }) { Text("Adelante") }
            Button(onClick = {
                dev.zanderp.opencfmoto.VideoPipelineHolder.browser()?.reload()
            }) { Text("Recargar") }
            Button(onClick = {
                dev.zanderp.opencfmoto.VideoPipelineHolder.browser()?.loadUrl(DashBrowser.HOME_URL)
            }) { Text("Mapa") }
        }
    }
}
```

- [ ] **Step 4: Add the holder the screen reads**

The screen must not hold a `VideoPipeline` reference (it outlives composition). Add a tiny process-global:

```kotlin
// app/src/main/java/dev/zanderp/opencfmoto/VideoPipelineHolder.kt
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto

/**
 * The live browser pipeline, for UI that must drive it without owning it. Set by [VideoPipeline] when
 * the browser presentation comes up and cleared on stop, so a screen composed after a disconnect gets
 * null rather than a destroyed WebView.
 */
object VideoPipelineHolder {
    @Volatile private var pipeline: VideoPipeline? = null

    internal fun set(p: VideoPipeline?) { pipeline = p }
    fun browser(): android.webkit.WebView? = pipeline?.browserView
    fun compositor(): AaCompositor? = pipeline?.browserCompositor

    /** The canvas the browser is actually laid out at, straight from the live pipeline. */
    fun canvasSize(): Pair<Int, Int>? = pipeline?.let { it.canvasWidth to it.canvasHeight }
}
```

Set it in `setupBrowserPresentation` (`VideoPipelineHolder.set(this)`) and clear it in the Task 5
teardown (`VideoPipelineHolder.set(null)`).

- [ ] **Step 5: Register the route in the NavHost**

Beside the other `composable(Routes.X)` entries in `CockpitActivity.kt`:

```kotlin
composable(Routes.BROWSER) {
    val (cw, ch) = VideoPipelineHolder.canvasSize() ?: (1024 to 464)
    BrowserControlScreen(canvasW = cw, canvasH = ch)
}
```

The size comes from the **live pipeline**, not from `BikeProfileHolder`: the profile carries no canvas
size, and the bike negotiates its dimensions at connect time. The `1024 x 464` fallback only applies
before a pipeline exists, when the preview has nothing to show anyway.

- [ ] **Step 6: Build and exercise on the phone without a bike**

```bash
./gradlew :app:assembleRelease -PbuildNumber=82 --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
```
Select the WEB provider, confirm the screen opens, the preview paints, typing a search navigates, and a
touch on the preview moves the map.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/zanderp/opencfmoto/ui/cockpit/BrowserControlScreen.kt \
        app/src/main/java/dev/zanderp/opencfmoto/VideoPipelineHolder.kt \
        app/src/main/java/dev/zanderp/opencfmoto/ui/CockpitActivity.kt \
        app/src/main/java/dev/zanderp/opencfmoto/ui/cockpit/CockpitScreen.kt
git commit -m "Phone control screen: drive the dash browser and see exactly what it transmits"
```

---

### Task 8: Field verification and the regression gate

**Files:**
- Modify: `docs/08-FIELD-STATE.md`
- Modify: `knowledge/open-cfmoto/LEARNINGS.md` (in the AgentHub repo, `E:\Desarrollo\AgentHub`)

- [ ] **Step 1: Run the whole suite**

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.5"
./gradlew :app:testDebugUnitTest --console=plain
```
Expected: only `YunmoFrameTest.parseOkDimension_xCape1200Payload` red.

- [ ] **Step 2: Prove the map path did not regress**

Select `BUILTIN`, connect, and confirm the map still projects and survives a screen-off. The compositor
must not appear in the log for this provider: `grep -i "browser mode" ` on the session log must be empty.

- [ ] **Step 3: Field-test the browser on the bike**

Checklist, each answered from evidence rather than impression:
1. The dash paints the browser (photo).
2. A touch on the dash moves the map (photo before/after).
3. A touch on the phone preview moves the dash the same way.
4. Typing a search on the phone navigates the dash.
5. Screen off for 60 s: the dash keeps rendering and the link stays up
   (`hb#N framesSent` climbing).

- [ ] **Step 4: Write down what the bike said**

Append a thread to `docs/08-FIELD-STATE.md` with the five answers, including anything that failed.

- [ ] **Step 5: Commit**

```bash
git add docs/08-FIELD-STATE.md
git commit -m "Field state: what the bike said about the browser provider"
```
