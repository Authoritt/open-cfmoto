// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
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
        assertEquals(
            "http://192.168.49.1:8080/",
            DashBrowser.toNavigationUrl("http://192.168.49.1:8080/"),
        )
    }

    @Test
    fun `words become a Google search`() {
        assertEquals(
            "https://www.google.com/search?q=taller%20de%20motos",
            DashBrowser.toNavigationUrl("taller de motos"),
        )
    }

    /** A comma is not a dot: this is a phrase, not a host. */
    @Test
    fun `a phrase with punctuation is still a search`() {
        assertTrue(
            DashBrowser.toNavigationUrl("cali, colombia")
                .startsWith("https://www.google.com/search"),
        )
    }

    /** Dotted text with a space is a phrase too — the space is what settles it. */
    @Test
    fun `dotted text containing a space is a search`() {
        assertTrue(
            DashBrowser.toNavigationUrl("ver mapa.com aqui")
                .startsWith("https://www.google.com/search"),
        )
    }

    /** A trailing dot is a typo, not a host. */
    @Test
    fun `text ending in a dot is a search`() {
        assertTrue(
            DashBrowser.toNavigationUrl("vamos.")
                .startsWith("https://www.google.com/search"),
        )
    }

    @Test
    fun `blank input goes home rather than to an empty search`() {
        assertEquals(DashBrowser.HOME_URL, DashBrowser.toNavigationUrl("   "))
    }

    /** Spaces must be %20, not '+': '+' is only valid in a query, and this lands in a path too. */
    @Test
    fun `spaces are percent-encoded, not plus-encoded`() {
        assertTrue(!DashBrowser.toNavigationUrl("dos palabras").contains("+"))
    }

    @Test
    fun `the error page names the url and the reason`() {
        val html = DashBrowser.errorPageHtml("https://x.test/a", "net::ERR_NAME_NOT_RESOLVED")
        assertTrue(html.contains("https://x.test/a"))
        assertTrue(html.contains("net::ERR_NAME_NOT_RESOLVED"))
    }

    /** Rendered at ~1024x464 and read from a metre away: it must not inherit body defaults. */
    @Test
    fun `the error page sets its own large type`() {
        assertTrue(DashBrowser.errorPageHtml("https://x.test", "boom").contains("font-size"))
    }
}
