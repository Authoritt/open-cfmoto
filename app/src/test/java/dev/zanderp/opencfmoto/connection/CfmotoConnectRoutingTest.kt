package dev.zanderp.opencfmoto.connection

import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.fromQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Rieju's Carbit model id (QR `modelid=43402`) — the only phone-hotspot model on the factory path. */
private const val RIEJU = "43402"

/**
 * Guards the [CfmotoConnect.joinWifi] routing decision that sends the Rieju to the NEW
 * `PhoneHotspotTransport`. The predicate is Rieju-model-SCOPED, not QR-shape-scoped: other Carbit-family
 * phone-hotspot bikes (Zontes, opaque `CARBIT` tokens with no `modelid`) share the exact shape
 * (`action=128` + `bm=` mac + empty pwd) but work TODAY on the classic tether [CfmotoConnect.joinPhoneHotspot]
 * path, so they MUST resolve to `false` here (regression guard). Pure: [CfmotoConnect] holds only `const`/
 * stateless members, so touching [CfmotoConnect.isBleHotspot] never spins up Android; [QrData] is a plain
 * data class.
 */
class CfmotoConnectRoutingTest {

    private fun qr(
        action: Int,
        ssid: String = "",
        pwd: String = "",
        mac: String? = "DD:0D:30:16:6B:50",
        modelId: String? = null,
    ) = QrData(
        ssid = ssid, pwd = pwd, auth = null, mac = mac, name = null,
        action = action, modelId = modelId, sn = null, channel = null,
    )

    @Test fun `Rieju action=128 with a mac, empty pwd and modelid 43402 is a BLE hotspot`() {
        assertTrue(CfmotoConnect.isBleHotspot(qr(action = 128, modelId = RIEJU)))
    }

    @Test fun `Rieju blank-ssid QR (mac fallback) with modelid 43402 is a BLE hotspot`() {
        // QrData.supportsPhoneHotspot also fires on blank ssid + mac (no bit7); still gated by the modelid.
        assertTrue(CfmotoConnect.isBleHotspot(qr(action = 0, modelId = RIEJU)))
    }

    // --- REGRESSION GUARD: Zontes / opaque CARBIT phone-hotspot bikes stay on the classic tether path ---

    @Test fun `Zontes-shape opaque CARBIT (action=128 + mac, NO modelid) is NOT a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 128, modelId = null)))
    }

    @Test fun `phone-hotspot with a non-Rieju modelid is NOT a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 128, modelId = "12345")))
    }

    // --- Other conjuncts still gate even WITH the Rieju modelid ---

    @Test fun `Rieju-modelid QR carrying a SoftAP pwd is NOT a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 128, ssid = "CFMOTO-9", pwd = "secret", modelId = RIEJU)))
    }

    @Test fun `Rieju-modelid QR without a mac is NOT a BLE hotspot (manual assist)`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 128, ssid = "MyDash", mac = null, modelId = RIEJU)))
    }

    @Test fun `SoftAP QR is not a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 1, ssid = "CFMOTO", pwd = "12345678", mac = null, modelId = RIEJU)))
    }

    @Test fun `P2P DIRECT QR is not a BLE hotspot`() {
        assertFalse(CfmotoConnect.isBleHotspot(qr(action = 8, ssid = "DIRECT-ab", modelId = RIEJU)))
    }

    // --- ONE DEFINITION: the routing predicate and the persisted spec.mode can never disagree ---
    // `CfmotoConnect.isBleHotspot` and `ConnectionSpec.fromQr` both call `isBleHotspotQr`. These pin the two
    // together: whichever connector joinWifi picks for a phone-hotspot QR, the stored mode picks the same one.

    @Test fun `Rieju QR — isBleHotspot true AND fromQr PHONE_HOTSPOT`() {
        val q = qr(action = 128, modelId = RIEJU)
        assertTrue(CfmotoConnect.isBleHotspot(q))
        assertEquals(TransportKind.PHONE_HOTSPOT, ConnectionSpec.fromQr(q).mode)
    }

    @Test fun `Zontes-shape QR — isBleHotspot false AND fromQr TETHER`() {
        val q = qr(action = 128, modelId = null)
        assertFalse(CfmotoConnect.isBleHotspot(q))
        assertEquals(TransportKind.TETHER, ConnectionSpec.fromQr(q).mode)
    }

    @Test fun `non-Rieju modelid — isBleHotspot false AND fromQr TETHER`() {
        val q = qr(action = 128, modelId = "12345")
        assertFalse(CfmotoConnect.isBleHotspot(q))
        assertEquals(TransportKind.TETHER, ConnectionSpec.fromQr(q).mode)
    }

    @Test fun `a phone-hotspot QR carrying a SoftAP pwd is neither — both sides say SoftAP`() {
        val q = qr(action = 128, ssid = "CFMOTO-9", pwd = "secret", modelId = RIEJU)
        assertFalse(CfmotoConnect.isBleHotspot(q))
        assertEquals(TransportKind.SOFT_AP, ConnectionSpec.fromQr(q).mode)
    }

    // --- THE ANDROID-AUTO GATE: a Rieju QR must NOT bypass the AA hand-off ---
    // The `isBleHotspot` branch used to sit ABOVE every preferFactory/gateOnAaSteady guard, so
    // `startAaConnect` (gateOnAaSteady = true) routed a Rieju into PhoneHotspotTransport and skipped the
    // classic joinPhoneHotspot(..., gateOnAaSteady = true) -> BikeLink.markP2pReady deferral — the prober
    // then raced AA video. Gated now exactly like the tether branch already was.

    @Test fun `a Rieju QR routes to the factory BLE connector OFF the Android-Auto path`() {
        assertTrue(CfmotoConnect.routesToBleHotspotConnector(qr(action = 128, modelId = RIEJU), gateOnAaSteady = false))
    }

    @Test fun `a Rieju QR does NOT route to the factory on the Android-Auto path (falls through to classic)`() {
        assertFalse(CfmotoConnect.routesToBleHotspotConnector(qr(action = 128, modelId = RIEJU), gateOnAaSteady = true))
    }

    @Test fun `the AA gate does not change the answer for non-Rieju QRs (still classic on both paths)`() {
        val zontes = qr(action = 128, modelId = null)
        assertFalse(CfmotoConnect.routesToBleHotspotConnector(zontes, gateOnAaSteady = false))
        assertFalse(CfmotoConnect.routesToBleHotspotConnector(zontes, gateOnAaSteady = true))
    }

    @Test fun `the AA gate is the ONLY difference from isBleHotspot (off-AA the two agree everywhere)`() {
        val cases = listOf(
            qr(action = 128, modelId = RIEJU),
            qr(action = 0, modelId = RIEJU),
            qr(action = 128, modelId = null),
            qr(action = 128, modelId = "12345"),
            qr(action = 128, ssid = "CFMOTO-9", pwd = "secret", modelId = RIEJU),
            qr(action = 1, ssid = "CFMOTO", pwd = "12345678", mac = null, modelId = RIEJU),
            qr(action = 8, ssid = "DIRECT-ab", modelId = RIEJU),
        )
        for (q in cases) {
            assertEquals(
                "off the AA path the gate must be transparent for ssid=" + q.ssid + " action=" + q.action,
                CfmotoConnect.isBleHotspot(q),
                CfmotoConnect.routesToBleHotspotConnector(q, gateOnAaSteady = false),
            )
            assertFalse(
                "on the AA path nothing may reach the factory: ssid=" + q.ssid + " action=" + q.action,
                CfmotoConnect.routesToBleHotspotConnector(q, gateOnAaSteady = true),
            )
        }
    }
}
