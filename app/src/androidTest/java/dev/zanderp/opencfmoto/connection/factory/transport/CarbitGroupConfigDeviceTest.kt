package dev.zanderp.opencfmoto.connection.factory.transport

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WHICH rung of [CarbitGroupConfig]'s ladder this phone allows — the one question about the Carbit-shaped
 * group that no plain-JVM test can answer, because it depends on this Android's `WifiP2pConfig.Builder`
 * validation and its hidden-API enforcement.
 *
 * Touches no radio: it builds a config object and nothing else. No group is created, no Wi-Fi is changed —
 * safe to run on anybody's phone. The verdict goes to logcat under [TAG] and is asserted only where the
 * answer must hold on EVERY device: the ladder never throws, and any config it hands back really does carry
 * the name we asked for (a silent revert would make the connector's log a liar).
 *
 * Run: `gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.zanderp.opencfmoto.connection.factory.transport.CarbitGroupConfigDeviceTest`
 */
@RunWith(AndroidJUnit4::class)
class CarbitGroupConfigDeviceTest {

    @Test
    fun whichRungOfTheLadderThisPhoneAllows() {
        val creds = CarbitGroupConfig.creds(Random(1234))
        Log.i(TAG, "android=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}")
        Log.i(TAG, "asking for ssid='${creds.ssid}' pwdLen=${creds.passphrase.length} band=${CarbitGroupConfig.BAND}")
        val built = CarbitGroupConfig.build(creds) { line -> Log.i(TAG, line) }
        if (built == null) {
            Log.i(TAG, "VERDICT: neither custom-name rung is allowed — plain createGroup (system DIRECT-… name)")
            return
        }
        Log.i(TAG, "VERDICT: ${built.how} → networkName='${built.networkName}'")
        assertEquals("the reported name must be the config's real one", built.config.networkName, built.networkName)
        assertTrue("the group must carry the passphrase we generated", built.config.passphrase == creds.passphrase)
        assertTrue(
            "either Carbit's own name or its legal DIRECT- form — never something we did not ask for",
            built.networkName == creds.ssid || built.networkName == CarbitGroupConfig.legalNetworkName(creds.ssid),
        )
    }

    private companion object {
        const val TAG = "CarbitGroupProbe"
    }
}
