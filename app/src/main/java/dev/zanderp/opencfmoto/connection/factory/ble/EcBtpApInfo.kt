// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.ble

import com.google.gson.Gson
import dev.zanderp.opencfmoto.EcBtpProtocol
import java.io.ByteArrayOutputStream

/** Builds the Carbit EcBtp NOTIFY_AP_INFO (0x52) frame that hands Wi-Fi creds to a phone-hosts-hotspot
 *  dash over BLE. Wire keys are the literal letters a,b,c,d,e (verified against net.easyconn.carman.wws). */
object EcBtpApInfo {
    private val gson = Gson()
    private class Body(val a: String, val b: String, val c: String, val d: String, val e: String)

    fun frame(ssid: String, pwd: String, ip: String, auth: String = "WPA2", phoneMac: String = ""): ByteArray {
        val payload = gson.toJson(Body(ssid, pwd, auth, phoneMac, ip)).toByteArray(Charsets.UTF_8)
        val cmd = EcBtpProtocol.CMD_NOTIFY_AP_INFO
        val len = payload.size + 4
        var xor = 0x24 xor cmd xor len
        for (byte in payload) xor = xor xor (byte.toInt() and 0xff)
        return ByteArrayOutputStream().apply {
            write(0x24); write(cmd); write(len); write(payload); write(xor and 0xff); write(0x0a)
        }.toByteArray()
    }
}
