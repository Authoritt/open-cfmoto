package dev.zanderp.opencfmoto

import android.content.SharedPreferences
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
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
