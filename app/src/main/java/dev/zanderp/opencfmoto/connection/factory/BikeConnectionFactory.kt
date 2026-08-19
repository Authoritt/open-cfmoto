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
     * Build a ready-to-drive connection for [qr]. Garage fast-path (Task 8): a remembered [ConnectionSpec]
     * for this bike skips re-detection (auto-vs-P2P racing, mode probing); a never-seen bike falls back to
     * deriving one fresh from the QR, same as before. Either way, [DefaultBikeConnection] saves the spec
     * back to [memory] once it reaches `Connected` (refreshing [ConnectionSpec.lastEndpointHint]), so the
     * *next* connect always has the freshest known-good spec.
     */
    fun create(ctx: Context, qr: QrData, memory: BikeMemory, io: PlatformIO): BikeConnection {
        val spec = memory.specFor(ctx, qr) ?: ConnectionSpec.fromQr(qr)
        // Reconnect parity (flip-work-design.md §2): the classic 450NK path retries FOREVER (BikeWifi keeps
        // the WifiNetworkSpecifier request pending and re-grabs the AP the instant it returns), so the
        // daily-driver SoftAP/P2P factory path must not self-terminate on a long outage or a flap — an ended
        // driver has no receiver for a later re-acquire. PHONE_HOTSPOT (Rieju) keeps the default caps: a
        // one-shot BLE handoff with a manual fallback, not a daily-ride reconnect.
        val soft = spec.mode == TransportKind.SOFT_AP || spec.mode == TransportKind.P2P
        return DefaultBikeConnection(
            transport = selectTransport(spec),
            links = listOf(EasyConnBikeLink(), YunmoBikeLink()),
            spec = spec,
            io = io,
            maxAttempts = if (soft) Int.MAX_VALUE else MAX_ATTEMPTS,
            flapMaxFailures = if (soft) Int.MAX_VALUE else FLAP_MAX_FAILURES,
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
