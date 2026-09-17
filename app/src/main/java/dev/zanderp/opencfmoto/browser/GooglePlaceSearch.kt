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

    suspend fun search(
        context: Context,
        query: String,
        lat: Double? = null,
        lon: Double? = null,
        timeoutMs: Long = 15_000L,
    ): Result {
        val q = query.trim()
        if (q.length < 2) return Result.Empty

        val url = GoogleMapsScrape.searchUrl(q, lat, lon)
        // If the browser is not up yet it is started ON the search, because a load() issued while the
        // WebView is still being built goes nowhere.
        val wasRunning = DashBrowserHost.isRunning
        DashBrowserHost.ensureStarted(context, url)
        if (wasRunning) {
            eval(STAMP_JS)
            DashBrowserHost.load(url)
        }
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

    private const val MAPS_HOME = "https://www.google.com/maps"

    /**
     * Have Maps already open before the rider types a letter.
     *
     * The dropdown only exists on a live Maps page, and MEASURED from cold the search box is still
     * missing after 6 s — so the first suggestion of every session came back empty. Warming up when
     * the provider is chosen turns that first suggestion into an instant one. The browser is invisible
     * and costs nothing while idle; the search would have created it anyway.
     */
    fun warmUp(context: Context) {
        DashBrowserHost.ensureStarted(context, MAPS_HOME)
    }

    /**
     * What Maps would offer while the rider types — the dropdown, not the results.
     *
     * The owner worked out the difference himself: "si yo escribo en el buscador de maps asi me salen
     * todos esos, pero si le doy a la lupa de buscar ahi si solo me llega el del centro comercial".
     * Typing and submitting are two different questions, and Maps answers them differently: five
     * Chipichapes in the dropdown, one after the magnifying glass. So typing asks this, and the
     * magnifying glass asks [search] — the same split the app already had.
     *
     * No page load: the query is typed into the box of whatever Maps page the browser is on, and Maps
     * fetches its own suggestions. Cheap enough to run on every typing pause.
     */
    suspend fun suggest(context: Context, query: String, timeoutMs: Long = 9_000L): List<GoogleMapsScrape.Suggestion> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        DashBrowserHost.ensureStarted(context, MAPS_HOME)

        val start = SystemClock.uptimeMillis()
        var typed = false
        var loadedOnce = false
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (!typed) {
                val r = unwrap(eval(typeJs(q)))
                if (r?.optInt("ok", 0) == 1) {
                    typed = true
                } else {
                    // No search box: the browser is on another site, or a load failed and left our
                    // error page. Send it to Maps ONCE and keep trying until the budget runs out.
                    if (!loadedOnce) {
                        loadedOnce = true
                        DashBrowserHost.load(MAPS_HOME)
                    }
                    delay(POLL_MS)
                    continue
                }
            }
            delay(POLL_MS)
            val obj = unwrap(eval(SUGGEST_JS)) ?: continue
            val arr = obj.optJSONArray("s") ?: continue
            if (arr.length() == 0) continue
            val out = ArrayList<GoogleMapsScrape.Suggestion>(arr.length())
            for (i in 0 until arr.length()) {
                GoogleMapsScrape.parseSuggestion(arr.optString(i))?.let(out::add)
            }
            if (out.isNotEmpty()) {
                LogBus.log("[GSUGGEST] $q -> ${out.size}")
                return out
            }
        }
        LogBus.log("[GSUGGEST] $q -> nada (escribio=$typed)")
        return emptyList()
    }

    /**
     * Type into Maps' own box. On the results page it has NO id (measured: `input[name="q"]`), so it
     * is found by a ladder of selectors rather than by the one everybody quotes.
     */
    private fun typeJs(q: String): String = """
        (function(t){
          var b = document.querySelector('#searchboxinput')
               || document.querySelector('input[name="q"]')
               || document.querySelector('input[type="text"]');
          if (!b) return JSON.stringify({ ok: 0 });
          b.focus();
          b.value = t;
          b.dispatchEvent(new Event('input', { bubbles: true }));
          return JSON.stringify({ ok: 1 });
        })(QQ);
    """.trimIndent().replace("QQ", org.json.JSONObject.quote(q))

    /**
     * The dropdown. `[role="option"]` is empty here (measured: 0) — Google marks these rows with its
     * own `jsaction`, so that is what we read, and the parser is pure and tested because this is the
     * least durable selector in the file.
     */
    private val SUGGEST_JS = """
        (function(){
          var xs = document.querySelectorAll('[jsaction*="suggestion"]'), o = [];
          for (var i = 0; i < xs.length && i < 8; i++) o.push((xs[i].innerText || '').slice(0, 160));
          return JSON.stringify({ s: o });
        })();
    """.trimIndent()

    /**
     * Resolver una sugerencia PULSANDOLA, que es lo que hace Maps.
     *
     * El primer intento buscaba su texto ("Chipichape Living Calle 37 Bis Norte, Santa Monica
     * Residential, Cali, Valle del Cauca") y agotaba los 15 s sin respuesta: una frase larga no es una
     * consulta, es una descripcion. Pulsar la fila deja que Google haga lo suyo, y entonces la url se
     * convierte en la ficha del sitio, con sus coordenadas dentro.
     *
     * Si el clic no llega a ninguna ficha se cae a [search] con el texto, que al menos dice algo.
     */
    suspend fun resolveSuggestion(
        context: Context,
        index: Int,
        suggestion: GoogleMapsScrape.Suggestion,
        timeoutMs: Long = 12_000L,
    ): Result {
        val fallbackQuery = GoogleMapsScrape.queryFor(suggestion)
        DashBrowserHost.ensureStarted(context, MAPS_HOME)
        val clicked = unwrap(eval(clickJs(index)))?.optInt("ok", 0) == 1
        if (clicked) {
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < timeoutMs) {
                delay(POLL_MS)
                val obj = unwrap(eval(HARVEST_JS)) ?: continue
                GoogleMapsScrape.singleHit(obj.optString("u"), obj.optString("a"))?.let {
                    // El nombre, de la SUGERENCIA. El de la url trae la direccion pegada
                    // ("Chipichape Living, Calle 37 Bis Norte, Santa Monica Residential, Cali, ...")
                    // y ese texto acaba siendo el nombre del destino en el tablero.
                    val named = it.copy(name = suggestion.name, subtitle = it.subtitle ?: suggestion.address)
                    LogBus.log("[GPICK] $index -> ${named.name}")
                    return Result.Found(listOf(named))
                }
            }
            LogBus.log("[GPICK] $index -> el clic no llego a una ficha; se busca el texto")
        } else {
            LogBus.log("[GPICK] $index -> la fila ya no esta; se busca el texto")
        }
        return search(context, fallbackQuery, timeoutMs = timeoutMs)
    }

    private fun clickJs(index: Int): String = """
        (function(i){
          var xs = document.querySelectorAll('[jsaction*="suggestion"]');
          if (i < 0 || i >= xs.length) return JSON.stringify({ ok: 0 });
          var t = xs[i].querySelector('a,[role="button"]') || xs[i];
          t.click();
          return JSON.stringify({ ok: 1 });
        })(II);
    """.trimIndent().replace("II", index.toString())

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
