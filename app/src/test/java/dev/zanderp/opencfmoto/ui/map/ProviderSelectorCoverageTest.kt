// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.map

import dev.zanderp.opencfmoto.settings.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider selector used to be three hand-written rows. Adding `MapProvider.WEB` compiled fine,
 * passed every test, and then simply **did not appear on screen** — the owner found it, not the suite.
 *
 * A list written by hand stops seeing what is new and never says so. These tests make that impossible:
 * a new provider must be either offered or explicitly excluded, and forgetting is a red test rather
 * than a button nobody can find.
 */
class ProviderSelectorCoverageTest {

    @Test
    fun `every provider is either offered or deliberately excluded`() {
        val accountedFor = (SELECTABLE_PROVIDERS + PROVIDERS_NOT_IN_SELECTOR).toSet()
        val missing = MapProvider.entries.toSet() - accountedFor
        assertEquals(
            "These providers exist but the selector neither offers nor excludes them: $missing",
            emptySet<MapProvider>(),
            missing,
        )
    }

    /** Offering and excluding the same value would make the intent unreadable. */
    @Test
    fun `nothing is both offered and excluded`() {
        val both = SELECTABLE_PROVIDERS.toSet() intersect PROVIDERS_NOT_IN_SELECTOR.toSet()
        assertEquals(emptySet<MapProvider>(), both)
    }

    /** A duplicate would render the same button twice and steal width from the others. */
    @Test
    fun `no provider is offered twice`() {
        assertEquals(SELECTABLE_PROVIDERS.size, SELECTABLE_PROVIDERS.toSet().size)
    }

    /** The row divides the width evenly; past four it stops being readable on a phone. */
    @Test
    fun `the selector stays within what one row can show`() {
        assertTrue(
            "The selector row has ${SELECTABLE_PROVIDERS.size} options; beyond four it needs a new layout",
            SELECTABLE_PROVIDERS.size <= 4,
        )
    }

    @Test
    fun `the browser is offered`() {
        assertTrue(MapProvider.WEB in SELECTABLE_PROVIDERS)
    }
}
