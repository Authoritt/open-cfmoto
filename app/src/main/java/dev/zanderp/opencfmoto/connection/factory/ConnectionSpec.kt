// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import com.google.gson.Gson
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.settings.MapProvider

/**
 * Stable id for a [dev.zanderp.opencfmoto.BikeProfile] ("generic" = no override — pick by CLIENT_INFO/QR
 * as usual). A plain `String` (not the `BikeProfile` interface itself) on purpose: [ConnectionSpec] is
 * Gson-serialized and persisted per-bike (design doc `2026-08-18-bike-connection-factory-design.md` §5,
 * wired in Task 8's `BikeMemory.specFor`/`saveSpec`), so it must stay JSON-friendly and decoupled from the
 * heavyweight `BikeProfile`/`BikeProfiles` object graph.
 */
typealias BikeProfileId = String

/**
 * Per-bike connection config — how to reach a specific, already-seen bike again without re-running full
 * detection (design doc §5). One [ConnectionSpec] per [bikeId], persisted as JSON (Task 8).
 */
data class ConnectionSpec(
    /** Stable key: the QR `mac` (`bm=`) when present, else the `ssid` (see [fromQr]). */
    val bikeId: String,
    /** How the phone reaches this bike (SoftAP/P2P/PhoneHotspot). */
    val mode: TransportKind,
    /** Brand-quirk profile id (timeouts, P2P MAC±1, BLE service override). "generic" = no override. */
    val profile: BikeProfileId = "generic",
    val ssid: String? = null,
    val pwd: String? = null,
    /** Phone-hotspot BLE pairing target — the QR `mac`/`bm=`. */
    val bleMac: String? = null,
    /** GATT service UUID override when a dash remaps the default (`0000B360-…`); null = default. */
    val bleServiceOverride: String? = null,
    /** Last known gateway/host, for a faster reconnect hint. */
    val lastEndpointHint: String? = null,
    /** Per-bike default map provider (config-ownership, design doc §2). */
    val defaultMapProvider: MapProvider? = null,
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        fun fromJson(json: String): ConnectionSpec = gson.fromJson(json, ConnectionSpec::class.java)
    }
}

/**
 * The stable per-bike key: the QR `mac` (`bm=`) when present, else the `ssid` (design doc §5). The single
 * definition [fromQr] and [dev.zanderp.opencfmoto.BikeMemory.specFor]/`saveSpec` (Task 8) share, so a saved
 * spec is always looked up under the exact id a fresh scan would derive.
 */
fun ConnectionSpec.Companion.bikeIdFor(qr: QrData): String = qr.mac ?: qr.ssid

/**
 * Derive a [ConnectionSpec] from a scanned/parsed [QrData]. Mode selection reuses [QrData]'s own bitmask
 * getters rather than re-deriving the `action` bitmask here (spec design doc §7/§8):
 *  - [QrData.supportsPhoneHotspot] (bit7, or blank-ssid + mac) wins outright — no ssid/pwd to join.
 *  - else [QrData.supportsP2p] (bit3) only counts for a genuine Wi-Fi Direct QR (`ssid` starts with
 *    "DIRECT") — some bikes set bit3 alongside a normal AP ssid, which must still resolve to SoftAP.
 *  - else SoftAp (the common case: bit0/bit1).
 */
fun ConnectionSpec.Companion.fromQr(qr: QrData): ConnectionSpec {
    val mode = when {
        qr.supportsPhoneHotspot -> TransportKind.PHONE_HOTSPOT
        qr.supportsP2p && qr.ssid.startsWith("DIRECT") -> TransportKind.P2P
        else -> TransportKind.SOFT_AP
    }
    return ConnectionSpec(
        bikeId = bikeIdFor(qr),
        mode = mode,
        ssid = qr.ssid,
        pwd = qr.pwd,
        bleMac = qr.mac,
    )
}
