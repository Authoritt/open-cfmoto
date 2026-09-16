package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.QrData
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionSpecTest {
    private fun qr(action: Int, ssid: String = "", mac: String? = "DD:0D:30:16:6B:50") =
        QrData(
            ssid = ssid, pwd = "", auth = null, mac = mac, name = null,
            action = action, modelId = null, sn = null, channel = null,
        )

    private fun rieju(action: Int = 128, ssid: String = "") =
        QrData(
            ssid = ssid, pwd = "", auth = null, mac = "DD:0D:30:16:6B:50", name = null,
            action = action, modelId = "43402", sn = null, channel = null,
        )

    @Test fun `action 128 with bm and the Rieju modelid maps to PHONE_HOTSPOT and keeps bleMac`() {
        val s = ConnectionSpec.fromQr(rieju())
        assertEquals(TransportKind.PHONE_HOTSPOT, s.mode)
        assertEquals("DD:0D:30:16:6B:50", s.bleMac)
    }

    // --- the phone-hosts-the-network split: BLE (Rieju) vs rider-tether (Zontes / opaque CARBIT) ---

    @Test fun `Zontes-shape action 128 with bm but NO modelid maps to TETHER`() {
        val s = ConnectionSpec.fromQr(qr(action = 128))
        assertEquals(TransportKind.TETHER, s.mode)
        assertEquals("DD:0D:30:16:6B:50", s.bleMac)
    }

    @Test fun `phone-hotspot QR with an unknown modelid maps to TETHER`() {
        val unknown = QrData(
            ssid = "", pwd = "", auth = null, mac = "DD:0D:30:16:6B:50", name = null,
            action = 128, modelId = "12345", sn = null, channel = null,
        )
        assertEquals(TransportKind.TETHER, ConnectionSpec.fromQr(unknown).mode)
    }

    @Test fun `blank-ssid mac-fallback QR without a modelid maps to TETHER`() {
        // QrData.supportsPhoneHotspot also fires on blank ssid + mac (no bit7); still a tether bike.
        assertEquals(TransportKind.TETHER, ConnectionSpec.fromQr(qr(action = 0)).mode)
    }

    @Test fun `blank-ssid mac-fallback QR WITH the Rieju modelid maps to PHONE_HOTSPOT`() =
        assertEquals(TransportKind.PHONE_HOTSPOT, ConnectionSpec.fromQr(rieju(action = 0)).mode)

    @Test fun `a phone-hotspot QR with no mac maps to TETHER (nothing to pair with over BLE)`() {
        val noMac = QrData(
            ssid = "MyDash", pwd = "", auth = null, mac = null, name = null,
            action = 128, modelId = "43402", sn = null, channel = null,
        )
        assertEquals(TransportKind.TETHER, ConnectionSpec.fromQr(noMac).mode)
    }

    @Test fun `action bit3 maps to P2P`() =
        assertEquals(TransportKind.P2P, ConnectionSpec.fromQr(qr(action = 8, ssid = "DIRECT-ab")).mode)

    @Test fun `action bit0 maps to SOFT_AP`() =
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(qr(action = 1, ssid = "CFMOTO")).mode)

    // --- §1c: two fromQr mis-mappings that sent real bikes to a connector that cannot work ---

    @Test fun `a NON-DIRECT P2P-only QR maps to P2P — classic joins by MAC, SoftAP would have an empty PSK`() {
        // The Voge-5G / ZT5G class: bit3 set, no AP bit, an ordinary SSID and no password. Mapping it to
        // SOFT_AP handed SoftApTransport an empty PSK — a guaranteed failure — while classic joins Wi-Fi
        // Direct by MAC.
        assertEquals(TransportKind.P2P, ConnectionSpec.fromQr(qr(action = 8, ssid = "ZT5G-1234")).mode)
        assertEquals(TransportKind.P2P, ConnectionSpec.fromQr(qr(action = 8, ssid = "Voge-5G-77")).mode)
    }

    @Test fun `a P2P bit alongside a normal AP ssid still maps to SOFT_AP`() {
        // Regression guard for the clause above: bit3 + bit0 with a non-DIRECT ssid is a SoftAP bike.
        assertEquals(
            TransportKind.SOFT_AP,
            ConnectionSpec.fromQr(qr(action = 9, ssid = "CFMOTO-166B50")).mode,
        )
    }

    @Test fun `the DIRECT- prefix is matched case-insensitively (classic's exact form)`() {
        // Was `startsWith("DIRECT")`, case-SENSITIVE: a lowercase-DIRECT dash that advertises both P2P and
        // SoftAP fell to SOFT_AP here while classic went P2P — the two paths disagreeing about one bike.
        assertEquals(
            TransportKind.P2P,
            ConnectionSpec.fromQr(qr(action = 9, ssid = "direct-go-CFMOTO-0CDC0B")).mode,
        )
        assertEquals(
            TransportKind.P2P,
            ConnectionSpec.fromQr(qr(action = 9, ssid = "DIRECT-go-CFMOTO-0CDC0B")).mode,
        )
    }

    @Test fun `bit7 QR carrying a SoftAP password maps to SOFT_AP (matches classic pwd-empty gate)`() {
        // A bit7 dash that ALSO advertises a password is a SoftAP bike classically (CfmotoConnect gates
        // phone-hotspot on supportsPhoneHotspot && pwd.isEmpty()); the persisted spec must agree, not diverge.
        val withCreds = QrData(
            ssid = "CFMOTO-9", pwd = "secret", auth = null, mac = "DD:0D:30:16:6B:50", name = null,
            action = 128, modelId = null, sn = null, channel = null,
        )
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(withCreds).mode)
    }

    @Test fun `json round-trips`() {
        val s = ConnectionSpec.fromQr(qr(action = 128))
        assertEquals(s, ConnectionSpec.fromJson(s.toJson()))
    }

    // --- persisted specs must survive the arrival of TransportKind.TETHER (nothing re-pairs) ---

    @Test fun `a spec saved BEFORE TETHER existed still deserializes`() {
        // Verbatim shape of a spec written by the pre-TETHER build (a Zontes paired back then).
        val legacy = """
            {"bikeId":"DD:0D:30:16:6B:50","mode":"PHONE_HOTSPOT","profile":"generic",
             "ssid":"PHONE-HOTSPOT-166B50","pwd":"","bleMac":"DD:0D:30:16:6B:50",
             "lastEndpointHint":"192.168.43.24"}
        """.trimIndent()
        val spec = ConnectionSpec.fromJson(legacy)
        assertEquals(TransportKind.PHONE_HOTSPOT, spec.mode) // read back as stored; healing is the factory's job
        assertEquals("DD:0D:30:16:6B:50", spec.bikeId)
        assertEquals("192.168.43.24", spec.lastEndpointHint)
    }

    @Test fun `every saved SoftAP or P2P spec still deserializes unchanged`() {
        for (mode in listOf(TransportKind.SOFT_AP, TransportKind.P2P)) {
            val saved = ConnectionSpec(bikeId = "b", mode = mode, ssid = "CFMOTO", pwd = "12345678")
            assertEquals(saved, ConnectionSpec.fromJson(saved.toJson()))
        }
    }

    @Test fun `a TETHER spec round-trips`() {
        val s = ConnectionSpec.fromQr(qr(action = 128)).copy(lastEndpointHint = "192.168.43.24")
        assertEquals(TransportKind.TETHER, s.mode) // guard the premise
        assertEquals(s, ConnectionSpec.fromJson(s.toJson()))
    }

    @Test fun `an UNKNOWN stored mode degrades to SOFT_AP instead of blowing up the connect path`() {
        // Gson leaves an unknown enum name null (a spec written by a newer build, or a corrupt pref); the
        // non-null Kotlin field would only surface that as an NPE inside selectTransport.
        val future = """{"bikeId":"b","mode":"WARP_DRIVE","profile":"generic","ssid":"CFMOTO"}"""
        val spec = ConnectionSpec.fromJson(future)
        assertEquals(TransportKind.SOFT_AP, spec.mode)
        assertEquals("CFMOTO", spec.ssid)
    }
}
