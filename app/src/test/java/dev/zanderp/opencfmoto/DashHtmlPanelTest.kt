// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DashHtmlPanel.resolve] decides what the bike dash paints. A wrong answer here is not a crash — it is
 * a BLANK dash on a moving motorcycle, so each branch is pinned.
 */
class DashHtmlPanelTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `panel_html wins when present`() {
        val dir = tmp.newFolder("paneles")
        java.io.File(dir, "aaa.html").writeText("<i>otro</i>")
        java.io.File(dir, DashHtmlPanel.PANEL_NAME).writeText("<b>el bueno</b>")
        assertEquals(DashHtmlPanel.PANEL_NAME, DashHtmlPanel.resolve(dir)?.name)
    }

    @Test
    fun `a single differently named html still renders`() {
        val dir = tmp.newFolder("paneles")
        java.io.File(dir, "tablero-moto.html").writeText("<b>x</b>")
        assertEquals("tablero-moto.html", DashHtmlPanel.resolve(dir)?.name)
    }

    @Test
    fun `several html files resolve alphabetically, not by filesystem order`() {
        val dir = tmp.newFolder("paneles")
        listOf("zeta.html", "Alfa.html", "beta.html").forEach {
            java.io.File(dir, it).writeText("<b>$it</b>")
        }
        assertEquals("Alfa.html", DashHtmlPanel.resolve(dir)?.name)
    }

    @Test
    fun `non-html files are ignored`() {
        val dir = tmp.newFolder("paneles")
        java.io.File(dir, "notas.txt").writeText("no soy un panel")
        java.io.File(dir, "estilo.css").writeText("body{}")
        assertNull(DashHtmlPanel.resolve(dir))
    }

    @Test
    fun `an empty folder resolves to nothing rather than to a phantom file`() {
        assertNull(DashHtmlPanel.resolve(tmp.newFolder("paneles")))
    }

    @Test
    fun `a missing folder resolves to nothing instead of throwing`() {
        val gone = java.io.File(tmp.root, "no-existe")
        assertNull(DashHtmlPanel.resolve(gone))
    }

    /** A directory named `panel.html` is not a panel — `isFile` is what keeps the dash from loading it. */
    @Test
    fun `a directory named like the panel is not mistaken for it`() {
        val dir = tmp.newFolder("paneles")
        java.io.File(dir, DashHtmlPanel.PANEL_NAME).mkdirs()
        assertNull(DashHtmlPanel.resolve(dir))
    }
}
