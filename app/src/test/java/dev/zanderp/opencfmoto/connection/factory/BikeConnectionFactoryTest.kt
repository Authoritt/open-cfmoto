package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.connection.factory.transport.P2pTransport
import dev.zanderp.opencfmoto.connection.factory.transport.PhoneHotspotTransport
import dev.zanderp.opencfmoto.connection.factory.transport.SoftApTransport
import dev.zanderp.opencfmoto.connection.factory.transport.TetherTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Rieju's Carbit model id (QR `modelid=43402`) — the only phone-hotspot model on the BLE connector. */
private const val RIEJU = "43402"

/**
 * Task 4: `BikeConnectionFactory.selectTransport` maps each [TransportKind] to its Layer-1 transport.
 * Pure and Android-free — only touches `selectTransport`, never `create` (which references Android types).
 */
class BikeConnectionFactoryTest {

    private fun spec(kind: TransportKind) = ConnectionSpec(bikeId = "test-bike", mode = kind)

    private fun qr(action: Int, ssid: String = "", pwd: String = "", modelId: String? = null) =
        QrData(
            ssid = ssid, pwd = pwd, auth = null, mac = "DD:0D:30:16:6B:50", name = null,
            action = action, modelId = modelId, sn = null, channel = null,
        )

    @Test
    fun `SOFT_AP selects SoftApTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.SOFT_AP))
        assertTrue("SOFT_AP must select SoftApTransport but was ${t::class.simpleName}", t is SoftApTransport)
    }

    @Test
    fun `P2P selects P2pTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.P2P))
        assertTrue("P2P must select P2pTransport but was ${t::class.simpleName}", t is P2pTransport)
    }

    @Test
    fun `PHONE_HOTSPOT selects PhoneHotspotTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.PHONE_HOTSPOT))
        assertTrue(
            "PHONE_HOTSPOT must select PhoneHotspotTransport but was ${t::class.simpleName}",
            t is PhoneHotspotTransport,
        )
    }

    @Test
    fun `TETHER selects TetherTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.TETHER))
        assertTrue(
            "TETHER must select TetherTransport but was ${t::class.simpleName}",
            t is TetherTransport,
        )
    }

    // Reconnect-parity caps (review I2 / flip-work §2): SoftAP/P2P retry forever to match classic; the two
    // phone-hosts-the-network connectors (Rieju BLE handoff, rider-driven tether) keep the default give-up
    // caps — interactive one-shot flows, not daily-ride reconnect paths.
    @Test fun `retryCapsFor gives SoftAP effectively-infinite caps`() =
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, BikeConnectionFactory.retryCapsFor(TransportKind.SOFT_AP))

    @Test fun `retryCapsFor gives P2P effectively-infinite caps`() =
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, BikeConnectionFactory.retryCapsFor(TransportKind.P2P))

    @Test fun `retryCapsFor keeps the default caps for PHONE_HOTSPOT`() =
        assertEquals(MAX_ATTEMPTS to FLAP_MAX_FAILURES, BikeConnectionFactory.retryCapsFor(TransportKind.PHONE_HOTSPOT))

    @Test fun `retryCapsFor keeps the default caps for TETHER`() =
        assertEquals(MAX_ATTEMPTS to FLAP_MAX_FAILURES, BikeConnectionFactory.retryCapsFor(TransportKind.TETHER))

    // --- reconcileStoredMode: specs persisted BEFORE TransportKind.TETHER existed ---

    @Test fun `reconcileStoredMode heals a pre-TETHER Zontes spec (AUTO, stored PHONE_HOTSPOT, no modelid)`() {
        // Paired on a build where fromQr mapped EVERY phone-hotspot QR to PHONE_HOTSPOT; today that stored
        // value would select the Rieju BLE connector for a tether bike (the exact regression to avoid).
        val stored = ConnectionSpec(bikeId = "bike", mode = TransportKind.PHONE_HOTSPOT)
        val healed = BikeConnectionFactory.reconcileStoredMode(stored, qr(action = 128), ConnectorChoice.AUTO)
        assertEquals(TransportKind.TETHER, healed.mode)
    }

    @Test fun `reconcileStoredMode leaves a Rieju spec on PHONE_HOTSPOT`() {
        val stored = ConnectionSpec(bikeId = "bike", mode = TransportKind.PHONE_HOTSPOT)
        val out = BikeConnectionFactory.reconcileStoredMode(
            stored, qr(action = 128, modelId = RIEJU), ConnectorChoice.AUTO,
        )
        assertEquals(TransportKind.PHONE_HOTSPOT, out.mode)
    }

    @Test fun `reconcileStoredMode never overrides a rider pin`() {
        // RIEJU_BLE deliberately forces PHONE_HOTSPOT even for a QR whose modelid isn't known — a pin is law.
        val stored = ConnectionSpec(bikeId = "bike", mode = TransportKind.PHONE_HOTSPOT)
        val out = BikeConnectionFactory.reconcileStoredMode(stored, qr(action = 128), ConnectorChoice.RIEJU_BLE)
        assertEquals(TransportKind.PHONE_HOTSPOT, out.mode)
    }

    @Test fun `reconcileStoredMode leaves SoftAP and P2P specs untouched`() {
        val ap = ConnectionSpec(bikeId = "bike", mode = TransportKind.SOFT_AP)
        val p2p = ConnectionSpec(bikeId = "bike", mode = TransportKind.P2P)
        assertEquals(ap, BikeConnectionFactory.reconcileStoredMode(ap, qr(action = 1, ssid = "CFMOTO", pwd = "x"), ConnectorChoice.AUTO))
        assertEquals(p2p, BikeConnectionFactory.reconcileStoredMode(p2p, qr(action = 8, ssid = "DIRECT-ab"), ConnectorChoice.AUTO))
    }

    @Test fun `reconcileStoredMode keeps every other stored field when healing the mode`() {
        val stored = ConnectionSpec(
            bikeId = "bike",
            mode = TransportKind.PHONE_HOTSPOT,
            ssid = "PHONE-HOTSPOT-166B50",
            bleMac = "DD:0D:30:16:6B:50",
            lastEndpointHint = "192.168.43.24",
        )
        val healed = BikeConnectionFactory.reconcileStoredMode(stored, qr(action = 128), ConnectorChoice.AUTO)
        assertEquals(stored.copy(mode = TransportKind.TETHER), healed)
    }
}
