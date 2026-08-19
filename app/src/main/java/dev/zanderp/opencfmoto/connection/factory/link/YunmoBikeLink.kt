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
 * Layer-2 Yunmo link (design doc 2026-08-18 §7 — the existing Yunmo protocol as an explicit [BikeLink],
 * tried after EasyConn as the fallback).
 *
 * Shell only: real wrapper in Task 6, so [establish] throws until then. Kept intentionally tiny.
 */
class YunmoBikeLink : BikeLink {
    override suspend fun establish(
        ctx: Context,
        endpoint: BikeEndpoint,
        spec: ConnectionSpec,
        io: PlatformIO,
    ): LinkSession = throw NotImplementedError("wired in Task 6")

    override fun stop() {}
}
