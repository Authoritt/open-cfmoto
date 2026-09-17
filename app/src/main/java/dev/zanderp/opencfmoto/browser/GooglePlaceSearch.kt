// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

import android.content.Context
import android.os.SystemClock
import dev.zanderp.opencfmoto.LogBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Asks Google Maps for places — the IMPURE half. [GoogleMapsScrape] holds the part worth testing.
 *
 * It drives the browser the app already owns ([DashBrowserHost]) rather than opening a second one. In
 * this provider the dash is showing the MAP, so that browser is idle and invisible: nobody is reading
 * the page it is on, and no encoder or preview is attached to it. Reusing it costs one virtual display
 * instead of two, and it is the path already proven to render Google Maps on this phone.
 *
 * No API key, and nothing is requested that Google did not already serve to the page: this reads the
 * rider's own search, in the rider's own browser, and shows it to the rider. The licensed alternative
 * (Places API) needs a key and billing, which is what the owner ruled out.
 */
object GooglePlaceSearch {

    sealed interface Result {
        /** At least one place, best first (Google's own order, which is the reason we are here). */
        data class Found(val hits: List<GoogleMapsScrape.Hit>) : Result

        /** The page answered and had nothing. Different from [Failed]: do not retry, tell the rider. */
        object Empty : Result

        /** No usable answer in time — no page, no network, or Google changed the markup. */
        data class Failed(val why: String) : Result
    }

    private const val POLL_MS = 600L
    private const val EVAL_TIMEOUT_MS = 2_500L

    /**
     * Marks the document that is on screen RIGHT NOW. A real navigation builds a new document where
     * this flag does not exist, which is how [search] tells "Google answered" from "the previous page
     * is still up". Without it the first poll happily returns the LAST search's results.
     */
    private const val STAMP_JS = "(function(){ window.__ovkStale = 1; })();"

    /**
     * The harvest, shaped by what the device actually showed (build 101). Each result is an anchor to
     * `/maps/place/…` whose href carries the coordinates in `!3d`/`!4d`, so the whole list is readable
     * without opening a single result. The card it sits in ([role="article"]) carries the rating,
     * category and street that become the subtitle.
     */
    private val HARVEST_JS = """
        (function(){
          if (window.__ovkStale) return JSON.stringify({ stale: 1 });
          var out = [];
          var as = document.querySelectorAll('a[href*="/maps/place/"]');
          for (var i = 0; i < as.length && out.length < 12; i++) {
            var h = as[i].getAttribute('href') || '';
            if (h.indexOf('!3d') < 0) continue;
            var art = as[i].closest ? as[i].closest('[role="article"]') : null;
            out.push({
              n: (as[i].getAttribute('aria-label') || as[i].textContent || '').trim().slice(0,120),
              h: h,
              s: art ? (art.innerText || '').replace(/\n/g,'|').slice(0,200) : ''
            });
          }
          var addr = '';
          var ae = document.querySelector('[data-item-id="address"]');
          if (ae) addr = (ae.getAttribute('aria-label') || ae.textContent || '').trim().slice(0,140);
          return JSON.stringify({ u: location.href, a: addr, r: out });
        })();
    """.trimIndent()

    suspend fun search(context: Context, query: String, timeoutMs: Long = 15_000L): Result {
        val q = query.trim()
        if (q.length < 2) return Result.Empty

        DashBrowserHost.ensureStarted(context)
        eval(STAMP_JS)
        val url = GoogleMapsScrape.searchUrl(q)
        DashBrowserHost.load(url)
        LogBus.log("[GSEARCH] $q -> $url")

        val start = SystemClock.uptimeMillis()
        var sawFreshPage = false
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            delay(POLL_MS)
            val obj = unwrap(eval(HARVEST_JS)) ?: continue

            if (obj.optInt("stale", 0) == 1) {
                // Still the stamped document. Normally that means the navigation has not committed
                // yet — but loading the SAME url can legitimately reuse the document, and then the
                // stamp survives forever. Past halfway we stop treating it as evidence and read the
                // page: if the url did not change, its results are for this very query anyway.
                if (SystemClock.uptimeMillis() - start < timeoutMs / 2) continue
            } else {
                sawFreshPage = true
            }

            val hits = hitsOf(obj)
            if (hits.isNotEmpty()) {
                LogBus.log("[GSEARCH] $q -> ${hits.size} sitios")
                return Result.Found(hits)
            }
            // No list: `?api=1&query=` and an unambiguous search both land straight on a place page,
            // and then the settled url IS the answer.
            GoogleMapsScrape.singleHit(obj.optString("u"), obj.optString("a"))?.let {
                LogBus.log("[GSEARCH] $q -> 1 sitio exacto (${it.name})")
                return Result.Found(listOf(it))
            }
        }

        LogBus.log("[GSEARCH] $q -> sin respuesta (pagina cargada=$sawFreshPage)")
        return if (sawFreshPage) Result.Empty else Result.Failed("la pagina no respondio")
    }

    /** One eval, bounded. A destroyed WebView can drop the callback, and a hung poll is a hung search. */
    private suspend fun eval(js: String): String? = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            DashBrowserHost.evaluate(js) { r -> if (cont.isActive) cont.resume(r) }
        }
    }

    /** `evaluateJavascript` hands back the value JSON-encoded, so our JSON string arrives double-wrapped. */
    private fun unwrap(raw: String?): JSONObject? {
        if (raw == null) return null
        return runCatching {
            val inner = JSONTokener(raw).nextValue() as? String ?: return null
            JSONObject(inner)
        }.getOrNull()
    }

    private fun hitsOf(obj: JSONObject): List<GoogleMapsScrape.Hit> {
        val arr = obj.optJSONArray("r") ?: return emptyList()
        val out = ArrayList<GoogleMapsScrape.Hit>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val hit = GoogleMapsScrape.hitFrom(
                label = row.optString("n"),
                href = row.optString("h"),
                articleText = row.optString("s").takeIf { it.isNotEmpty() },
            ) ?: continue
            // A card and its photo can be two anchors to the same place. Deduped by position, in
            // micro-degrees (~10 cm) and locale-free — String.format would put commas in es-CO.
            val key = "${(hit.lat * 1e6).toLong()},${(hit.lon * 1e6).toLong()}"
            if (seen.add(key)) out.add(hit)
        }
        return out
    }
}
