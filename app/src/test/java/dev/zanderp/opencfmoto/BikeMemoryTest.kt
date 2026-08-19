package dev.zanderp.opencfmoto

import android.content.SharedPreferences
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.ConnectorChoice
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.fromQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 8: [BikeMemory.specFor]/[BikeMemory.saveSpec] roundtrip, exercised through the `internal`
 * [SharedPreferences]-direct overloads (see their KDoc) since this project has no Robolectric/mocking
 * library on the unit-test classpath to fabricate a real `android.content.Context` — the same constraint
 * `DefaultBikeConnectionTest` documents. [FakeSharedPreferences] is a minimal in-memory implementation;
 * the logic under test is the exact code the `Context`-taking production entry points delegate to.
 */
class BikeMemoryTest {

    private fun qr(action: Int, ssid: String = "", mac: String? = "DD:0D:30:16:6B:50") =
        QrData(
            ssid = ssid, pwd = "pwd", auth = null, mac = mac, name = null,
            action = action, modelId = null, sn = null, channel = null,
        )

    @Test fun `specFor returns null when nothing was ever saved`() {
        val prefs = FakeSharedPreferences()
        assertNull(BikeMemory.specFor(prefs, qr(action = 1, ssid = "CFMOTO")))
    }

    @Test fun `saveSpec then specFor round-trips the exact spec`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")
        val spec = ConnectionSpec.fromQr(q).copy(lastEndpointHint = "192.168.1.1")

        BikeMemory.saveSpec(prefs, spec)

