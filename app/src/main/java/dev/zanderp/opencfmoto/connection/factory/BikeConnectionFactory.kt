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
     * Build a ready-to-drive connection for [qr]. For now the spec is always derived fresh from the QR;
     * the Garage fast-path (remembered [ConnectionSpec], skipping re-detection) is wired in Task 8.
     *
     * @param memory accepted now so the Task-8 fast-path is a one-line change here (no call-site churn).
     */
    fun create(ctx: Context, qr: QrData, memory: BikeMemory, io: PlatformIO): BikeConnection {
        // TODO(Task 8): val spec = memory.specFor(qr) ?: ConnectionSpec.fromQr(qr)  — Garage skip-detection.
        val spec = ConnectionSpec.fromQr(qr)
        return DefaultBikeConnection(
            transport = selectTransport(spec),
            links = listOf(EasyConnBikeLink(), YunmoBikeLink()),
            spec = spec,
            io = io,
        )
    }
}
