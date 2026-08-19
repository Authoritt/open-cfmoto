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
 * Layer-1 phone-hotspot transport — the Rieju connector (design doc 2026-08-18 §8): the phone becomes the
 * Wi-Fi Direct group owner / PXC server at `192.168.49.1` and pushes AP credentials to the dash over BLE
 * (EcBtp `0x52`), so `phoneIsServer = true`. Foreground-only (needs an `Activity`).
 *
 * Shell only: real wrapper in Task 6, so [open] throws until then. Kept intentionally tiny.
 */
class PhoneHotspotTransport : BikeTransport {
    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint =
        throw NotImplementedError("wired in Task 6")

    override fun close() {}
}
