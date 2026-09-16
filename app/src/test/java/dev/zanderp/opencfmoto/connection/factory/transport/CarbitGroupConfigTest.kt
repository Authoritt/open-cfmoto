package dev.zanderp.opencfmoto.connection.factory.transport

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the Carbit-shaped Wi-Fi Direct group: the credentials the official app generates and the
 * framework-legal name we fall back to.
 *
 * Shape matters because it is the ONE part of the group a dash could plausibly recognise on sight —
 * `WifiApUtils` in `net.easyconn.carman.wws` makes `"Easyconn_AP-" + 4 digits` with a 12-character
 * `[a-z0-9]` passphrase, and until today we shipped whatever `createGroup` invented (`DIRECT-fa-<phone>`).
 * The Android-bound half ([CarbitGroupConfig.build], which touches `WifiP2pConfig`) is device-only; only
 * these two functions can be proven off the bike.
 */
class CarbitGroupConfigTest {

    @Test fun `the ssid is the official app's Easyconn_AP- plus exactly four digits`() {
        repeat(200) {
            val ssid = CarbitGroupConfig.creds(Random(it)).ssid
            assertTrue("unexpected ssid shape: '$ssid'", Regex("^Easyconn_AP-\\d{4}$").matches(ssid))
        }
    }

    @Test fun `the passphrase is twelve lowercase alphanumerics`() {
        repeat(200) {
            val pwd = CarbitGroupConfig.creds(Random(it)).passphrase
            assertEquals("WPA2 needs 8..63; the official app uses 12", 12, pwd.length)
            assertTrue("unexpected passphrase alphabet: '$pwd'", Regex("^[a-z0-9]{12}$").matches(pwd))
        }
    }

    @Test fun `two groups do not share credentials`() {
        val a = CarbitGroupConfig.creds(Random(1))
        val b = CarbitGroupConfig.creds(Random(2))
        assertTrue("a fixed passphrase would be a permanent key on the rider's phone", a.passphrase != b.passphrase)
    }

    @Test fun `the fallback name satisfies the framework's DIRECT-xy rule and still carries the ssid`() {
        val name = CarbitGroupConfig.legalNetworkName("Easyconn_AP-1234")
        // WifiP2pConfig.Builder.setNetworkName enforces exactly this prefix rule on stock Android.
        assertTrue("'$name' must start with DIRECT- plus two alphanumerics", Regex("^DIRECT-[a-zA-Z0-9]{2}.*").matches(name))
        assertTrue("the rider-visible name should still say which network this is", name.contains("Easyconn_AP-1234"))
        assertTrue("an SSID is at most 32 bytes", name.toByteArray(Charsets.UTF_8).size <= 32)
    }

    @Test fun `the fallback name stays legal even for an ssid that offers no alphanumerics`() {
        val name = CarbitGroupConfig.legalNetworkName("___")
        assertTrue("'$name' must still be legal", Regex("^DIRECT-[a-zA-Z0-9]{2}.*").matches(name))
    }

    @Test fun `the same ssid always yields the same fallback name`() {
        val ssid = CarbitGroupConfig.creds(Random(7)).ssid
        assertEquals(CarbitGroupConfig.legalNetworkName(ssid), CarbitGroupConfig.legalNetworkName(ssid))
    }
}
