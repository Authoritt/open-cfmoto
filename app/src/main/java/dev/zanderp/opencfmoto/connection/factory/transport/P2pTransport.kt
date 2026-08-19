// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.content.Context
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeTransport
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.PlatformIO

/**
 * Layer-1 Wi-Fi Direct (P2P) transport (design doc 2026-08-18 §7 — wraps `BikeWifiP2p.connect`: form/join
 * the P2P group, deviceAddress from `mac` with the MAC±1 quirk, endpoint has no `Network` but a `bindIp`).
 *
 * Shell only: real wrapper in Task 6, so [open] throws until then. Kept intentionally tiny.
 */
class P2pTransport : BikeTransport {
    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint =
        throw NotImplementedError("wired in Task 6")

    override fun close() {}
}
