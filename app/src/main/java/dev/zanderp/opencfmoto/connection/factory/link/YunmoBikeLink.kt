// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.link

import android.content.Context
import dev.zanderp.opencfmoto.BikeProfileHolder
import dev.zanderp.opencfmoto.YunmoFrame
import dev.zanderp.opencfmoto.YunmoLink
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeLink
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.LinkSession
import dev.zanderp.opencfmoto.connection.factory.PlatformIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Layer-2 Yunmo link (design doc 2026-08-18 §7) — promotes the SoftAP Yunmo fallback that today hides
 * inside [dev.zanderp.opencfmoto.EasyConnProber]'s private `tryYunmoFallback` (EasyConnProber.kt:451) to a
 * first-class [BikeLink], tried after EasyConn (X-Cape 1200 / MOTOMORINI dashes that speak Yunmo on
 * `:8200` instead of EasyConn `:10930`).
 *
 * WRAPS [YunmoLink] — [establish] instantiates it and runs the blocking `connectAndStream` (socket connect
 * + canvas negotiation + stream threads) on [Dispatchers.IO], mirroring `tryYunmoFallback`'s width/height
 * pick (the prober's negotiated `negW/negH` are internal to it, so we fall back to the AA spec, then the
 * 800×480 default). The bike IP is [BikeEndpoint.host] — for a SoftAP endpoint that is the gateway the
 * [dev.zanderp.opencfmoto.connection.factory.transport.SoftApTransport] resolved.
 */
class YunmoBikeLink : BikeLink {

    @Volatile private var link: YunmoLink? = null

    override suspend fun establish(
        ctx: Context,
        endpoint: BikeEndpoint,
        spec: ConnectionSpec,
        io: PlatformIO,
    ): LinkSession {
        // A previous instance may linger after a link drop / re-establish; stop it before opening a new one.
        runCatching { link?.stop() }

        val logCb: (String) -> Unit = { msg -> io.log(TAG, msg) }
        val yunmo = YunmoLink(ctx.applicationContext, logCb)
        this.link = yunmo

        // Mirror EasyConnProber.tryYunmoFallback's size pick, minus the prober-internal negW/negH.
        val aa = BikeProfileHolder.aaVideo
        val w = if (aa.width >= 64) aa.width else 800
        val h = if (aa.height >= 64) aa.height else 480

        val ok = try {
            // connectAndStream is blocking (socket I/O + ~5s dim await); run it off the driver dispatcher.
            // If the coroutine is cancelled meanwhile, withContext throws CancellationException on resume,
            // which the catch turns into a stop() before rethrowing — no dangling socket/threads.
            withContext(Dispatchers.IO) {
                yunmo.connectAndStream(endpoint.host, endpoint.network, w, h)
            }
        } catch (t: Throwable) {
            runCatching { yunmo.stop() }
            throw t
        }
        if (!ok) {
            runCatching { yunmo.stop() }
            throw IllegalStateException(
                "Yunmo link did not connect at ${endpoint.host.hostAddress}:${YunmoFrame.DEFAULT_PORT}",
            )
        }

        return object : LinkSession {
            override fun close() {
                runCatching { yunmo.stop() }
            }
        }
    }

    override fun stop() {
        runCatching { link?.stop() }
    }

    private companion object {
        private const val TAG = "YunmoBikeLink"
    }
}