        assertEquals(spec, BikeMemory.specFor(prefs, q))
    }

    @Test fun `saveSpec is keyed by bikeId — a different bike's spec does not collide`() {
        val prefs = FakeSharedPreferences()
        val bikeA = qr(action = 1, ssid = "CFMOTO-A", mac = "AA:AA:AA:AA:AA:AA")
        val bikeB = qr(action = 1, ssid = "CFMOTO-B", mac = "BB:BB:BB:BB:BB:BB")

        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(bikeA))

        assertEquals(ConnectionSpec.fromQr(bikeA), BikeMemory.specFor(prefs, bikeA))
        assertNull("bike B must not see bike A's saved spec", BikeMemory.specFor(prefs, bikeB))
    }

    @Test fun `saveSpec overwrites a prior spec for the same bike (refresh on reconnect)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")
        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(q).copy(lastEndpointHint = "192.168.1.1"))

        val refreshed = ConnectionSpec.fromQr(q).copy(lastEndpointHint = "192.168.1.42")
        BikeMemory.saveSpec(prefs, refreshed)

        assertEquals(refreshed, BikeMemory.specFor(prefs, q))
    }

    @Test fun `saveSpec with a blank bikeId is a no-op`() {
        val prefs = FakeSharedPreferences()
        // Hand-built (not via fromQr, which can never itself produce a blank bikeId): the defensive
        // guard this test protects is in saveSpec itself.
        BikeMemory.saveSpec(prefs, ConnectionSpec(bikeId = "", mode = TransportKind.SOFT_AP, ssid = "X"))

        assertEquals("a blank-bikeId save must not write anything", 0, prefs.size)
    }

    @Test fun `specFor with a blank query bikeId (no mac, no ssid) returns null without touching prefs`() {
        val prefs = FakeSharedPreferences()
        assertNull(BikeMemory.specFor(prefs, qr(action = 1, ssid = "", mac = null)))
    }

    // Persisting a spec must NEVER write the transport-winner index: spec.mode is a fromQr guess, whereas
    // winningTransport is a real connection outcome the live path records itself (regression: the old
    // saveSpec mirror silently rewrote a learned winner via a seed/map-pick — flag-OFF live-path bug).
    @Test fun `saveSpec does NOT write the legacy winningTransport index (SOFT_AP)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")

        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(q))

        assertNull(BikeMemory.winningTransport(prefs, "CFMOTO-1234"))
        assertEquals(0, prefs.transportKeyCount())
    }

    @Test fun `saveSpec does NOT write the legacy winningTransport index (P2P)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 8, ssid = "DIRECT-ab")

        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(q))

        assertNull(BikeMemory.winningTransport(prefs, "DIRECT-ab"))
        assertEquals(0, prefs.transportKeyCount())
    }

    @Test fun `saveSpec leaves a previously-learned winningTransport intact (no seed-or-map-pick clobber)`() {
        val prefs = FakeSharedPreferences()
        // A DIRECT-* bike whose P2P never forms: the live path learned the REAL winner = AP.
        BikeMemory.setWinningTransport(prefs, "DIRECT-ab", "AP")

        // A later Garage map-pick / re-scan persists a fromQr-guessed P2P spec for the same bike.
        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(qr(action = 8, ssid = "DIRECT-ab")))

        // The learned winner must survive — the old mirror silently rewrote "AP" → "P2P", forcing the next
        // auto-connect to try P2P at the full timeout.
        assertEquals("AP", BikeMemory.winningTransport(prefs, "DIRECT-ab"))
    }

    // ---- ConnectorChoice: per-bike connection MECHANISM override (default AUTO leaves today's behavior) ----

    @Test fun `connectorChoice defaults to AUTO when never set`() {
        val prefs = FakeSharedPreferences()
        assertEquals(ConnectorChoice.AUTO, BikeMemory.connectorChoice(prefs, qr(action = 1, ssid = "CFMOTO-1234")))
    }

    @Test fun `connectorChoice defaults to AUTO for a QR with no stable id at all`() {
        val prefs = FakeSharedPreferences()
        assertEquals(ConnectorChoice.AUTO, BikeMemory.connectorChoice(prefs, qr(action = 1, ssid = "", mac = null)))
    }

    @Test fun `setConnectorChoice persists and connectorChoice reads it back`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")
        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.P2P)
        assertEquals(ConnectorChoice.P2P, BikeMemory.connectorChoice(prefs, q))
    }

    // FLIPPED (was `setConnectorChoice with a blank ssid is a no-op`, which enshrined the defect): the index
    // is keyed by ConnectionSpec.bikeIdFor, so the phone-hotspot bikes whose QRs carry NO ssid — Rieju,
    // Zontes, opaque CARBIT: exactly the ones with the newest, least-proven connectors — can hold a pin like
    // any other bike. While it was ssid-keyed, the Garage/Scan picker AND the help sheet's "try another
    // connector" escape hatch were silent no-ops for them.
    @Test fun `setConnectorChoice on a mac-only (blank-ssid) QR DOES persist and read back`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 128, ssid = "", mac = "AA:AA:AA:AA:AA:AA")

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.TETHER)

        assertEquals(ConnectorChoice.TETHER, BikeMemory.connectorChoice(prefs, q))
        assertEquals(TransportKind.TETHER, BikeMemory.specFor(prefs, q)?.mode)
        assertEquals("the pin is stored under the mac, not the (blank) ssid", "TETHER", prefs.getString("connector_AA:AA:AA:AA:AA:AA", null))
    }

    @Test fun `setConnectorChoice with NO stable id at all (no mac, no ssid) is still a no-op`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "", mac = null)
        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.SOFT_AP)
        assertEquals("nothing to key by — must not write anything", 0, prefs.size)
    }

    @Test fun `connectorChoice still reads a pin written under the LEGACY ssid key (upgrade path)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234", mac = "AA:AA:AA:AA:AA:AA")
        // Exactly what the pre-fix build wrote for a bike that has BOTH a mac and an ssid.
        prefs.edit().putString("connector_CFMOTO-1234", "P2P").apply()

        assertEquals(ConnectorChoice.P2P, BikeMemory.connectorChoice(prefs, q))
    }

    @Test fun `setConnectorChoice(SOFT_AP) forces the stored spec mode to SOFT_AP`() {
        val prefs = FakeSharedPreferences()
        // A DIRECT-* P2P bike (fromQr mode = P2P) — the SoftAP override must flip the stored spec.mode.
        val q = qr(action = 8, ssid = "DIRECT-ab")
        assertEquals(TransportKind.P2P, ConnectionSpec.fromQr(q).mode) // guard the premise

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.SOFT_AP)

        assertEquals(TransportKind.SOFT_AP, BikeMemory.specFor(prefs, q)?.mode)
    }

    @Test fun `setConnectorChoice(P2P) forces the stored spec mode to P2P`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(q).mode) // guard the premise

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.P2P)

        assertEquals(TransportKind.P2P, BikeMemory.specFor(prefs, q)?.mode)
    }

    @Test fun `setConnectorChoice(RIEJU_BLE) forces PHONE_HOTSPOT even for a SoftAP QR (deliberate override)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234") // a plain SoftAP QR, not phone-hotspot-shaped

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.RIEJU_BLE)

        assertEquals(TransportKind.PHONE_HOTSPOT, BikeMemory.specFor(prefs, q)?.mode)
        assertEquals(ConnectorChoice.RIEJU_BLE, BikeMemory.connectorChoice(prefs, q))
    }

    @Test fun `setConnectorChoice(TETHER) forces the stored spec mode to TETHER (keeping the rest)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234") // a plain SoftAP QR — the pin must override detection
        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(q).copy(lastEndpointHint = "192.168.1.9"))
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(q).mode) // guard the premise

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.TETHER)

        assertEquals(ConnectorChoice.TETHER, BikeMemory.connectorChoice(prefs, q))
        // TETHER is a real TransportKind now: the factory selects TetherTransport by spec.mode.
        assertEquals(TransportKind.TETHER, BikeMemory.specFor(prefs, q)?.mode)
        assertEquals("192.168.1.9", BikeMemory.specFor(prefs, q)?.lastEndpointHint)
    }

    @Test fun `setConnectorChoice(AUTO) after TETHER resets the spec mode to the auto-detected one`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")
        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.TETHER)
        assertEquals(TransportKind.TETHER, BikeMemory.specFor(prefs, q)?.mode)

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.AUTO)

        assertEquals(ConnectorChoice.AUTO, BikeMemory.connectorChoice(prefs, q))
        assertEquals(TransportKind.SOFT_AP, BikeMemory.specFor(prefs, q)?.mode)
    }

    @Test fun `a spec saved before TETHER existed is still readable through specFor`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 128, ssid = "PHONE-HOTSPOT-166B50") // keyed by mac (bikeIdFor)
        // Written by the pre-TETHER build: every phone-hotspot QR was stored as PHONE_HOTSPOT.
        BikeMemory.saveSpec(
            prefs,
            ConnectionSpec(
                bikeId = "DD:0D:30:16:6B:50",
                mode = TransportKind.PHONE_HOTSPOT,
                ssid = "PHONE-HOTSPOT-166B50",
                bleMac = "DD:0D:30:16:6B:50",
                lastEndpointHint = "192.168.43.24",
            ),
        )

        val spec = BikeMemory.specFor(prefs, q)

        assertEquals(TransportKind.PHONE_HOTSPOT, spec?.mode) // survives verbatim; the factory heals it
        assertEquals("192.168.43.24", spec?.lastEndpointHint)
    }

    @Test fun `setConnectorChoice(AUTO) clears an override — spec mode back to auto-detected, choice back to AUTO`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234") // fromQr mode = SOFT_AP
        // Rider first pins P2P (spec.mode → P2P), then returns to Automatic.
        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.P2P)
        assertEquals(TransportKind.P2P, BikeMemory.specFor(prefs, q)?.mode)

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.AUTO)

        assertEquals(ConnectorChoice.AUTO, BikeMemory.connectorChoice(prefs, q))
        assertEquals(TransportKind.SOFT_AP, BikeMemory.specFor(prefs, q)?.mode) // reset to the fromQr guess
    }

    @Test fun `setConnectorChoice keeps other spec fields when forcing the mode (reuses the existing spec)`() {
        val prefs = FakeSharedPreferences()
        val q = qr(action = 1, ssid = "CFMOTO-1234")
        BikeMemory.saveSpec(prefs, ConnectionSpec.fromQr(q).copy(lastEndpointHint = "10.0.0.1"))

        BikeMemory.setConnectorChoice(prefs, q, ConnectorChoice.P2P)

        val spec = BikeMemory.specFor(prefs, q)
        assertEquals(TransportKind.P2P, spec?.mode)
        assertEquals("10.0.0.1", spec?.lastEndpointHint)
    }
}

