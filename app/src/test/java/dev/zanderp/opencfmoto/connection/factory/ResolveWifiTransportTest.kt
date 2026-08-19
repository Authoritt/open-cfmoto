package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.WifiTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **The identity proof for the §1a extraction.** `CfmotoConnect.joinWifi` used to hold the SoftAP-vs-P2P
 * decision inline; it now calls [resolveWifiTransport], which the factory also calls (through
 * [ConnectionSpec.detectedAtPairing]) so the classic path and the cockpit can never route the same bike two
 * different ways again.
 *
 * The refactor is only safe if it is EXACTLY the old code, so [oldClassicUseP2p] re-states the two original
 * expressions verbatim (copied from the pre-refactor `joinWifi`) and the truth table below asserts the
 * extracted function agrees on every combination of
 * `{pref} × {supportsP2p} × {supportsAp} × {DIRECT-… / other ssid} × {remembered null|"AP"|"P2P"}` —
 * 3 × 2 × 2 × 2 × 3 = 72 cases, exhaustive rather than sampled.
 */
class ResolveWifiTransportTest {

    /** VERBATIM copy of the two expressions `joinWifi` held before the extraction — the oracle. */
    private fun oldClassicUseP2p(qr: QrData, transport: WifiTransport, remembered: String?): Boolean {
        val useP2p = when (transport) {
            WifiTransport.P2P -> qr.supportsP2p || !qr.supportsAp
            WifiTransport.AP -> false
            WifiTransport.AUTO ->
                (qr.supportsP2p && !qr.supportsAp) ||
                    (qr.ssid.startsWith("DIRECT-", ignoreCase = true) &&
                        (qr.supportsP2p || !qr.supportsAp))
        }
        return when {
            remembered == "AP" && qr.supportsAp -> false
            remembered == "P2P" && qr.supportsP2p -> true
            else -> useP2p
        }
    }

    // action bits: bit0 = SoftAP, bit3 = P2P — the same getters the production code reads.
    private fun qr(supportsP2p: Boolean, supportsAp: Boolean, ssid: String) = QrData(
        ssid = ssid, pwd = "12345678", auth = null, mac = "DD:0D:30:16:6B:50", name = null,
        action = (if (supportsAp) 1 else 0) or (if (supportsP2p) 8 else 0),
        modelId = null, sn = null, channel = null,
    )

    @Test
    fun `resolveWifiTransport matches the classic joinWifi expressions across the full truth table`() {
        var cases = 0
        for (pref in WifiTransport.entries) {
            for (supportsP2p in listOf(true, false)) {
                for (supportsAp in listOf(true, false)) {
                    for (ssid in listOf("DIRECT-go-CFMOTO-0CDC0B", "CFMOTO-166B50")) {
                        for (remembered in listOf(null, "AP", "P2P")) {
                            val q = qr(supportsP2p, supportsAp, ssid)
                            val expected =
                                if (oldClassicUseP2p(q, pref, remembered)) TransportKind.P2P
                                else TransportKind.SOFT_AP
                            assertEquals(
                                "pref=" + pref + " p2p=" + supportsP2p + " ap=" + supportsAp +
                                    " ssid=" + ssid + " remembered=" + remembered,
                                expected,
                                resolveWifiTransport(q, pref, remembered),
                            )
                            cases++
                        }
                    }
                }
            }
        }
        assertEquals("the table must be exhaustive, not sampled", 3 * 2 * 2 * 2 * 3, cases)
    }

    @Test
    fun `the DIRECT- prefix is matched case-insensitively, exactly like classic`() {
        // Classic uses startsWith("DIRECT-", ignoreCase = true); a case-SENSITIVE test would send a
        // lowercase-DIRECT dash down the wrong branch.
        val lower = qr(supportsP2p = true, supportsAp = true, ssid = "direct-go-CFMOTO-0CDC0B")
        assertEquals(TransportKind.P2P, resolveWifiTransport(lower, WifiTransport.AUTO, remembered = null))
    }

    // --- THE OWNER'S BIKE (the CRITICAL case this whole finding is about) ---

