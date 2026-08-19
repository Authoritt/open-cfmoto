package dev.zanderp.opencfmoto.connection

import dev.zanderp.opencfmoto.QrData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the [CfmotoConnect.joinWifi] routing decision that sends the Rieju to the NEW
 * `PhoneHotspotTransport`: a BLE-capable phone-hotspot QR (`supportsPhoneHotspot && pwd.isEmpty()` AND a
 * `bm=` mac) → factory; anything else (SoftAP/P2P, or a phone-hotspot QR with no mac) → the classic path.
 * Pure: [CfmotoConnect] holds only `const val`s, so touching [CfmotoConnect.isBleHotspot] never spins up
 * Android; [QrData] is a plain data class.
 */
class CfmotoConnectRoutingTest {

    private fun qr(action: Int, ssid: String = "", pwd: String = "", mac: String? = "DD:0D:30:16:6B:50") =
        QrData(
            ssid = ssid, pwd = pwd, auth = null, mac = mac, name = null,
            action = action, modelId = null, sn = null, channel = null,
        )

    @Test fun `Rieju action=128 with a bm mac and no pwd is a BLE hotspot`() {
        assertTrue(CfmotoConnect.isBleHotspot(qr(action = 128, ssid = "", mac = "DD:0D:30:16:6B:50")))
    }

    @Test fun `blank-ssid QR with only a mac (no bit7) is a BLE hotspot via the mac fallback`() {
        // QrData.supportsPhoneHotspot also fires on blank ssid + mac; pwd empty + mac present -> BLE hotspot.
        assertTrue(CfmotoConnect.isBleHotspot(qr(action = 0, ssid = "", mac = "DD:0D:30:16:6B:50")))
    }

    @Test fun `phone-hotspot QR carrying a SoftAP pwd is NOT a BLE hotspot`() {
        // bit7 + a password is a SoftAP bike (classic gate is supportsPhoneHotspot && pwd.isEmpty()).
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 128, ssid = "CFMOTO-9", pwd = "secret")))
    }

    @Test fun `phone-hotspot QR without a mac is NOT a BLE hotspot (manual assist)`() {
        // action=128 but no bm= -> nothing to program over BLE -> stays on joinPhoneHotspot.
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 128, ssid = "MyDash", mac = null)))
    }

    @Test fun `SoftAP QR is not a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 1, ssid = "CFMOTO", pwd = "12345678", mac = null)))
    }

    @Test fun `P2P DIRECT QR is not a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 8, ssid = "DIRECT-ab")))
    }
}
