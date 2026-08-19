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

    @Test fun `action 128 with bm maps to PHONE_HOTSPOT and keeps bleMac`() {
        val s = ConnectionSpec.fromQr(qr(action = 128))
        assertEquals(TransportKind.PHONE_HOTSPOT, s.mode)
        assertEquals("DD:0D:30:16:6B:50", s.bleMac)
    }

    @Test fun `action bit3 maps to P2P`() =
        assertEquals(TransportKind.P2P, ConnectionSpec.fromQr(qr(action = 8, ssid = "DIRECT-ab")).mode)

    @Test fun `action bit0 maps to SOFT_AP`() =
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(qr(action = 1, ssid = "CFMOTO")).mode)

    @Test fun `json round-trips`() {
        val s = ConnectionSpec.fromQr(qr(action = 128))
        assertEquals(s, ConnectionSpec.fromJson(s.toJson()))
    }
}
