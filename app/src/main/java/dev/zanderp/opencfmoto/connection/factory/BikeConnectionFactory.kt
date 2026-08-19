// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.content.Context
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.connection.factory.link.EasyConnBikeLink
import dev.zanderp.opencfmoto.connection.factory.link.YunmoBikeLink
import dev.zanderp.opencfmoto.connection.factory.transport.P2pTransport
import dev.zanderp.opencfmoto.connection.factory.transport.PhoneHotspotTransport
import dev.zanderp.opencfmoto.connection.factory.transport.SoftApTransport
import dev.zanderp.opencfmoto.connection.factory.transport.TetherTransport

/**
 * Assembles a two-layer [BikeConnection] from a scanned QR (design doc 2026-08-18 §3): pick the Layer-1
 * [BikeTransport] by the bike's [TransportKind], stack the Layer-2 [BikeLink]s (EasyConn first, Yunmo as
 * fallback), and hand both to a [DefaultBikeConnection] that owns the lifecycle.
 */
object BikeConnectionFactory {

    /** Layer-1 selection: the transport that gets the phone onto this bike's network. */
    fun selectTransport(spec: ConnectionSpec): BikeTransport = when (spec.mode) {
        TransportKind.SOFT_AP -> SoftApTransport()
        TransportKind.P2P -> P2pTransport()
        TransportKind.PHONE_HOTSPOT -> PhoneHotspotTransport()
        TransportKind.TETHER -> TetherTransport()
    }

    /**
     * Retry-parity caps by transport (flip-work-design.md §2): SoftAP/P2P get effectively-infinite caps so the
     * daily-driver factory path matches classic's UNBOUNDED retry — an ended driver has no receiver for a later
     * Wi-Fi re-acquire. The two phone-hosts-the-network connectors keep the DEFAULT (finite) caps: both are
     * interactive, foreground, one-shot flows — PHONE_HOTSPOT is a one-shot BLE handoff with a manual fallback,
     * TETHER is a rider-driven hotspot setup with an assist dialog — not daily-ride reconnect paths. Returns
     * `(maxAttempts, flapMaxFailures)`. Written as an exhaustive `when` on purpose: a future transport kind
     * must DECIDE its retry policy here, not inherit one from an `else`. Pure/internal for testing.
     */
    internal fun retryCapsFor(mode: TransportKind): Pair<Int, Int> = when (mode) {
        TransportKind.SOFT_AP, TransportKind.P2P -> Int.MAX_VALUE to Int.MAX_VALUE
        TransportKind.PHONE_HOTSPOT, TransportKind.TETHER -> MAX_ATTEMPTS to FLAP_MAX_FAILURES
    }

    /**
     * Heal a spec persisted BEFORE [TransportKind.TETHER] existed. Those builds mapped EVERY phone-hotspot QR
     * to [TransportKind.PHONE_HOTSPOT] (then the only phone-hosts-the-network kind), so a Zontes paired on an
     * older build carries a stored `mode = PHONE_HOTSPOT` that would now select the Rieju BLE connector — the
     * exact regression `isBleHotspotQr` exists to prevent. Under AUTO the mode is a DETECTION result, so it
     * must follow today's detection: a stored PHONE_HOTSPOT on a QR that isn't a BLE-hotspot QR becomes TETHER.
     *
     * A rider PIN is law and is never touched ([choice] != AUTO returns [stored] as-is): `RIEJU_BLE`
     * deliberately forces PHONE_HOTSPOT even for a QR whose `modelid` isn't known
     * (`BikeMemory.setConnectorChoice`). Pure/internal so the rule is unit-testable without Android.
     */
    internal fun reconcileStoredMode(
        stored: ConnectionSpec,
        qr: QrData,
        choice: ConnectorChoice,
    ): ConnectionSpec =
        if (choice == ConnectorChoice.AUTO &&
            stored.mode == TransportKind.PHONE_HOTSPOT &&
            !isBleHotspotQr(qr)
        ) {
            stored.copy(mode = TransportKind.TETHER)
        } else {
            stored
        }

    /**
     * Build a ready-to-drive connection for [qr]. Garage fast-path (Task 8): a remembered [ConnectionSpec]
     * for this bike skips re-detection (auto-vs-P2P racing, mode probing); a never-seen bike falls back to
     * deriving one fresh from the QR, same as before. Either way, [DefaultBikeConnection] saves the spec
     * back to [memory] once it reaches `Connected` (refreshing [ConnectionSpec.lastEndpointHint]), so the
     * *next* connect always has the freshest known-good spec.
     */
    fun create(ctx: Context, qr: QrData, memory: BikeMemory, io: PlatformIO): BikeConnection {
        val spec = memory.specFor(ctx, qr)
            ?.let { reconcileStoredMode(it, qr, memory.connectorChoice(ctx, qr.ssid)) }
            ?: ConnectionSpec.fromQr(qr)
        val (maxAttempts, flapMax) = retryCapsFor(spec.mode)
        return DefaultBikeConnection(
            transport = selectTransport(spec),
            links = listOf(EasyConnBikeLink(), YunmoBikeLink()),
            spec = spec,
            io = io,
            maxAttempts = maxAttempts,
            flapMaxFailures = flapMax,
            onConnected = { saved ->
                memory.saveSpec(ctx, saved)
                // Record the REAL transport outcome (saveSpec no longer mirrors spec.mode — that was a
                // fromQr guess). The phone-hosts-the-network kinds have no legacy AP/P2P winner index
                // (the classic path never wrote one for them either), so skip them.
                when (saved.mode) {
                    TransportKind.SOFT_AP -> memory.setWinningTransport(ctx, saved.ssid.orEmpty(), "AP")
                    TransportKind.P2P -> memory.setWinningTransport(ctx, saved.ssid.orEmpty(), "P2P")
                    TransportKind.PHONE_HOTSPOT, TransportKind.TETHER -> {}
                }
            },
        )
    }
}
