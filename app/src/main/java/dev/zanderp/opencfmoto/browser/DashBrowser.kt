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
            // Pinch-zoom yes, floating +/- buttons no: they would sit on the dash forever.
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
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
