// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.cockpit

import dev.zanderp.opencfmoto.settings.MapProvider
import dev.zanderp.opencfmoto.ui.map.SELECTABLE_PROVIDERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider chip in the cockpit is the ONLY selector a rider can reach: `MapScreen`'s picker is
 * dead code, because nothing navigates to `Routes.MAP`.
 *
 * That mattered: `MapProvider.WEB` was added, the selector on the unreachable screen was updated, its
 * coverage test went green — and the rider still could not get to the browser, because this chip cycled
 * through a hand-written chain whose `else` folded the new value back to BUILTIN. A test over a screen
 * nobody opens proves nothing about the one they do. These tests are on the reachable path.
 */
class ProviderCycleTest {

    @Test
    fun `the cycle reaches every selectable provider`() {
        var p = SELECTABLE_PROVIDERS.first()
        val seen = mutableSetOf(p)
        repeat(SELECTABLE_PROVIDERS.size * 2) {
            p = nextProvider(p)
            seen += p
        }
        assertEquals(
            "Tapping the chip must be able to reach every offered provider",
            SELECTABLE_PROVIDERS.toSet(),
            seen,
        )
    }

    /** The whole point of this fix: the browser must be reachable by tapping. */
    @Test
    fun `the browser is reachable from the built-in map`() {
        var p: MapProvider = MapProvider.BUILTIN
        repeat(SELECTABLE_PROVIDERS.size) { p = nextProvider(p) }
        // A full lap returns home, and along the way WEB must have appeared.
        val lap = generateSequence(MapProvider.BUILTIN) { nextProvider(it) }
            .take(SELECTABLE_PROVIDERS.size + 1)
            .toList()
        assertTrue("WEB never appears in a full lap: $lap", MapProvider.WEB in lap)
    }

    @Test
    fun `a full lap returns to where it started`() {
        var p = MapProvider.BUILTIN
        repeat(SELECTABLE_PROVIDERS.size) { p = nextProvider(p) }
        assertEquals(MapProvider.BUILTIN, p)
    }

    /** Mirror is off-cycle (armed from its own screen); it must fold back in, not dead-end. */
    @Test
    fun `an off-cycle provider folds back into the cycle`() {
        assertTrue(nextProvider(MapProvider.MIRROR) in SELECTABLE_PROVIDERS)
    }
}
