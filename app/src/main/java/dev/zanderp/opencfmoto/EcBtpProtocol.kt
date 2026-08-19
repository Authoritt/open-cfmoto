// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EasyConn Bluetooth serial framing used by Carbit Ride for dash clock (and Wi-Fi creds / CAN).
 *
 * `0x24 | CMD | LEN | payload… | XOR | 0x0A`
 * LEN = payload.size + 4 (counts start, cmd, len, payload, xor — not the terminator).
 * XOR = 0x24 ^ CMD ^ LEN ^ every payload byte.
 *
 * Clock commands (dash asks, phone replies — never unsolicited):
 *  - [CMD_SYNC_TIME]  epoch millis + raw TZ offset (no DST), 8-byte little-endian
 *  - [CMD_QUERY_TIME] `dd.MM.yyyy HH:mm:ss:zzz` (zone *name*, not milliseconds)
 */
internal object EcBtpProtocol {
    const val START: Byte = 0x24
    const val END: Byte = 0x0A
    const val CMD_SYNC_TIME: Byte = 0x01
    const val CMD_QUERY_TIME: Byte = 0x55
    const val QUERY_TIME_LAYOUT = "dd.MM.yyyy HH:mm:ss:zzz"

    // AP-info / Wi-Fi credential handshake (phone hosts a hotspot, dash joins it over BLE).
    // Untyped (Int, not Byte) — EcBtpApInfo builds its frame with raw int arithmetic/xor, unlike
    // the Byte-typed clock commands above which flow through [build]'s `command: Byte` parameter.
    const val CMD_EC_BTP_CLIENT_INFO = 0x30
    const val CMD_REQUEST_BUILD_NET = 0x50
    const val CMD_NOTIFY_BUILD_NET_FINISH = 0x51
    const val CMD_NOTIFY_AP_INFO = 0x52
    const val CMD_NOTIFY_CAR_NET_INFO = 0x53

    private const val FRAME_OVERHEAD = 5
    private const val LENGTH_OVERHEAD = 4
    private const val MAX_STRING_PAYLOAD = 120

    data class Frame(val command: Byte, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return command == other.command && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * command.toInt() + payload.contentHashCode()
    }

    fun build(command: Byte, payload: ByteArray): ByteArray {
        val frame = ByteArray(payload.size + FRAME_OVERHEAD)
        frame[0] = START
        frame[1] = command
        frame[2] = (payload.size + LENGTH_OVERHEAD).toByte()
        payload.copyInto(frame, destinationOffset = 3)
        frame[frame.size - 2] = checksum(frame, payload.size)
        frame[frame.size - 1] = END
        return frame
    }

    fun parse(buffer: ByteArray): Frame? {
        if (buffer.size < FRAME_OVERHEAD) return null
        if (buffer[0] != START) return null
        if (buffer[buffer.size - 1] != END) return null
        val declared = buffer[2].toInt() and 0xFF
        val payloadSize = declared - LENGTH_OVERHEAD
        if (payloadSize < 0) return null
        if (payloadSize + FRAME_OVERHEAD != buffer.size) return null
        val payload = buffer.copyOfRange(3, 3 + payloadSize)
        if (buffer[buffer.size - 2] != checksum(buffer, payloadSize)) return null
        return Frame(command = buffer[1], payload = payload)
    }

    fun syncTimeReply(nowMillis: Long, rawOffsetMillis: Int): ByteArray {
        val shifted = nowMillis + rawOffsetMillis
        val payload = ByteArray(8)
        for (index in 0 until 8) {
            payload[index] = ((shifted shr (index * 8)) and 0xFF).toByte()
        }
        return build(CMD_SYNC_TIME, payload)
    }

    fun queryTimeReply(now: Date, zone: TimeZone, locale: Locale = Locale.getDefault()): ByteArray {
        val formatter = SimpleDateFormat(QUERY_TIME_LAYOUT, locale).apply { timeZone = zone }
        var payload = formatter.format(now).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_STRING_PAYLOAD) payload = payload.copyOf(MAX_STRING_PAYLOAD)
        return build(CMD_QUERY_TIME, payload)
    }

    private fun checksum(frame: ByteArray, payloadSize: Int): Byte {
        var acc = 0
        for (index in 0 until (3 + payloadSize)) {
            acc = acc xor (frame[index].toInt() and 0xFF)
        }
        return acc.toByte()
    }
}
