package dev.zanderp.opencfmoto.connection.factory.transport

import android.net.wifi.p2p.WifiP2pManager
import dev.zanderp.opencfmoto.connection.factory.RiderFacingFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Wi-Fi Direct pre-flight: the reason-code vocabulary and the "what stops us, and what
 * do we tell the rider" mapping.
 *
 * Written from a real failure (Rieju owner, 2026-08-19): `createGroup` was rejected twice, ~11 ms apart, and
 * the whole log line was `createGroup failed: ERROR`. `ERROR` is `WifiP2pManager`'s generic 0 — the same
 * answer a missing `NEARBY_WIFI_DEVICES` grant produces on Android 13+ — so the app could not tell "your
 * phone's Wi-Fi is off" from "you never granted nearby devices" from "this phone can't do Wi-Fi Direct".
 * These are the three verdicts, each with its own rider-facing sentence.
 */
class WifiDirectPreflightTest {

    private val msgs = WifiDirectMessages(
        wifiOff = "turn wifi on",
        nearbyPermission = "allow nearby devices",
        unsupported = "this phone cannot",
    )

    // --- reason code -> name (the line the field log did not have) ---

    @Test fun `the generic zero is named AND numbered`() =
        assertEquals("ERROR (0)", WifiDirectPreflight.reasonName(WifiP2pManager.ERROR))

    @Test fun `P2P_UNSUPPORTED is named`() =
        assertEquals("P2P_UNSUPPORTED (1)", WifiDirectPreflight.reasonName(WifiP2pManager.P2P_UNSUPPORTED))

    @Test fun `BUSY is named`() =
        assertEquals("BUSY (2)", WifiDirectPreflight.reasonName(WifiP2pManager.BUSY))

    @Test fun `NO_SERVICE_REQUESTS is named`() =
        assertEquals("NO_SERVICE_REQUESTS (3)", WifiDirectPreflight.reasonName(WifiP2pManager.NO_SERVICE_REQUESTS))

    @Test fun `a code the platform adds later still prints its number`() =
        assertEquals("UNKNOWN (7)", WifiDirectPreflight.reasonName(7))

    // --- which rejections are a verdict about the PHONE ---

    @Test fun `only P2P_UNSUPPORTED says the phone itself cannot do this`() {
        assertEquals(WifiDirectBlocker.UNSUPPORTED, WifiDirectPreflight.blockerForReason(WifiP2pManager.P2P_UNSUPPORTED))
    }

    @Test fun `BUSY is not a verdict about the phone (it is the stale-group case, repaired once)`() =
        assertNull(WifiDirectPreflight.blockerForReason(WifiP2pManager.BUSY))

    @Test fun `the generic ERROR is not a verdict about the phone either`() =
        assertNull(WifiDirectPreflight.blockerForReason(WifiP2pManager.ERROR))

    // --- the precondition decision ---

    @Test fun `all three preconditions met is a clean pass`() =
        assertNull(WifiDirectPreflight.blockerOf(wifiEnabled = true, nearbyGranted = true, wifiDirectSupported = true))

    @Test fun `Wi-Fi off is reported first — it is the fastest thing the rider can fix`() {
        assertEquals(
            WifiDirectBlocker.WIFI_OFF,
            WifiDirectPreflight.blockerOf(wifiEnabled = false, nearbyGranted = false, wifiDirectSupported = false),
        )
    }

    @Test fun `the missing nearby-devices grant is caught BEFORE the framework answers ERROR`() {
        assertEquals(
            WifiDirectBlocker.NEARBY_PERMISSION,
            WifiDirectPreflight.blockerOf(wifiEnabled = true, nearbyGranted = false, wifiDirectSupported = true),
        )
    }

    @Test fun `a phone without Wi-Fi Direct is told plainly`() {
        assertEquals(
            WifiDirectBlocker.UNSUPPORTED,
            WifiDirectPreflight.blockerOf(wifiEnabled = true, nearbyGranted = true, wifiDirectSupported = false),
        )
    }

    // --- reason -> what the rider reads ---

    @Test fun `each blocker maps to its own rider-facing sentence`() {
        assertEquals("turn wifi on", WifiDirectPreflight.riderMessage(WifiDirectBlocker.WIFI_OFF, msgs))
        assertEquals("allow nearby devices", WifiDirectPreflight.riderMessage(WifiDirectBlocker.NEARBY_PERMISSION, msgs))
        assertEquals("this phone cannot", WifiDirectPreflight.riderMessage(WifiDirectBlocker.UNSUPPORTED, msgs))
    }

    @Test fun `with no injected text the rider message is null and the technical one still stands`() {
        for (b in WifiDirectBlocker.entries) {
            assertNull(WifiDirectPreflight.riderMessage(b, WifiDirectMessages()))
            assertTrue("no technical text for $b", WifiDirectPreflight.technical(b).isNotBlank())
        }
    }

    @Test fun `the log text names the cause precisely (support reads this, not the rider)`() {
        assertTrue(
            WifiDirectPreflight.technical(WifiDirectBlocker.NEARBY_PERMISSION).contains("NEARBY_WIFI_DEVICES"),
        )
        assertTrue(WifiDirectPreflight.technical(WifiDirectBlocker.WIFI_OFF).contains("Wi-Fi is OFF"))
    }

    // --- the failure carries its own words to the rider ---

    @Test fun `a pre-flight failure is a RiderFacingFailure, so it overrides the generic re-scan text`() {
        val e = dev.zanderp.opencfmoto.connection.factory.TransportUnavailableException(
            WifiDirectPreflight.riderMessage(WifiDirectBlocker.WIFI_OFF, msgs),
            WifiDirectPreflight.technical(WifiDirectBlocker.WIFI_OFF),
        )
        val named: RiderFacingFailure = e // compile-time proof of the contract the driver keys on
        assertEquals("turn wifi on", named.riderMessage)
        // ...while the technical cause stays on the exception itself, which is what the log prints.
        assertTrue(e.message!!.contains("Wi-Fi is OFF"))
    }
}
