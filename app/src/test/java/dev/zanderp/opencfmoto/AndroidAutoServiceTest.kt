package dev.zanderp.opencfmoto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoServiceTest {
    @Test
    fun bikeTransportIsLiveForApOrP2p() {
        assertFalse(AndroidAutoService.bikeTransportConnected(false, false, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(true, false, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(false, true, false))
        assertTrue(AndroidAutoService.bikeTransportConnected(true, true, false))
    }

    /**
     * The transport the phone HOSTS (classic `joinPhoneHotspot`, still the AA route for those bikes)
     * reports neither an AP Network nor a Wi-Fi Direct group, so the two upstream signals are false for
     * the whole ride. Reading that as "bike gone" parked a live dash after GRACE_MS.
     */
    @Test
    fun phoneHostedTransportIsLiveWithNoWifiLayerSignal() {
        assertTrue(AndroidAutoService.bikeTransportConnected(false, false, true))
    }
}
