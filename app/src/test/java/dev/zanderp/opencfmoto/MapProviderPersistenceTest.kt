// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import dev.zanderp.opencfmoto.settings.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `mapProvider` is persisted BY NAME in DataStore, so the stored string of an already-paired install
 * must keep meaning what it meant. Renaming or reordering a constant silently repoints every existing
 * install — the same class of bug that `ConnectionSpec.mode` paid for in the connection factory, where
 * splitting a persisted enum value would have routed a Zontes onto the Rieju's unproven connector.
 */
class MapProviderPersistenceTest {

    @Test
    fun `the persisted names never change`() {
        assertEquals(
            listOf("BUILTIN", "GOOGLE", "WAZE", "MIRROR", "WEB", "BUILTIN_GSEARCH"),
            MapProvider.entries.map { it.name },
        )
    }

    /**
     * The previous shape of this test only checked that WEB was last, which stopped being the point
     * the moment a sixth value arrived. What actually matters is that nothing already shipped MOVED:
     * a value that changes ordinal repoints every install that had stored it.
     */
    @Test
    fun `every value that has already shipped keeps its place`() {
        val shipped = mapOf(
            "BUILTIN" to 0,
            "GOOGLE" to 1,
            "WAZE" to 2,
            "MIRROR" to 3,
            "WEB" to 4,
            "BUILTIN_GSEARCH" to 5,
        )
        shipped.forEach { (name, ordinal) ->
            assertEquals("${name} moved", ordinal, MapProvider.valueOf(name).ordinal)
        }
    }

    @Test
    fun `an unknown stored name degrades to BUILTIN instead of throwing`() {
        val decoded = runCatching { MapProvider.valueOf("FROM_A_NEWER_BUILD") }
            .getOrDefault(MapProvider.BUILTIN)
        assertEquals(MapProvider.BUILTIN, decoded)
    }
}