    @Test
    fun `the owner's DIRECT- 450NK with learned winner AP resolves to SOFT_AP`() {
        // DIRECT-go-CFMOTO-0CDC0B advertises BOTH P2P and SoftAP, and P2P never forms on this phone/bike —
        // the live path learned "AP". AUTO must therefore skip the dead P2P path (device log, task-7 report).
        val nk450 = qr(supportsP2p = true, supportsAp = true, ssid = "DIRECT-go-CFMOTO-0CDC0B")
        assertEquals(TransportKind.SOFT_AP, resolveWifiTransport(nk450, WifiTransport.AUTO, remembered = "AP"))
        // …and with no memory yet it is P2P, i.e. the memory is doing the work (guards the premise).
        assertEquals(TransportKind.P2P, resolveWifiTransport(nk450, WifiTransport.AUTO, remembered = null))
    }

    @Test
    fun `the owner's DIRECT- 450NK with learned winner AP also PERSISTS as SOFT_AP`() {
        // detectedAtPairing is what BikeMemory.save stores and BikeConnectionFactory.reconcileStoredMode
        // re-derives: the persisted connector — not just the momentary decision — must be SoftAP.
        val nk450 = qr(supportsP2p = true, supportsAp = true, ssid = "DIRECT-go-CFMOTO-0CDC0B")
        val spec = ConnectionSpec.detectedAtPairing(nk450, WifiTransport.AUTO, remembered = "AP")
        assertEquals(TransportKind.SOFT_AP, spec.mode)
        assertEquals("DD:0D:30:16:6B:50", spec.bikeId) // keyed by mac, like every other index
    }

    @Test
    fun `an explicit Setup preference is never overridden by the winner memory`() {
        val direct = qr(supportsP2p = true, supportsAp = true, ssid = "DIRECT-go-CFMOTO-0CDC0B")
        // Classic only consults the winner index under AUTO, so callers pass remembered = null otherwise —
        // detectedAtPairing does the same. The gate lives at the call sites; this pins both outcomes.
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.detectedAtPairing(direct, WifiTransport.AP, null).mode)
        assertEquals(TransportKind.P2P, ConnectionSpec.detectedAtPairing(direct, WifiTransport.P2P, null).mode)
    }

    // --- detectedAtPairing composes the FAMILY decision with the Wi-Fi refinement ---

    @Test
    fun `detectedAtPairing leaves the phone-hosts-the-network kinds alone`() {
        val rieju = QrData(
            ssid = "", pwd = "", auth = null, mac = "DD:0D:30:16:6B:50", name = null,
            action = 128, modelId = "43402", sn = null, channel = null,
        )
        val zontes = rieju.copy(modelId = null)
        // Neither the Setup preference nor the AP/P2P winner index has ever applied to these.
        assertEquals(
            TransportKind.PHONE_HOTSPOT,
            ConnectionSpec.detectedAtPairing(rieju, WifiTransport.P2P, "P2P").mode,
        )
        assertEquals(
            TransportKind.TETHER,
            ConnectionSpec.detectedAtPairing(zontes, WifiTransport.P2P, "P2P").mode,
        )
    }

    @Test
    fun `detectedAtPairing keeps classic's MAC carve-out — P2P-only, no mac, has pwd goes SoftAP`() {
        // Classic: "QR says P2P-only; SSID has no MAC — joining SoftAP first (skip Wi-Fi Direct by MAC)",
        // which exists because those QRs burn ~40s on MAC ERROR first.
        val macless = QrData(
            ssid = "ZT5G-1234", pwd = "12345678", auth = null, mac = null, name = null,
            action = 8, modelId = null, sn = null, channel = null,
        )
        assertEquals(TransportKind.P2P, resolveWifiTransport(macless, WifiTransport.AUTO, null)) // the raw rule
        assertEquals(
            TransportKind.SOFT_AP, // …and the persisted decision applies the carve-out, like classic
            ConnectionSpec.detectedAtPairing(macless, WifiTransport.AUTO, null).mode,
        )
    }

    @Test
    fun `detectedAtPairing keeps a P2P-only QR WITH a mac on P2P (joined by MAC, as classic does)`() {
        val voge = QrData(
            ssid = "Voge-5G-77", pwd = "", auth = null, mac = "DD:0D:30:16:6B:50", name = null,
            action = 8, modelId = null, sn = null, channel = null,
        )
        assertEquals(TransportKind.P2P, ConnectionSpec.detectedAtPairing(voge, WifiTransport.AUTO, null).mode)
    }
}
