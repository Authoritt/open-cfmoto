// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.link

import android.content.Context
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeLink
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.LinkSession
import dev.zanderp.opencfmoto.connection.factory.PlatformIO

/**
 * Layer-2 EasyConn link (design doc 2026-08-18 §7 — wraps `EasyConnProber`: the PXC handshake, which must
 * support server mode when `endpoint.phoneIsServer` for the phone-hotspot path). Tried before Yunmo.
 *
 * Shell only: the real wrapper arrives in Task 6, so [establish] throws to keep the factory (Task 4) and
 * the state machine (Task 5) compiling and unit-testable in isolation. Kept intentionally tiny.
 */
class EasyConnBikeLink : BikeLink {
    override suspend fun establish(
        ctx: Context,
        endpoint: BikeEndpoint,
        spec: ConnectionSpec,
        io: PlatformIO,
    ): LinkSession = throw NotImplementedError("wired in Task 6")

    override fun stop() {}
}
