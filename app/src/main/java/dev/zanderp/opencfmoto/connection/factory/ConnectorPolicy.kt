// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

/**
 * Per-connector POLICY FACTS — pure decisions (no Context, no I/O) the app layer asks before it does
 * something on a bike's behalf. They live here, next to [TransportKind], because every one of them is an
 * exhaustive `when`: a future connector must DECIDE its answer, not inherit one from an `else`.
 */

/**
 * How auto-connect is allowed to answer "is the bike near?" for a given connector — the gate it applies
 * before attempting a connect on its own.
 *
 * The field bug this fixes: auto-connect asked ONE question for every bike — "is the BIKE's SSID in a
 * Wi-Fi scan?" ([dev.zanderp.opencfmoto.BikeWifi.isSsidInRange]). For the two phone-hosts-the-network
 * connectors that question has NO answer: the PHONE creates the network, so there is no bike SSID to scan
 * for, the gate returns `false` forever and those bikes NEVER auto-connect (real log, Rieju owner:
 * `[auto-fg] 'Phone hotspot (16:6b:50)' not in range — will retry on resume`).
 */
enum class AutoConnectGate {
    /**
     * The DASH hosts the network, so its SSID is a real presence signal: scan for it and attempt only when
     * it is there (or unknown — `isSsidInRange` is deliberately biased to "try anyway"). Correct and
     * valuable here, and unchanged: it is what keeps a parked phone from joining nothing all day.
     */
    BIKE_SSID_IN_RANGE,

    /**
     * The PHONE hosts the network and the whole handshake is automatic (creates the group, hands the dash
     * the password over Bluetooth). Nothing can be scanned for, so the honest gate is a BUDGET: **one
     * automatic attempt per app session**, then the rider decides with Connect.
     *
     * Why not "just attempt on every resume": the cockpit's auto-connect fires on every ON_RESUME of the
     * dashboard destination, so a rider tapping through the app would set up a Wi-Fi Direct group and eat a
     * ~15 s wait plus a terminal error EVERY time they came back. A wrong auto-attempt costs a failed
     * connect and a scary message; one per session buys the feature its point (the bike connects by itself
     * when you open the app next to it) at the smallest possible cost.
     */
    ONCE_PER_SESSION,

    /**
     * The RIDER has to switch the phone's hotspot on by hand and answer the assist dialog
     * ([dev.zanderp.opencfmoto.PhoneHotspotAssist]) before this connector can do anything. Automatic
     * attempts would pop a modal nobody asked for and could never succeed on their own, so there are none:
     * this connector is Connect-only. (Its behaviour is therefore unchanged — it never auto-connected
     * either, it just used to be blocked by a gate that lied about why.)
     */
    RIDER_ONLY,
}

/** The gate auto-connect must apply for [mode] — see [AutoConnectGate]. */
fun autoConnectGateFor(mode: TransportKind): AutoConnectGate = when (mode) {
    TransportKind.SOFT_AP, TransportKind.P2P -> AutoConnectGate.BIKE_SSID_IN_RANGE
    TransportKind.PHONE_HOTSPOT -> AutoConnectGate.ONCE_PER_SESSION
    TransportKind.TETHER -> AutoConnectGate.RIDER_ONLY
}

/**
 * Does this connector drive **Wi-Fi Direct** (`WifiP2pManager`)? [TransportKind.PHONE_HOTSPOT] creates a
 * group (the phone becomes its owner) and [TransportKind.P2P] joins the dash's — both go through the same
 * platform API, and on Android 13+ both are refused without the `NEARBY_WIFI_DEVICES` runtime grant
 * (declared `neverForLocation` in the manifest, so a location grant does NOT substitute for it).
 *
 * [TransportKind.SOFT_AP] (a `WifiNetworkSpecifier` join) and [TransportKind.TETHER] (the rider's own
 * hotspot) never touch it — which is why the grant is asked for by CONNECTOR, at pairing, instead of being
 * demanded from every rider at first run.
 */
fun usesWifiDirect(mode: TransportKind): Boolean = when (mode) {
    TransportKind.PHONE_HOTSPOT, TransportKind.P2P -> true
    TransportKind.SOFT_AP, TransportKind.TETHER -> false
}
