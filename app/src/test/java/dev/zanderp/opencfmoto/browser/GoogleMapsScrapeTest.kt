// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every URL in here was CAPTURED FROM THE PHONE (build 101, `/maps/search/taller+de+motos+cali` and
 * `?api=1&query=centro+comercial+chipichape`), not written from memory. That matters more than usual:
 * the thing under test is somebody else's markup, so a fixture invented by the author would only ever
 * prove the author's idea of it.
 *
 * The two synthetic cases are marked as such, and both test a rule of OURS rather than Google's shape.
 */
class GoogleMapsScrapeTest {

    // ---- Real anchors from the results list ------------------------------------------------------

    private val darioHref =
        "https://www.google.com/maps/place/Taller+de+Motos+Dar%C3%ADo+Cali/data=!4m7!3m6" +
            "!1s0x8e30a7beef9d8d0f:0xb95c636e789fa858!8m2!3d3.3587317!4d-76.5218265" +
            "!16s%2Fg%2F11fmjn273_!19sChIJD42d776nMI4R"

    private val caliMotosHref =
        "https://www.google.com/maps/place/CaliMotos+Taller+de+motos/data=!4m7!3m6" +
            "!1s0x8e30a1a3d53c797d:0x4ad035f885fad795!8m2!3d3.3818677!4d-76.5207229" +
            "!16s%2Fg%2F11rsmm2yzz!19sChIJfXk81aOhMI4Rldf6hf"

    private val kycHref =
        "https://www.google.com/maps/place/TALLER+K%26C+MOTOS+-+TALLER+EN+EL+SUR+DE+CALI+-+CANEY" +
            "/data=!4m7!3m6!1s0x8e30a16e13097187:0x9670084bc8db6fa5!8m2!3d3.3804706!4d-76.5150815" +
            "!16s%2Fg%2F11xl"

    /** The single-match landing page: `?api=1&query=` collapses a search into one place. */
    private val chipichapeUrl =
        "https://www.google.com/maps/place/Centro+Comercial+Chipichape/@3.4759489,-76.5276424,17z" +
            "/data=!3m1!4b1!4m6!3m5!1s0x8e30a618c17d3bf7:0x516c5b91fa92e1b9" +
            "!8m2!3d3.4759489!4d-76.5276424!16s%2Fg%2F12hq5zv7m?entry=ttu&g_ep=EgoyMDI2MDkxNC4w"

    /** The results page's own URL: a viewport, no place, no pin. */
    private val searchPageUrl =
        "https://www.google.com/maps/search/taller+de+motos+cali/@3.4024481,-76.528307,13z" +
            "/data=!3m1!4b1?entry=ttu&g_ep=EgoyMDI2MDkxNC4w"

    @Test
    fun `the four measured results parse to the four measured points`() {
        assertEquals(3.3587317 to -76.5218265, GoogleMapsScrape.coordsOf(darioHref))
        assertEquals(3.3818677 to -76.5207229, GoogleMapsScrape.coordsOf(caliMotosHref))
        assertEquals(3.3804706 to -76.5150815, GoogleMapsScrape.coordsOf(kycHref))
        assertEquals(3.4759489 to -76.5276424, GoogleMapsScrape.coordsOf(chipichapeUrl))
    }

    @Test
    fun `the name is decoded, accents and ampersands included`() {
        assertEquals("Taller de Motos Darío Cali", GoogleMapsScrape.placeNameOf(darioHref))
        assertEquals(
            "TALLER K&C MOTOS - TALLER EN EL SUR DE CALI - CANEY",
            GoogleMapsScrape.placeNameOf(kycHref),
        )
        assertEquals("Centro Comercial Chipichape", GoogleMapsScrape.placeNameOf(chipichapeUrl))
    }

    @Test
    fun `a results page is not a place`() {
        // It has a /@viewport, so coordsOf answers — but it is nobody's destination, and singleHit
        // must not offer it as one. This is the difference between "where the map is looking" and
        // "where the rider is going".
        assertNull(GoogleMapsScrape.singleHit(searchPageUrl))
        assertEquals(3.4024481 to -76.528307, GoogleMapsScrape.coordsOf(searchPageUrl))
    }

    @Test
    fun `the single match becomes a hit`() {
        val hit = GoogleMapsScrape.singleHit(chipichapeUrl)!!
        assertEquals("Centro Comercial Chipichape", hit.name)
        assertEquals(3.4759489, hit.lat, 1e-7)
        assertEquals(-76.5276424, hit.lon, 1e-7)
    }

    /**
     * SYNTHETIC, and deliberately so: it tests OUR precedence rule, not Google's format. Every real
     * capture had pin and viewport equal, so only a case where they differ can prove which one wins.
     * On a results page the viewport is the middle of the whole list — taking it for a place would
     * send the rider to the centre of gravity of five workshops instead of to one.
     */
    @Test
    fun `the place pin beats the viewport centre`() {
        val both = "https://www.google.com/maps/place/X/@1.0,2.0,17z/data=!8m2!3d9.5!4d8.5"
        assertEquals(9.5 to 8.5, GoogleMapsScrape.coordsOf(both))
    }

    /** SYNTHETIC: a half-matched regex yields zeroes, and (0,0) is a real point in the Atlantic. */
    @Test
    fun `null island is refused`() {
        assertNull(GoogleMapsScrape.coordsOf("https://www.google.com/maps/place/X/data=!3d0!4d0"))
        assertNull(GoogleMapsScrape.coordsOf("https://www.google.com/maps/place/X/@0,0,17z"))
        assertNull(GoogleMapsScrape.coordsOf("https://www.google.com/maps/place/X/nothing"))
    }

    @Test
    fun `a row with no coordinates is dropped, not guessed`() {
        assertNull(GoogleMapsScrape.hitFrom("Un sitio", "https://www.google.com/maps/place/X/", null))
    }

    @Test
    fun `the anchor label wins over the name in the url`() {
        val hit = GoogleMapsScrape.hitFrom("Taller de Motos Darío Cali", darioHref, null)!!
        assertEquals("Taller de Motos Darío Cali", hit.name)
        val unlabelled = GoogleMapsScrape.hitFrom("   ", darioHref, null)!!
        assertEquals("Taller de Motos Darío Cali", unlabelled.name)
    }

    @Test
    fun `the card text becomes one line, without the repeated name`() {
        // Measured verbatim: Google prints the name twice before anything useful.
        val card = "Taller de Motos Darío Cali|Taller de Motos Darío Cali|4.4(54)|" +
            "Taller de reparación de motos · Cl. 42"
        assertEquals(
            "4.4(54) · Taller de reparación de motos · Cl. 42",
            GoogleMapsScrape.subtitleOf(card, "Taller de Motos Darío Cali"),
        )
    }

    @Test
    fun `a card with nothing but the name has no subtitle`() {
        assertNull(GoogleMapsScrape.subtitleOf("Solo el nombre|Solo el nombre", "Solo el nombre"))
        assertNull(GoogleMapsScrape.subtitleOf(null, "x"))
    }

    @Test
    fun `the search url is the listing form, not the single-place form`() {
        val u = GoogleMapsScrape.searchUrl(" taller de motos cali ")
        assertEquals("https://www.google.com/maps/search/taller%20de%20motos%20cali", u)
        // The documented ?api=1&query= form was MEASURED to collapse to one place; the rider asked for
        // the list, so this must never go back to it.
        assertTrue("the search must not use the single-place form", !u.contains("api=1"))
    }
}
