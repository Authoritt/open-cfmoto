// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.content.Context
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.BikeWifiP2p
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeTransport
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.PlatformIO
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Layer-1 Wi-Fi Direct (P2P) transport (design doc 2026-08-18 §7) — a coroutine wrapper over the proven
 * [BikeWifiP2p.connect]: form/join the bike's Wi-Fi Direct group and hand back a [BikeEndpoint] with no
 * bindable [android.net.Network] (`network = null`) but the phone's P2P interface `bindIp` and the
 * group-owner (bike) gateway as `host`.
 *
 * WRAPS `BikeWifiP2p` — the deviceAddress-from-`mac` join and the ±1 MAC quirk live inside that helper
 * ([BikeWifiP2p.macMatches]); this class only bridges its callbacks and mirrors the connect-timeout
 * selection from `CfmotoConnect.joinWifiP2p` (CfmotoConnect.kt:410-415).
 *
 * `BikeWifiP2p.connect` takes a [QrData]. [ConnectionSpec] does not persist the raw QR `action` bitmask,
 * so we reconstruct the minimal QrData the join actually reads (ssid/pwd/mac); see [qrFor].
 */
class P2pTransport : BikeTransport {

    @Volatile private var logCb: ((String) -> Unit)? = null

    override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint {
        val appCtx = ctx.applicationContext
        val logCb: (String) -> Unit = { msg -> io.log(TAG, msg) }
        this.logCb = logCb

        val qr = qrFor(spec)
        // Mirror CfmotoConnect.joinWifiP2p:410-415: give a proven P2P device the full window; bail fast
        // (6s) only when a SoftAP fallback exists; otherwise the full window. (Reconstructed qr.supportsAp
        // = "SoftAP creds present" — see qrFor; without the raw action bitmask this only tunes the timeout,
        // never correctness. The factory has no in-connector SoftAP fallback: on failure the driver retries.)
        val known = BikeMemory.winningTransport(appCtx, qr.ssid)
        val timeoutMs = when {
            known == "P2P" -> BikeWifiP2p.CONNECT_TIMEOUT_MS
            qr.supportsAp && qr.pwd.isNotEmpty() -> 6_000L
            else -> BikeWifiP2p.CONNECT_TIMEOUT_MS
        }

        return suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            cont.invokeOnCancellation { runCatching { BikeWifiP2p.stop(logCb) } }
            BikeWifiP2p.connect(
                context = appCtx,
                qr = qr,
                onConnected = { bindIp, gatewayIp ->
                    if (resumed.compareAndSet(false, true)) {
                        cont.resume(
                            BikeEndpoint(
                                network = null,
                                host = gatewayIp,
                                bindIp = bindIp,
                                kind = TransportKind.P2P,
                                phoneIsServer = false,
                            ),
                        )
                    }
                },
                // BikeWifiP2p's own timeout fires onFailed too, so this also covers the "no group formed" case.
                onFailed = { reason ->
                    if (resumed.compareAndSet(false, true)) {
                        cont.resumeWithException(IllegalStateException("Wi-Fi Direct join failed: $reason"))
                    }
                },
                log = logCb,
                timeoutMs = timeoutMs,
            )
        }
    }

    override fun close() {
        val log = logCb ?: { _: String -> }
        runCatching { BikeWifiP2p.stop(log) }
        logCb = null
    }

    /**
     * The minimal [QrData] `BikeWifiP2p.connect` reads: `ssid` (DIRECT-* detection / credential join /
     * peer-name match), `pwd` (credential join), `mac` (the deviceAddress join + ±1 match). `action` is
     * used by the join only for a log line; we set the P2P bit, plus the AP bit when SoftAP creds exist so
     * the mirrored `supportsAp` timeout gate matches CfmotoConnect's intent ("a SoftAP fallback exists").
     */
    private fun qrFor(spec: ConnectionSpec): QrData {
        val hasApCreds = !spec.pwd.isNullOrEmpty()
        return QrData(
            ssid = spec.ssid.orEmpty(),
            pwd = spec.pwd.orEmpty(),
            auth = null,
            mac = spec.bleMac,
            name = null,
            action = P2P_ACTION_BIT or (if (hasApCreds) AP_ACTION_BIT else 0),
            modelId = null,
            sn = null,
            channel = null,
        )
    }

    private companion object {
        private const val TAG = "P2pTransport"
        private const val AP_ACTION_BIT = 1 // QrData.supportsAp: (action and 1) != 0
        private const val P2P_ACTION_BIT = 8 // QrData.supportsP2p: (action and 8) != 0
    }
}
