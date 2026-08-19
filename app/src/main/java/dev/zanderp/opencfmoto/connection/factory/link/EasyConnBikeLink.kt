// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.link

import android.content.Context
import dev.zanderp.opencfmoto.EasyConnProber
import dev.zanderp.opencfmoto.connection.factory.BikeEndpoint
import dev.zanderp.opencfmoto.connection.factory.BikeLink
import dev.zanderp.opencfmoto.connection.factory.ConnectionSpec
import dev.zanderp.opencfmoto.connection.factory.LinkSession
import dev.zanderp.opencfmoto.connection.factory.PlatformIO
import dev.zanderp.opencfmoto.connection.factory.TransportKind

/**
 * Layer-2 EasyConn link (design doc 2026-08-18 §7) — wraps the proven [EasyConnProber] (the phone-as-PXC-
 * server handshake: opens the :10920-10922 servers, sends MDNS_RESPOND, drives the dash's call-back). Tried
 * before Yunmo.
 *
 * WRAPS `EasyConnProber` — [establish] starts it with the same arguments the two `CfmotoConnect` call sites
 * use (SoftAP passes the [android.net.Network] and lets the prober derive the gateway, CfmotoConnect.kt:256;
 * P2P passes `network = null` + the group-owner gateway + our bind IP, CfmotoConnect.kt:431-435) and returns
 * a [LinkSession] whose [LinkSession.close] stops the prober.
 *
 * `EasyConnProber.start()` is fire-and-forget (it spawns discovery/probe threads and returns), so a link is
 * "established" the moment the prober is started, not when the PXC handshake confirms — a mid-handshake
 * failure surfaces later through the prober's own reconnect/`ConnectionState.ERROR`, consistent with the
 * Task-6 "device-verified wrap" model (the factory's transport/link watchdogs are wired in a later task).
 *
 * @param serverMode phone-as-server seam for the Task-9 PhoneHotspot path (design §11: `EasyConnProber` must
 *   accept the phone-as-server role — not yet, so this is currently a forward-looking flag). The effective
 *   value ORs in [BikeEndpoint.phoneIsServer]; SoftAP/P2P are always false → unchanged behavior.
 */
class EasyConnBikeLink(
    private val serverMode: Boolean = false,
) : BikeLink {

    // The wrapped prober; reused across re-establishes (its start() force-restarts a live session).
    @Volatile private var prober: EasyConnProber? = null

    override suspend fun establish(
        ctx: Context,
        endpoint: BikeEndpoint,
        spec: ConnectionSpec,
        io: PlatformIO,
    ): LinkSession {
        val logCb: (String) -> Unit = { msg -> io.log(TAG, msg) }
        val useServerMode = serverMode || endpoint.phoneIsServer // false for SoftAP/P2P (design §11)
        val prober = this.prober ?: EasyConnProber(ctx.applicationContext, logCb).also { this.prober = it }

        when (endpoint.kind) {
            // P2P: no bindable Network — hand the prober the GO gateway + our bind IP (CfmotoConnect.kt:431).
            TransportKind.P2P -> prober.start(
                network = null,
                gatewayOverride = endpoint.host,
                bindIpOverride = endpoint.bindIp,
            )
            // SoftAP (and, once server mode lands, PhoneHotspot): pass the bound Network; the prober
            // resolves the gateway itself, exactly as CfmotoConnect.joinWifi does (CfmotoConnect.kt:256).
            else -> prober.start(endpoint.network)
        }
        io.log(TAG, "EasyConn PXC prober started (kind=${endpoint.kind}, serverMode=$useServerMode)")

        return object : LinkSession {
            override fun close() {
                runCatching { prober.stop() }
            }
        }
    }

    override fun stop() {
        runCatching { prober?.stop() }
    }

    private companion object {
        private const val TAG = "EasyConnBikeLink"
    }
}
