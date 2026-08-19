// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import com.google.gson.Gson
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.WifiTransport
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
 *  - else [QrData.supportsP2p] (bit3) counts for a genuine Wi-Fi Direct QR (`ssid` starts with `DIRECT-`,
 *    matched CASE-INSENSITIVELY exactly like classic `joinWifi`) **or** for a P2P-ONLY QR (bit3 set, no AP
 *    bit): the Voge-5G / ZT5G class, which classic joins by MAC — mapping those to SoftAP handed
 *    `SoftApTransport` an empty PSK, i.e. a guaranteed failure. A bike that sets bit3 *alongside* a normal
 *    AP ssid still resolves to SoftAP here.
 *  - else SoftAp (the common case: bit0/bit1).
 *
 * This is only the **family** decision (which of the four mechanisms the QR describes). The rider's Setup
 * preference and the learned per-bike winner refine SoftAP-vs-P2P in [resolveWifiTransport]; the persisted
 * pairing decision that combines both is [detectedAtPairing] — that, not this, is what the factory stores.
 */
fun ConnectionSpec.Companion.fromQr(qr: QrData): ConnectionSpec {
    val mode = when {
        qr.supportsPhoneHotspot && qr.pwd.isEmpty() ->
            if (isBleHotspotQr(qr)) TransportKind.PHONE_HOTSPOT else TransportKind.TETHER
        qr.supportsP2p && (qr.ssid.startsWith(DIRECT_SSID_PREFIX, ignoreCase = true) || !qr.supportsAp) ->
            TransportKind.P2P
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

/** Wi-Fi Direct SSID prefix, matched case-insensitively — classic `joinWifi`'s exact form. */
const val DIRECT_SSID_PREFIX: String = "DIRECT-"

/**
 * **The single Wi-Fi connector decision, shared by the classic path and the factory.** Extracted VERBATIM
 * from `CfmotoConnect.joinWifi` (which now calls this instead of holding its own copy), so a bike can never
 * be routed one way by the legacy UI and another way by the cockpit — the CRITICAL divergence this fixes:
 * the factory used to derive the mode from the QR alone, ignoring both the rider's Setup preference and the
 * learned per-bike winner, and sent the owner's `DIRECT-go-CFMOTO-*` 450NK (learned winner `"AP"`, because
 * P2P never forms on that phone/bike) to `P2pTransport` on every ride.
 *
 * Pure by construction (no Context, no I/O): the caller supplies [pref] (`AppSettings.transport`) and
 * [remembered] (`BikeMemory.winningTransport`, the `"AP"`/`"P2P"` string the live path records on success).
 *
 * @param pref the rider's Setup → Wi-Fi transport preference. `AP`/`P2P` are explicit orders;
 *   [WifiTransport.AUTO] means "decide from the QR".
 * @param remembered the transport that last produced a live link for this bike, or null. It refines
 *   **AUTO only** — an explicit `AP`/`P2P` is the rider's call — so callers pass null unless
 *   [pref] is [WifiTransport.AUTO] (classic does exactly this, and so does [detectedAtPairing]).
 * @return [TransportKind.P2P] or [TransportKind.SOFT_AP] — never a phone-hosts-the-network kind. This
 *   answers "SoftAP or Wi-Fi Direct?" for a QR already known to be a Wi-Fi bike.
 */
fun resolveWifiTransport(qr: QrData, pref: WifiTransport, remembered: String?): TransportKind {
    // AUTO: P2P when the QR is P2P-only (incl. non-DIRECT SSIDs — join by MAC), or DIRECT-*. Never force
    // P2P when the QR is SoftAP-only (action bit3 clear) — Setup→P2P on those bikes only burns 25s then
    // falls back (Benelli TRK / bj* SSIDs in the field).
    val useP2p = when (pref) {
        WifiTransport.P2P -> qr.supportsP2p || !qr.supportsAp
        WifiTransport.AP -> false
        WifiTransport.AUTO ->
            (qr.supportsP2p && !qr.supportsAp) ||
                (qr.ssid.startsWith(DIRECT_SSID_PREFIX, ignoreCase = true) &&
                    (qr.supportsP2p || !qr.supportsAp))
    }
    // Per-bike memory refines AUTO only: once a transport has produced a live link for THIS bike, use it
    // and skip the dead path. Some dashes advertise DIRECT-* + SoftAP, so AUTO would try P2P first and burn
    // the whole timeout on every connect even when P2P never forms a group on this phone.
    val eff = when {
        remembered == "AP" && qr.supportsAp -> false
        remembered == "P2P" && qr.supportsP2p -> true
        else -> useP2p
    }
    return if (eff) TransportKind.P2P else TransportKind.SOFT_AP
}

/**
 * **The connector a bike is PAIRED with**: decided ONCE (at scan/seed time), persisted per bike, and used
 * verbatim at ride time — no cascade, no fallback, because fallbacks are what make connecting slow (classic
 * burns ~25 s on P2P before dropping to SoftAP). A failed connect fails bounded and visibly, and the app
 * recommends the alternative ([suggestAlternativeConnector]) instead of silently trying it.
 *
 * Composition: [fromQr] picks the FAMILY (phone-hosts-the-network vs Wi-Fi), then — for the two Wi-Fi kinds
 * only — [resolveWifiTransport] applies the rider's Setup preference and the learned winner, exactly as
 * classic `joinWifi` does. The phone-hotspot kinds are returned untouched: neither preference nor winner
 * index has ever applied to them.
 *
 * The last refinement mirrors classic `joinWifi`'s MAC carve-out: a P2P-only QR with a SoftAP password but
 * NO mac has nothing to join Wi-Fi Direct *by*, so classic joins SoftAP rather than burn ~40s on "MAC ERROR"
 * (Voge-5G / ZT5G in the field). Reproduced here so the persisted decision matches classic end-to-end.
 *
 * @param remembered pass `BikeMemory.winningTransport(...)` only when [pref] is [WifiTransport.AUTO] (null
 *   otherwise) — see [resolveWifiTransport].
 */
fun ConnectionSpec.Companion.detectedAtPairing(
    qr: QrData,
    pref: WifiTransport,
    remembered: String?,
): ConnectionSpec {
    val base = fromQr(qr)
    return when (base.mode) {
        TransportKind.PHONE_HOTSPOT, TransportKind.TETHER -> base
        TransportKind.SOFT_AP, TransportKind.P2P -> {
            val resolved = resolveWifiTransport(qr, pref, remembered)
            val macless = resolved == TransportKind.P2P &&
                !qr.ssid.startsWith(DIRECT_SSID_PREFIX, ignoreCase = true) &&
                qr.mac.isNullOrBlank() && qr.pwd.isNotEmpty()
            base.copy(mode = if (macless) TransportKind.SOFT_AP else resolved)
        }
    }
}
