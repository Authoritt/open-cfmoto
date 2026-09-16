// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.zanderp.opencfmoto.BikeWifi
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeTransport
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.PlatformIO
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Layer-1 SoftAP transport (design doc 2026-08-18 §7) — a thin coroutine wrapper over the proven
 * [BikeWifi.reuseOrJoin]: join the bike's WPA2 SoftAP via `WifiNetworkSpecifier` and hand back a
 * [BikeEndpoint] carrying the bound [Network] and the bike gateway (`192.168.x.1`).
 *
 * This class WRAPS `BikeWifi` — it does not reimplement the join. It only bridges the callback API
 * ([BikeWifi.reuseOrJoin]'s `onAvailable`/`onLost`) to `suspend fun open`, resolving the gateway the
 * same way the link layer does (mirrors [dev.zanderp.opencfmoto.EasyConnProber]'s private
 * `resolveGateway`, which stays authoritative for the actual PXC link — see [EasyConnBikeLink]).
 */
class SoftApTransport : BikeTransport {

    // Confined-then-read for close(): captured on open() so the parameterless close() can release
    // the exact BikeWifi request/callback this instance opened. @Volatile: open() runs on the driver
    // coroutine, close() may run from the driver's teardown on a different thread.
    @Volatile private var appCtx: Context? = null
    @Volatile private var logCb: ((String) -> Unit)? = null

    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint {
        val appCtx = ctx.applicationContext
        val ssid = spec.ssid
            ?: throw IllegalStateException("SoftAP transport requires an SSID (spec.ssid is null)")
        val psk = spec.pwd.orEmpty()
        val logCb: (String) -> Unit = { msg -> io.log(TAG, msg) }
        this.appCtx = appCtx
        this.logCb = logCb

        // BikeWifi retries a failed/absent join internally (it does NOT call onLost on a join timeout —
        // it re-arms the specifier request), so bound open() with the proven first-join window; on
        // expiry release the request and throw so the driver's backoff takes over (§6). The inner
        // continuation's invokeOnCancellation releases on BOTH the timeout-cancel and a disconnect-cancel.
        val endpoint = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            suspendCancellableCoroutine<BikeEndpoint> { cont ->
                val resumed = AtomicBoolean(false)
                cont.invokeOnCancellation { runCatching { BikeWifi.leave(appCtx, logCb) } }
                BikeWifi.reuseOrJoin(
                    context = appCtx,
                    ssid = ssid,
                    psk = psk,
                    // onAvailable fires exactly once for the first acquisition (BikeWifi's own
                    // firstDelivered guard); later re-acquires go via BikeLink.onWifiReacquired, not here.
                    onAvailable = { network ->
                        if (resumed.compareAndSet(false, true)) {
                            val gateway = resolveGateway(appCtx, network)
                            if (gateway == null) {
                                cont.resumeWithException(
                                    IllegalStateException(
                                        "SoftAP '$ssid' joined but the bike gateway IP could not be resolved",
                                    ),
                                )
                            } else {
                                cont.resume(
                                    BikeEndpoint(
                                        network = network,
                                        host = gateway,
                                        bindIp = null,
                                        kind = TransportKind.SOFT_AP,
                                        phoneIsServer = false,
                                    ),
                                )
                            }
                        }
                    },
                    onLost = {
                        if (resumed.compareAndSet(false, true)) {
                            cont.resumeWithException(
                                IllegalStateException("bike SoftAP '$ssid' network lost before it came up"),
                            )
                        }
                    },
                    log = logCb,
                )
            }
        }
        return endpoint
            ?: throw IllegalStateException("SoftAP join for '$ssid' timed out after ${JOIN_TIMEOUT_MS}ms")
    }

    override fun close() {
        val ctx = appCtx ?: return
        val log = logCb ?: { _: String -> }
        runCatching { BikeWifi.leave(ctx, log) }
        appCtx = null
        logCb = null
    }

    /**
     * The bike's gateway on the joined SoftAP. A faithful mirror of
     * [dev.zanderp.opencfmoto.EasyConnProber]'s private `resolveGateway`: (1) the default-route gateway,
     * (2) the `.1` host of our own subnet when a bike advertises no default route, (3) the first DNS
     * server. Replicated (not called) because that method is private and this task must not edit
     * `EasyConnProber`; the prober still derives its own copy for the PXC link, so the two always agree
     * (identical logic on the same [Network]). The Yunmo fallback consumes this value as the bike IP.
     */
    private fun resolveGateway(context: Context, network: Network): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(network) ?: return null
        for (r in lp.routes) {
            if (r.isDefaultRoute) {
                val gw = r.gateway
                if (gw is Inet4Address && !gw.isAnyLocalAddress) return gw
            }
        }
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                gatewayForSubnet(a, la.prefixLength)?.let { return it }
            }
        }
        return lp.dnsServers.filterIsInstance<Inet4Address>().firstOrNull()
    }

    /** The `.1` host of [addr]'s subnet (network address | 1). Mirrors `EasyConnProber.gatewayForSubnet`. */
    private fun gatewayForSubnet(addr: Inet4Address, prefix: Int): Inet4Address? {
        if (prefix !in 1..31) return null
        return try {
            val b = addr.address
            val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val mask = -1 shl (32 - prefix)
            val gw = (ip and mask) or 1
            if (gw == ip) return null // we already are ".1"; no distinct gateway to reach
            val out = byteArrayOf(
                ((gw ushr 24) and 0xFF).toByte(),
                ((gw ushr 16) and 0xFF).toByte(),
                ((gw ushr 8) and 0xFF).toByte(),
                (gw and 0xFF).toByte(),
            )
            Inet4Address.getByAddress(out) as? Inet4Address
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        private const val TAG = "SoftApTransport"

        /**
         * Upper bound on one `open()` join attempt. Mirrors `BikeWifi.FIRST_JOIN_TIMEOUT_MS` (90s) — the
         * proven window that lets a slow first association (system Wi-Fi picker) complete — after which we
         * release and let the driver retry with backoff rather than hang forever.
         */
        private const val JOIN_TIMEOUT_MS = 90_000L
    }
}
