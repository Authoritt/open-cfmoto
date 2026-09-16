package dev.zanderp.opencfmoto.connection.factory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two per-connector policy decisions, pinned as pure facts (`ConnectorPolicy.kt`).
 *
 * The field bug behind [autoConnectGateFor]: auto-connect asked ONE question of every bike — "is the BIKE's
 * SSID in a Wi-Fi scan?" — which the phone-hosts-the-network connectors can never answer, because the phone
 * creates the network. Real Rieju log: `[auto-fg] 'Phone hotspot (16:6b:50)' not in range — will retry on
 * resume`, forever.
 */
class ConnectorPolicyTest {

    // --- the in-range gate: WHICH bikes should be asked "is your Wi-Fi in range?" ---

    @Test fun `SoftAP keeps the bike-SSID range gate (the dash really does host the network)`() =
        assertEquals(AutoConnectGate.BIKE_SSID_IN_RANGE, autoConnectGateFor(TransportKind.SOFT_AP))

    @Test fun `P2P keeps the bike-SSID range gate`() =
        assertEquals(AutoConnectGate.BIKE_SSID_IN_RANGE, autoConnectGateFor(TransportKind.P2P))

    @Test fun `the BLE connector is never gated on a bike SSID that cannot exist`() {
        // The phone creates the network for this bike, so scanning for the BIKE's SSID answers "absent"
        // every time and the bike would never auto-connect. It gets a budget instead of a scan.
        assertEquals(AutoConnectGate.ONCE_PER_SESSION, autoConnectGateFor(TransportKind.PHONE_HOTSPOT))
    }

    @Test fun `the tether connector is rider-initiated, not merely out of range`() {
        // It needs the rider to switch the phone hotspot on and answer the assist dialog: an automatic
        // attempt could only pop a modal nobody asked for.
        assertEquals(AutoConnectGate.RIDER_ONLY, autoConnectGateFor(TransportKind.TETHER))
    }

    @Test fun `every transport kind decides its own auto-connect gate`() {
        for (kind in TransportKind.entries) assertNotNull("no gate decided for $kind", autoConnectGateFor(kind))
    }

    @Test fun `only the connectors whose network the DASH hosts scan for its SSID`() {
        // The property that matters, stated once: the scan gate applies exactly to the dash-hosted networks.
        for (kind in TransportKind.entries) {
            val scans = autoConnectGateFor(kind) == AutoConnectGate.BIKE_SSID_IN_RANGE
            val dashHostsTheNetwork = kind == TransportKind.SOFT_AP || kind == TransportKind.P2P
            assertEquals("scan gate for $kind", dashHostsTheNetwork, scans)
        }
    }

    // --- Wi-Fi Direct: WHICH bikes need the Android 13+ nearby-devices grant ---

    @Test fun `the BLE connector uses Wi-Fi Direct (it creates the group)`() =
        assertTrue(usesWifiDirect(TransportKind.PHONE_HOTSPOT))

    @Test fun `the P2P connector uses Wi-Fi Direct (it joins the dash's group)`() =
        assertTrue(usesWifiDirect(TransportKind.P2P))

    @Test fun `SoftAP does not use Wi-Fi Direct, so its riders are never asked for the grant`() =
        assertFalse(usesWifiDirect(TransportKind.SOFT_AP))

    @Test fun `the tether connector does not use Wi-Fi Direct either`() =
        assertFalse(usesWifiDirect(TransportKind.TETHER))
}
