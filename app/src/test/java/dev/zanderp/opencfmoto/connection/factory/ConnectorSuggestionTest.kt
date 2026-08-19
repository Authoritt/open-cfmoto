package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.QrData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §1d: the engine-side half of "fail visibly, then RECOMMEND the alternative". Pure — the app decides what
 * to do with the answer; nothing in the connection package acts on it (there is no cascade, by design).
 */
class ConnectorSuggestionTest {

    private fun qr(action: Int, ssid: String = "CFMOTO-1234", pwd: String = "12345678", mac: String? = "DD:0D:30:16:6B:50") =
        QrData(
            ssid = ssid, pwd = pwd, auth = null, mac = mac, name = null,
            action = action, modelId = null, sn = null, channel = null,
        )

    @Test fun `P2P failure suggests SoftAP when the QR also advertises an AP`() =
        assertEquals(
            ConnectorChoice.SOFT_AP,
            suggestAlternativeConnector(qr(action = 9, ssid = "DIRECT-go-CFMOTO-0CDC0B"), TransportKind.P2P),
        )

    @Test fun `P2P failure suggests nothing on a P2P-only QR (no SoftAP creds to offer)`() =
        assertNull(suggestAlternativeConnector(qr(action = 8, ssid = "ZT5G", pwd = ""), TransportKind.P2P))

    @Test fun `SoftAP failure suggests P2P when the QR advertises Wi-Fi Direct`() =
        assertEquals(
            ConnectorChoice.P2P,
            suggestAlternativeConnector(qr(action = 9, ssid = "DIRECT-go-CFMOTO-0CDC0B"), TransportKind.SOFT_AP),
        )

    @Test fun `SoftAP failure suggests nothing on an AP-only QR`() =
        assertNull(suggestAlternativeConnector(qr(action = 1), TransportKind.SOFT_AP))

    @Test fun `Rieju BLE failure suggests the manual tether (same outcome, by hand)`() =
        assertEquals(
            ConnectorChoice.TETHER,
            suggestAlternativeConnector(qr(action = 128, ssid = "", pwd = ""), TransportKind.PHONE_HOTSPOT),
        )

    @Test fun `tether failure suggests the Rieju BLE connector when the QR carries a mac`() =
        assertEquals(
            ConnectorChoice.RIEJU_BLE,
            suggestAlternativeConnector(qr(action = 128, ssid = "", pwd = ""), TransportKind.TETHER),
        )

    @Test fun `tether failure suggests nothing without a BLE mac to pair with`() =
        assertNull(
            suggestAlternativeConnector(
                qr(action = 128, ssid = "MyDash", pwd = "", mac = null),
                TransportKind.TETHER,
            ),
        )

    @Test fun `a suggestion is never the connector that just failed (it would be advice to retry the same thing)`() {
        val both = qr(action = 137, ssid = "DIRECT-go-CFMOTO-0CDC0B", pwd = "") // AP + P2P + phone-hotspot bits
        for (failed in TransportKind.entries) {
            val alt = suggestAlternativeConnector(both, failed)
            assertEquals(
                "suggestion for a failed " + failed + " must differ from it",
                false,
                alt?.name == failed.name,
            )
        }
    }
}