/**
 * Minimal in-memory [SharedPreferences]/[SharedPreferences.Editor] fake — only the handful of members
 * [BikeMemory]'s `internal` overloads actually exercise (`getString`/`edit().putString().apply()`) are
 * meaningfully implemented; the rest of the interface throws if ever called, which would mean a test
 * started depending on behavior this fake was never meant to model.
 */
private class FakeSharedPreferences : SharedPreferences {
    private val map = linkedMapOf<String, Any?>()

    val size: Int get() = map.size
    fun transportKeyCount(): Int = map.keys.count { it.startsWith("transport_") }

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException("not used by BikeMemory")
    override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException("not used by BikeMemory")
    override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException("not used by BikeMemory")
    override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException("not used by BikeMemory")
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        throw UnsupportedOperationException("not used by BikeMemory")
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    // NOTE: bodies return `this` explicitly (not the `apply {}` stdlib scope function) — this class
    // itself overrides a zero-arg member also named `apply()` (the SharedPreferences.Editor contract),
    // and shadowing it with lambda-taking calls of the same name is needless overload-resolution risk.
    private inner class FakeEditor : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            puts[requireNotNull(key)] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException("not used by BikeMemory")
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            throw UnsupportedOperationException("not used by BikeMemory")
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            throw UnsupportedOperationException("not used by BikeMemory")
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            throw UnsupportedOperationException("not used by BikeMemory")
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            throw UnsupportedOperationException("not used by BikeMemory")
        override fun remove(key: String?): SharedPreferences.Editor {
            removals.add(requireNotNull(key))
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean { applyPending(); return true }
        override fun apply() = applyPending()

        private fun applyPending() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            map.putAll(puts)
        }
    }
}
