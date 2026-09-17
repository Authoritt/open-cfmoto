// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder

/**
 * The browser projected to the bike dash.
 *
 * It renders at the bike canvas size on the dash VirtualDisplay, which is read at a metre's distance
 * on a moving motorcycle — so every size here is deliberate, and Chromium's own chrome (error pages,
 * zoom controls) is either replaced or hidden.
 *
 * A WebView is our OWN View in our OWN process, which is why this is possible at all: the
 * `ADD_TRUSTED_DISPLAY` wall that blocks projecting other apps does not apply. See
 * `docs/09-DASH-CONTENT-WALL.md`.
 */
object DashBrowser {

    const val HOME_URL = "https://maps.google.com/"
    private const val SEARCH = "https://www.google.com/search?q="

    /**
     * What the rider typed → where to go. One search engine, hardcoded: a preference nobody changes
     * is a settings screen nobody needed.
     *
     * "Looks like a host" is deliberately strict — no whitespace, has a dot, does not end in one — so
     * that "cali, colombia" and "ver mapa.com aqui" stay searches instead of becoming broken URLs.
     */
    fun toNavigationUrl(typed: String): String {
        val t = typed.trim()
        if (t.isEmpty()) return HOME_URL
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.endsWith(".")
        return if (looksLikeHost) "https://$t"
        else SEARCH + URLEncoder.encode(t, "UTF-8").replace("+", "%20")
    }

    /** Chromium's built-in error page is unreadable on the dash; this one is not. */
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
            settings.setGeolocationEnabled(true)
            // A WebView DENIES geolocation unless something answers the prompt — there is no UI for it
            // on a virtual display anyway. Without this, Google Maps reports it cannot access your
            // location, which on a motorcycle dashboard is the one thing it must get right. The app
            // already holds the Android location permission for the dash map; this hands it on.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: android.webkit.GeolocationPermissions.Callback,
                ) {
                    callback.invoke(origin, true, false)
                }
            }
            // Pinch-zoom yes, floating +/- buttons no: they would sit on the dash forever.
            settings.displayZoomControls = false
            // Google serves mobile user-agents a page that immediately tries to bounce into the Maps
            // APP. We want the web one: the whole point is pixels we can project. A desktop UA also
            // gives the full map UI instead of the cut-down mobile page.
            settings.userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                /**
                 * `intent://` is how a site says "open my app instead". A WebView has no handler for
                 * it and fails with `ERR_UNKNOWN_URL_SCHEME` — which is exactly what the dash showed
                 * when the home page loaded. Launching the app would defeat the point (we cannot
                 * project another app), so we take the `browser_fallback_url` the intent carries and
                 * stay on the web.
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("http://") || url.startsWith("https://")) return false
                    if (url.startsWith("intent://")) {
                        val fallback = runCatching {
                            android.content.Intent
                                .parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                                .getStringExtra("browser_fallback_url")
                        }.getOrNull()
                        view.loadUrl(fallback ?: HOME_URL)
                        return true
                    }
                    // market://, tel:, geo:, whatsapp:… — swallow them rather than paint an error
                    // page on a motorcycle dashboard.
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // Only the main frame: a failed tracking pixel must not blank the dash.
                    if (!request.isForMainFrame) return
                    view.loadDataWithBaseURL(
                        null,
                        errorPageHtml(request.url.toString(), error.description.toString()),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    onTitle(view.title ?: url)
                }
            }
        }
}
