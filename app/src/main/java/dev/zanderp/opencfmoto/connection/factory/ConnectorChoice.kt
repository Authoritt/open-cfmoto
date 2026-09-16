// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

/**
 * The rider's per-bike choice of connection MECHANISM, set at Scan (where it is proven) or in the Garage
 * (durable home) and remembered by `BikeMemory.connectorChoice`/`setConnectorChoice`. [AUTO] is the default
 * and the only value existing bikes ever hold, so nothing changes unless the rider deliberately pins a
 * mechanism.
 *
 * **Every value is named after the MECHANISM, never after a brand.** A brand name on a mechanism is a trap:
 * "Rieju" as the label for *phone creates the network + BLE credential push* nearly routed working Zontes
 * dashes onto the wrong connector, because the rider reads the badge on the bike, not the protocol. Zontes,
 * Rieju, CFMoto and the rest all appear in the "?" help sheet as *tested on* evidence — that is where brands
 * belong.
 *
 * All four connectors ([SOFT_AP]/[P2P]/[BLE]/[HOTSPOT]) map onto a [TransportKind] that `setConnectorChoice`
 * writes into the bike's stored [ConnectionSpec.mode] — that is what `BikeConnectionFactory.selectTransport`
 * reads, so an explicit choice forces the transport without re-running detection. [AUTO] clears any prior
 * override (resets `spec.mode` back to the QR-derived guess) so the connect path auto-detects exactly as a
 * fresh scan would.
 *
 * **Persisted by NAME** (`connector_<bikeId>` in `BikeMemory`), so renaming a value is a data migration, not
 * a refactor — see [fromStoredName].
 */
enum class ConnectorChoice {
    /** Let the app detect the mechanism from the QR (default; existing bikes behave as today). */
    AUTO,

    /** Force *the dash creates the network and the phone joins it* → factory `SoftApTransport`. */
    SOFT_AP,

    /** Force *Wi-Fi Direct with the dash* → factory `P2pTransport`. */
    P2P,

    /**
     * Force *the phone creates the network and hands the dash the password over Bluetooth* → factory
     * `PhoneHotspotTransport` (keeps its manual fallback). Overrides even a QR whose `modelid` isn't in
     * `BLE_HOTSPOT_MODEL_IDS`. Stored as `RIEJU_BLE` by builds ≤ 2.0.13-pre (see [fromStoredName]).
     */
    BLE,

    /**
     * Force *the rider turns on the phone's hotspot and the dash joins it* → factory `TetherTransport`.
     * Overrides even a QR the app would have detected as SoftAP/P2P. Stored as `TETHER` by builds
     * ≤ 2.0.13-pre (see [fromStoredName]).
     */
    HOTSPOT;

    companion object {
        /**
         * Names written by builds that labelled the mechanisms by brand (`RIEJU_BLE`) or by their internal
         * [TransportKind] (`TETHER`). Kept forever, keyed by the EXACT string those builds persisted: a pin
         * the rider made is a decision we asked them for, and dropping it here would silently reset their
         * bike to [AUTO] on upgrade — the one failure mode a rename like this can cause, and an invisible one
         * (the app would just start guessing again on a bike the rider had already fixed by hand).
         */
        private val LEGACY_NAMES: Map<String, ConnectorChoice> = mapOf(
            "RIEJU_BLE" to BLE,
            "TETHER" to HOTSPOT,
        )

        /**
         * The choice a stored `connector_<bikeId>` string means TODAY — today's names first, then the legacy
         * ones above. Null for blank/unknown/corrupt values, so callers can apply their own default ([AUTO]).
         */
        fun fromStoredName(stored: String?): ConnectorChoice? {
            if (stored.isNullOrBlank()) return null
            return runCatching { valueOf(stored) }.getOrNull() ?: LEGACY_NAMES[stored]
        }

        /**
         * The connector that a resolved [TransportKind] corresponds to — the inverse of the mapping
         * `BikeMemory.setConnectorChoice` applies. Used to turn a connection that ACTUALLY worked into a
         * validated pin (Scan's Conectar), instead of leaving the bike on a guess.
         */
        fun forTransport(mode: TransportKind): ConnectorChoice = when (mode) {
            TransportKind.SOFT_AP -> SOFT_AP
            TransportKind.P2P -> P2P
            TransportKind.PHONE_HOTSPOT -> BLE
            TransportKind.TETHER -> HOTSPOT
        }

        /**
         * The transport a pinned [choice] forces, or null for [AUTO] (which forces nothing — detection
         * decides). The SINGLE definition of this direction: `BikeMemory.setConnectorChoice` writes it into
         * the bike's spec and `BikeMemory.effectiveMode` reads it back, and the two used to be one `when`
         * with no counterpart, which is how a mapping quietly grows a second, different copy.
         *
         * Mind the crossing, spelled out in [forTransport]: rider-facing [BLE] is [TransportKind.PHONE_HOTSPOT]
         * and rider-facing [HOTSPOT] is [TransportKind.TETHER].
         */
        fun transportFor(choice: ConnectorChoice): TransportKind? = when (choice) {
            SOFT_AP -> TransportKind.SOFT_AP
            P2P -> TransportKind.P2P
            BLE -> TransportKind.PHONE_HOTSPOT
            HOTSPOT -> TransportKind.TETHER
            AUTO -> null
        }
    }
}
