package dev.zanderp.opencfmoto.connection.factory.ble

import dev.zanderp.opencfmoto.EcBtpProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 9 follow-up (fix round 1): locks down [BleApInfoPush.extractFrames] — the BLE-notification reassembler,
 * the only device-INDEPENDENT parse path in the Rieju connector (there is no bike here to catch a receive-path
 * bug). Frames are the standard EcBtp shell (`24 | cmd | len | payload | xor | 0a`) built by
 * [EcBtpProtocol.build]. Pure: touches only the companion function, never constructs the Android-bound class.
 */
class BleApInfoPushTest {

    private val frame51 = EcBtpProtocol.build(EcBtpProtocol.CMD_NOTIFY_BUILD_NET_FINISH.toByte(), ByteArray(0))
    private val frame53 = EcBtpProtocol.build(EcBtpProtocol.CMD_NOTIFY_CAR_NET_INFO.toByte(), ByteArray(0))
    private val empty = ByteArray(0)

    @Test fun `a whole 0x51 in one notification yields one frame and drains the buffer`() {
        val r = BleApInfoPush.extractFrames(empty, frame51)
        assertEquals(1, r.frames.size)
        assertEquals(0x51.toByte(), r.frames[0].command)
        assertEquals(0, r.remainder.size)
    }

    @Test fun `a 0x53 split across two notifications reassembles`() {
        val part1 = frame53.copyOfRange(0, 2)             // 24 53
        val part2 = frame53.copyOfRange(2, frame53.size)  // 04 xor 0a
        val r1 = BleApInfoPush.extractFrames(empty, part1)
        assertEquals("partial frame yields nothing yet", 0, r1.frames.size)
        assertArrayEquals("the partial is buffered verbatim", part1, r1.remainder)
        val r2 = BleApInfoPush.extractFrames(r1.remainder, part2)
        assertEquals(1, r2.frames.size)
        assertEquals(0x53.toByte(), r2.frames[0].command)
        assertEquals(0, r2.remainder.size)
    }

    @Test fun `two frames concatenated in one notification yield both in order`() {
        val r = BleApInfoPush.extractFrames(empty, frame51 + frame53)
        assertEquals(2, r.frames.size)
        assertEquals(0x51.toByte(), r.frames[0].command)
        assertEquals(0x53.toByte(), r.frames[1].command)
        assertEquals(0, r.remainder.size)
    }

    @Test fun `garbage before the START byte is skipped`() {
        val garbage = byteArrayOf(0xff.toByte(), 0x00, 0xaa.toByte())
        val r = BleApInfoPush.extractFrames(empty, garbage + frame51)
        assertEquals(1, r.frames.size)
        assertEquals(0x51.toByte(), r.frames[0].command)
        assertEquals(0, r.remainder.size)
    }

    @Test fun `a truncated frame is left buffered for the next chunk`() {
        val partial = frame53.copyOfRange(0, 3) // 24 53 04 — header only; payload+xor+end missing
        val r = BleApInfoPush.extractFrames(empty, partial)
        assertEquals(0, r.frames.size)
        assertArrayEquals(partial, r.remainder)
    }

    @Test fun `a frame carrying a payload is extracted by its declared length`() {
        val f = EcBtpProtocol.build(EcBtpProtocol.CMD_NOTIFY_CAR_NET_INFO.toByte(), byteArrayOf(1, 2, 3, 4, 5))
        val r = BleApInfoPush.extractFrames(empty, f)
        assertEquals(1, r.frames.size)
        assertEquals(0x53.toByte(), r.frames[0].command)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), r.frames[0].payload)
        assertEquals(0, r.remainder.size)
    }
}
