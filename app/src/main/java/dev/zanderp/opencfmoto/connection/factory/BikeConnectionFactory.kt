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
    }

    /**
     * Retry-parity caps by transport (flip-work-design.md §2): SoftAP/P2P get effectively-infinite caps so the
     * daily-driver factory path matches classic's UNBOUNDED retry — an ended driver has no receiver for a later
     * Wi-Fi re-acquire. PHONE_HOTSPOT (Rieju) keeps the DEFAULT caps: a one-shot BLE handoff with a manual
     * fallback, not a daily-ride reconnect. Returns `(maxAttempts, flapMaxFailures)`. Pure/internal for testing.
     */
    internal fun retryCapsFor(mode: TransportKind): Pair<Int, Int> =
        if (mode == TransportKind.SOFT_AP || mode == TransportKind.P2P) {
            Int.MAX_VALUE to Int.MAX_VALUE
        } else {
            MAX_ATTEMPTS to FLAP_MAX_FAILURES
        }

    /**
     * Build a ready-to-drive connection for [qr]. Garage fast-path (Task 8): a remembered [ConnectionSpec]
     * for this bike skips re-detection (auto-vs-P2P racing, mode probing); a never-seen bike falls back to
     * deriving one fresh from the QR, same as before. Either way, [DefaultBikeConnection] saves the spec
     * back to [memory] once it reaches `Connected` (refreshing [ConnectionSpec.lastEndpointHint]), so the
     * *next* connect always has the freshest known-good spec.
     */
    fun create(ctx: Context, qr: QrData, memory: BikeMemory, io: PlatformIO): BikeConnection {
        val spec = memory.specFor(ctx, qr) ?: ConnectionSpec.fromQr(qr)
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
                // fromQr guess). PHONE_HOTSPOT has no legacy AP/P2P winner index, so skip it.
                when (saved.mode) {
                    TransportKind.SOFT_AP -> memory.setWinningTransport(ctx, saved.ssid.orEmpty(), "AP")
                    TransportKind.P2P -> memory.setWinningTransport(ctx, saved.ssid.orEmpty(), "P2P")
                    TransportKind.PHONE_HOTSPOT -> {}
                }
            },
        )
    }
}
