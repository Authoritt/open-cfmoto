// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

/**
 * The rider's per-bike choice of connection MECHANISM, set in the Garage (durable home) or at Scan and
 * remembered by `BikeMemory.connectorChoice`/`setConnectorChoice`. [AUTO] is the default and the only
 * value existing bikes ever hold, so nothing changes unless the rider deliberately pins a mechanism.
 *
 * The three FACTORY connectors ([SOFT_AP]/[P2P]/[RIEJU_BLE]) map onto a [TransportKind] that
 * `setConnectorChoice` writes into the bike's stored [ConnectionSpec.mode] — that is what
 * `BikeConnectionFactory.selectTransport` reads, so an explicit choice forces the transport without
 * re-running detection. [TETHER] routes the CLASSIC Android-tether path (`joinPhoneHotspot`) instead and
 * therefore does NOT touch `spec.mode`. [AUTO] clears any prior override (resets `spec.mode` back to the
 * QR-derived guess) so the connect path auto-detects exactly as a fresh scan would.
 */
enum class ConnectorChoice {
    /** Let the app detect the mechanism from the QR (default; existing bikes behave as today). */
    AUTO,

    /** Force the dash's own Wi-Fi network (SoftAP) → factory `SoftApTransport`. */
    SOFT_AP,

    /** Force the dash's Wi-Fi Direct (P2P) → factory `P2pTransport`. */
    P2P,

    /** Force the Rieju Wi-Fi-Direct + BLE mechanism → factory `PhoneHotspotTransport` (keeps its manual
     *  fallback). Overrides even a QR whose `modelid` isn't in `BLE_HOTSPOT_MODEL_IDS`. */
    RIEJU_BLE,

    /** Force the classic manual phone-hotspot tether (`CfmotoConnect.joinPhoneHotspot`); NOT the factory. */
    TETHER,
}
