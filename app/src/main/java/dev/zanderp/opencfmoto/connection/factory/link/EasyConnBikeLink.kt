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
 * @param serverMode phone-as-server seam for the Task-9 PhoneHotspot path (design §11). The effective value
 *   ORs in [BikeEndpoint.phoneIsServer], which is true only for `PhoneHotspotTransport`; SoftAP/P2P stay
 *   false → unchanged behavior. The prober is already structurally phone-as-server — it opens the
 *   :10920-10922 listeners and the bike dials back (EasyConnProber.kt:25) — so the phone-hotspot path feeds
 *   it the group-owner bind IP (192.168.49.1) so those listeners bind to the P2P interface. The remaining
 *   owner-test gap is whether the Rieju dash auto-dials the listeners after joining (design §8/§11).
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
        // NOTE: useServerMode is computed + logged ONLY — it does not reach EasyConnProber.start (single
        // signature, no server-mode param). The prober is structurally phone-as-server already; the real
        // server-mode completion is owner-test-gated (design §11). Don't mistake this for wired behavior.
        val useServerMode = serverMode || endpoint.phoneIsServer // true only on the phone-hotspot path (design §11)
        val prober = this.prober ?: EasyConnProber(ctx.applicationContext, logCb).also { this.prober = it }

        when (endpoint.kind) {
            // P2P and PhoneHotspot: no bindable Network — hand the prober our bind IP + the peer/host address
            // so it opens its :10920-10922 servers on the right interface (CfmotoConnect.kt:431). On the
            // phone-hotspot path the phone is the group owner, so bindIp == host == 192.168.49.1: the prober
            // (already phone-as-server — it listens and the bike dials back, EasyConnProber.kt:25) binds its
            // servers to our GO address. Whether the Rieju dash then auto-dials those listeners or needs an
            // active probe to its DHCP-assigned IP is the owner-test gap (design §11, §8 step 4).
            TransportKind.P2P, TransportKind.PHONE_HOTSPOT -> prober.start(
                network = null,
                gatewayOverride = endpoint.host,
                bindIpOverride = endpoint.bindIp,
            )
            // SoftAP: pass the bound Network; the prober resolves the gateway itself, exactly as
            // CfmotoConnect.joinWifi does (CfmotoConnect.kt:256).
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
