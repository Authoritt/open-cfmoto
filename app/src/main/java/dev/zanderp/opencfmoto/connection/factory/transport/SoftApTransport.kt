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
 * Layer-1 SoftAP transport (design doc 2026-08-18 §7 — wraps `BikeWifi.reuseOrJoin`: join the bike's
 * SoftAP and hand back the gateway endpoint `192.168.x.1`).
 *
 * Shell only: the real wrapper arrives in Task 6, so [open] throws to keep the factory (Task 4) and the
 * state machine (Task 5) compiling and unit-testable in isolation. Kept intentionally tiny.
 */
class SoftApTransport : BikeTransport {
    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint =
        throw NotImplementedError("wired in Task 6")

    override fun close() {}
}
