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

        /**
         * Read back a persisted spec. Gson stores enums BY NAME, so adding a [TransportKind] never breaks an
         * older saved spec — but the reverse (a name THIS build doesn't know: a spec written by a newer
         * build, or a corrupted pref) makes Gson leave the field null, which Kotlin's non-null [mode] would
         * only surface as an NPE deep inside `BikeConnectionFactory.selectTransport`. Normalize it here to
         * the SoftAP default (the same fallback [fromQr] uses) so an unknown value degrades to "re-detect
         * like a fresh scan" instead of crashing the connect path.
         */
        fun fromJson(json: String): ConnectionSpec {
            val parsed = gson.fromJson(json, ConnectionSpec::class.java)
                ?: throw IllegalArgumentException("not a ConnectionSpec: $json")
            @Suppress("USELESS_ELVIS") // Gson bypasses the constructor: `mode` CAN be null at runtime.
            val mode: TransportKind = parsed.mode ?: TransportKind.SOFT_AP
            return parsed.copy(mode = mode)
        }
    }
}

/**
 * The phone-hotspot model(s) whose dash needs the factory's Wi-Fi-Direct + BLE `PhoneHotspotTransport`: the
 * Rieju Aventura 500 (Carbit `action=128`, protocol RE'd in spec Appendix A; QR `modelid=43402`). Every OTHER
 * phone-hotspot bike — Zontes, and opaque `CARBIT` tokens that carry NO `modelid` — is a [TransportKind.TETHER]
 * bike (the rider's Android hotspot; no BLE). Extend this set ONLY after a model is confirmed ON THE BIKE to
 * need the Wi-Fi-Direct + BLE path (adding a working tether bike here would REGRESS it).
 */
val BLE_HOTSPOT_MODEL_IDS: Set<String> = setOf("43402")

/**
 * A phone-hosts-hotspot QR for a model that needs the factory's `PhoneHotspotTransport` (P2P group-owner +
 * BLE B360 `0x52`): phone-hotspot (`supportsPhoneHotspot && pwd.isEmpty()` — the classic gate), carrying a
 * BLE MAC (`bm=`), AND a known-Rieju `modelid` ([BLE_HOTSPOT_MODEL_IDS]). Narrowed to the Rieju on PURPOSE:
 * the shape (action=128 + `bm=` + empty pwd) is shared by other Carbit-family bikes (e.g. Zontes) that work
 * TODAY on the tether path, so routing by shape alone would divert them onto the Rieju-only BLE/Wi-Fi-Direct
 * connector (regression). Any non-matching phone-hotspot QR is a [TransportKind.TETHER] bike.
 *
 * **The single definition**: [fromQr] (which mode a scanned bike gets) and
 * [dev.zanderp.opencfmoto.connection.CfmotoConnect.isBleHotspot] (which connector `joinWifi` routes to) both
 * call THIS — they used to be two copies of the same rule, one of which would eventually drift.
 */
fun isBleHotspotQr(qr: QrData): Boolean =
    qr.supportsPhoneHotspot && qr.pwd.isEmpty() && !qr.mac.isNullOrEmpty() &&
        qr.modelId in BLE_HOTSPOT_MODEL_IDS

/**
 * The stable per-bike key: the QR `mac` (`bm=`) when present, else the `ssid` (design doc §5). The single
 * definition [fromQr] and [dev.zanderp.opencfmoto.BikeMemory.specFor]/`saveSpec` (Task 8) share, so a saved
 * spec is always looked up under the exact id a fresh scan would derive.
 */
fun ConnectionSpec.Companion.bikeIdFor(qr: QrData): String = qr.mac ?: qr.ssid

/**
 * Derive a [ConnectionSpec] from a scanned/parsed [QrData]. Mode selection reuses [QrData]'s own bitmask
 * getters rather than re-deriving the `action` bitmask here (spec design doc §7/§8):
 *  - [QrData.supportsPhoneHotspot] (bit7, or blank-ssid + mac) **with no SoftAP password** — matches the
 *    classic router's `supportsPhoneHotspot && pwd.isEmpty()` gate (CfmotoConnect): a bit7 QR that also
 *    carries a password is a SoftAP bike, not phone-hosts-hotspot, so it falls through to the cases below.
 *    Which of the TWO phone-hosts-the-network mechanisms it gets is [isBleHotspotQr]: a known-Rieju
 *    `modelid` means the BLE + Wi-Fi-Direct [TransportKind.PHONE_HOTSPOT] connector; every other
 *    phone-hotspot QR (Zontes, opaque `CARBIT`) means the rider's-Android-hotspot [TransportKind.TETHER].
 *  - else [QrData.supportsP2p] (bit3) only counts for a genuine Wi-Fi Direct QR (`ssid` starts with
 *    "DIRECT") — some bikes set bit3 alongside a normal AP ssid, which must still resolve to SoftAP.
 *  - else SoftAp (the common case: bit0/bit1).
 */
fun ConnectionSpec.Companion.fromQr(qr: QrData): ConnectionSpec {
    val mode = when {
        qr.supportsPhoneHotspot && qr.pwd.isEmpty() ->
            if (isBleHotspotQr(qr)) TransportKind.PHONE_HOTSPOT else TransportKind.TETHER
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
