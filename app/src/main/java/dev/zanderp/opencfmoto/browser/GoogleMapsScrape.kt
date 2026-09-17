// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Reads places out of a Google Maps results page — the PURE half.
 *
 * Why this exists: the fork's own place search (platform Geocoder + Photon + Nominatim, merged and
 * ranked) is good, and Google is still better at "centro comercial chipichape". The rider asked for
 * Google's list, picked in our UI, rendered on our map. So the browser we already own loads a Maps
 * search and this reads the answers out of the page it produced.
 *
 * **Everything here was measured on the device, not remembered.** The shapes below are verbatim from
 * `/maps/search/taller+de+motos+cali` on build 101:
 *
 * ```
 * href  = https://www.google.com/maps/place/Taller+de+Motos+Dar%C3%ADo+Cali/
 *         data=!4m7!3m6!1s0x8e30a7beef9d8d0f:0xb95c636e789fa858
 *              !8m2!3d3.3587317!4d-76.5218265!16s%2Fg%2F11fmjn273_
 * text  = Taller de Motos Darío Cali|Taller de Motos Darío Cali|4.4(54)|Taller de reparación · Cl. 42
 * ```
 *
 * Two things that measurement CHANGED from the plan:
 *  - `?api=1&query=` (the documented Maps URL) resolves straight to ONE place. Good for "take me
 *    there", useless for "show me the options" — so the search uses the `/maps/search/<text>` form.
 *  - The results are NOT plain anchors on the `?api=1` page (measured: zero). On the search page they
 *    are, and they carry the coordinates in the href, so the whole list is readable without opening
 *    a single result.
 *
 * The page's markup is not a contract. That is exactly why this half is pure and tested: when Google
 * changes it, the tests say so, and [coordsOf] already falls back from the place pin to the viewport.
 */
object GoogleMapsScrape {

    /** One result: what the rider reads and where it is. */
    data class Hit(val name: String, val lat: Double, val lon: Double, val subtitle: String?)

    /**
     * `!3d<lat>!4d<lon>` — the PLACE's own pin, inside the `data=` blob. First choice: it is the point,
     * not the view.
     */
    private val PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")

    /**
     * `/@<lat>,<lon>,<zoom>z` — the VIEWPORT centre. Second choice, and a real downgrade: on a results
     * page it is the middle of the whole list, not any one place. Only used when the pin is missing.
     */
    private val VIEW = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?),""")

    /** `/maps/place/<Name>/…` — the name Google itself gave the place, URL-encoded. */
    private val PLACE = Regex("""/maps/place/([^/@?]+)""")

    /**
     * Where to send the browser.
     *
     * The path form on purpose. The rider's own words for why: "poner una direccion o nombre de lugar
     * puede dar multiples lugares" — and `?api=1&query=` collapses exactly that into one guess.
     */
    fun searchUrl(query: String): String =
        "https://www.google.com/maps/search/" +
            URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    /** Pin first, viewport second, nothing third. Null Island is treated as "nothing": see [valid]. */
    fun coordsOf(url: String): Pair<Double, Double>? {
        PIN.find(url)?.let { m ->
            val p = m.groupValues[1].toDoubleOrNull() to m.groupValues[2].toDoubleOrNull()
            if (p.first != null && p.second != null && valid(p.first!!, p.second!!)) {
                return p.first!! to p.second!!
            }
        }
        VIEW.find(url)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && valid(lat, lon)) return lat to lon
        }
        return null
    }

    /**
     * (0, 0) is rejected as if it were a parse failure. It is a real point in the Atlantic that no rider
     * ever means, and it is what a half-matched regex produces — so letting it through would turn a
     * parsing bug into a destination 600 km off the coast of Ghana instead of an error message.
     */
    private fun valid(lat: Double, lon: Double): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** The name Google put in the URL, decoded. Fallback for when the anchor carries no label. */
    fun placeNameOf(url: String): String? =
        PLACE.find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * One harvested row → a [Hit], or null if it has no usable coordinates.
     *
     * [label] is the anchor's own text (what the rider sees in Google's list); the URL name is the
     * fallback because it is the same string with the punctuation flattened.
     */
    fun hitFrom(label: String?, href: String, articleText: String?): Hit? {
        val (lat, lon) = coordsOf(href) ?: return null
        val name = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: placeNameOf(href)
            ?: return null
        return Hit(name, lat, lon, subtitleOf(articleText, name))
    }

    /**
     * The single-match case. `?api=1&query=chipichape` — and sometimes a plain search too — lands
     * directly on a place page with no list at all, and then the settled URL IS the answer. Measured:
     * `/maps/place/Centro+Comercial+Chipichape/@3.4759489,-76.5276424,17z/data=…!3d3.4759489!4d-76.5276424`.
     */
    fun singleHit(settledUrl: String): Hit? {
        if (!settledUrl.contains("/maps/place/")) return null
        val (lat, lon) = coordsOf(settledUrl) ?: return null
        val name = placeNameOf(settledUrl) ?: return null
        return Hit(name, lat, lon, null)
    }

    /**
     * The result card's text → one readable line under the name.
     *
     * Google repeats the name (twice, measured) before the useful part, so the name is dropped and what
     * is left — rating, category, street — is joined. Two pieces, because this is read at a glance.
     */
    fun subtitleOf(articleText: String?, name: String): String? {
        val raw = articleText ?: return null
        val parts = raw.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
        if (parts.isEmpty()) return null
        return parts.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }
    }
}
