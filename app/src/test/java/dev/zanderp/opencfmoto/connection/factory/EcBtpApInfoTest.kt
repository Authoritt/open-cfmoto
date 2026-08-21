package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.connection.factory.ble.EcBtpApInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EcBtpApInfoTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    @Test fun `0x52 frame matches the Carbit golden bytes`() {
        val frame = EcBtpApInfo.frame(
            ssid = "Easyconn_AP-1234", pwd = "a1b2c3d4e5f6", ip = "192.168.49.1"
        )
        val golden =
            "24 52 54 7b 22 61 22 3a 22 45 61 73 79 63 6f 6e 6e 5f 41 50 2d 31 32 33 34 22 2c " +
            "22 62 22 3a 22 61 31 62 32 63 33 64 34 65 35 66 36 22 2c 22 63 22 3a 22 57 50 41 " +
            "32 22 2c 22 64 22 3a 22 22 2c 22 65 22 3a 22 31 39 32 2e 31 36 38 2e 34 39 2e 31 " +
            "22 7d 59 0a"
        assertEquals(golden.replace("\n", " ").trim(), hex(frame))
    }

    @Test fun `checksum is xor of start, cmd, len and payload`() {
        val f = EcBtpApInfo.frame("s", "p", "1.2.3.4")
        val payloadLen = f.size - 4                    // minus 0x24, cmd, len, xor, 0x0A → +1 back = -4
        var xor = 0
        for (i in 0 until f.size - 2) xor = xor xor (f[i].toInt() and 0xff)  // 0x24..last payload byte + len
        assertEquals((f[f.size - 2].toInt() and 0xff), xor)                  // second-to-last byte is the xor
        assertEquals(0x0a, f.last().toInt() and 0xff)
    }
}
