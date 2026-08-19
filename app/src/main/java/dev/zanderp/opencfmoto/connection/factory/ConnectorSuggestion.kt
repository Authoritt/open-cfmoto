// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.QrData

/**
 * **Advice, never a cascade.** The connector is decided once at pairing and used verbatim at ride time; a
 * connect that fails does so bounded and VISIBLY (finite retry caps for every kind — see
 * [BikeConnectionFactory.retryCapsFor]) instead of silently trying another mechanism, because that silent
 * try is exactly what makes connecting slow (classic burns ~25 s on P2P before dropping to SoftAP).
 *
 * What this gives the app is the *other* mechanism worth RECOMMENDING to the rider after such a failure —
 * the one the connector picker (`ConnectorChoice`) would pin. Returning a [ConnectorChoice] (not a
 * [TransportKind]) on purpose: the recommendation's only destination is that picker, and `RIEJU_BLE` is a
 * choice, not a kind.
 *
 * Nothing in this package acts on the result: it rides on the terminal [ConnState.Error.alternative] and is
 * there for a future UI to offer. Pure (QR + the kind that just failed), so it is unit-tested without a bike.
 *
 * The pairs, and why each is the only sensible partner:
 *  - [TransportKind.P2P] failed → `SOFT_AP`, but only if the QR actually advertises an AP (bit0/bit1);
 *    a P2P-only QR has no SoftAP credentials to offer.
 *  - [TransportKind.SOFT_AP] failed → `P2P`, but only if the QR advertises Wi-Fi Direct (bit3).
 *  - [TransportKind.PHONE_HOTSPOT] (the Rieju BLE handoff) failed → `TETHER`: the same "phone hosts the
 *    network" outcome reached by hand (the rider types the dash creds into the Android hotspot), which is
 *    the proven escape hatch when the BLE credential push doesn't land.
 *  - [TransportKind.TETHER] failed → `RIEJU_BLE`, but only when the QR carries a BLE mac (`bm=`) to pair
 *    with — without one there is nothing for the BLE connector to talk to.
 */
fun suggestAlternativeConnector(qr: QrData, failed: TransportKind): ConnectorChoice? = when (failed) {
    TransportKind.P2P -> ConnectorChoice.SOFT_AP.takeIf { qr.supportsAp }
    TransportKind.SOFT_AP -> ConnectorChoice.P2P.takeIf { qr.supportsP2p }
    TransportKind.PHONE_HOTSPOT -> ConnectorChoice.TETHER.takeIf { qr.supportsPhoneHotspot }
    TransportKind.TETHER -> ConnectorChoice.RIEJU_BLE.takeIf { !qr.mac.isNullOrBlank() }
}
