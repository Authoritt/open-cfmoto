package dev.zanderp.opencfmoto.connection.factory.ble

import dev.zanderp.opencfmoto.EcBtpProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `a checksum-failing frame is reported in dropped, not emitted, and does not block a good frame`() {
        val bad = EcBtpProtocol.build(EcBtpProtocol.CMD_NOTIFY_CAR_NET_INFO.toByte(), ByteArray(0))
        bad[bad.size - 2] = (bad[bad.size - 2] + 1).toByte() // corrupt the xor byte → parse() rejects it
        val r = BleApInfoPush.extractFrames(empty, bad + frame51)
        assertEquals("only the good frame parses", 1, r.frames.size)
        assertEquals(0x51.toByte(), r.frames[0].command)
        assertEquals("the corrupted frame is surfaced for the owner's log", 1, r.dropped.size)
        assertArrayEquals(bad, r.dropped[0])
        assertEquals(0, r.remainder.size)
    }

    // ---- The dash's own 0x50 reply (real bytes, Rieju Aventure 500, 2026-08-19 21:39:06) ------------------
    // The frame the connector used to decode and throw away while it waited for a 0x51/0x53 that never came.
    // It arrived split over three notifications, exactly as reproduced here.

    private val realChunk1 = bytes("24 50 14 7b 0a 09 22 73 74 61")
    private val realChunk2 = bytes("74 75 73 22 3a 09 32 0a 7d 7a")
    private val realChunk3 = bytes("0a")

    @Test fun `the real 0x50 reply reassembles from the three notifications the bike sent`() {
        val r1 = BleApInfoPush.extractFrames(empty, realChunk1)
        assertEquals("nothing complete yet", 0, r1.frames.size)
        val r2 = BleApInfoPush.extractFrames(r1.remainder, realChunk2)
        assertEquals("still one byte short of the frame", 0, r2.frames.size)
        val r3 = BleApInfoPush.extractFrames(r2.remainder, realChunk3)
        assertEquals(1, r3.frames.size)
        assertEquals("the dash answers REQUEST_BUILD_NET with the same cmd", 0x50.toByte(), r3.frames[0].command)
        assertEquals("{\n\t\"status\":\t2\n}", r3.frames[0].payload.toString(Charsets.UTF_8))
        assertEquals(0, r3.remainder.size)
        assertEquals(0, r3.dropped.size)
    }

    @Test fun `the status the bike reported is read out of that payload`() {
        val payload = BleApInfoPush.extractFrames(
            empty,
            realChunk1 + realChunk2 + realChunk3,
        ).frames.single().payload
        assertEquals("2", BleApInfoPush.netBuildStatus(payload))
    }

    @Test fun `a status without the dash's tabs and newlines reads the same`() {
        assertEquals("0", BleApInfoPush.netBuildStatus("""{"status":0}""".toByteArray()))
        assertEquals("-1", BleApInfoPush.netBuildStatus("""{ "status" : -1 , "x": 9 }""".toByteArray()))
    }

    @Test fun `a payload with no status is null, not an invented value`() {
        assertNull(BleApInfoPush.netBuildStatus("""{"state":2}""".toByteArray()))
        assertNull(BleApInfoPush.netBuildStatus(ByteArray(0)))
    }

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
