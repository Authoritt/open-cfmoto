// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.app.Activity
import android.content.Context
import android.net.Network
import java.net.Inet4Address
import kotlinx.coroutines.flow.StateFlow

/**
 * Two-layer bike connection factory contracts (design:
 * `docs/superpowers/specs/2026-08-18-bike-connection-factory-design.md`). Layer 1 [BikeTransport]
 * (SoftAP/P2P/PhoneHotspot) gets the phone onto the bike's network and yields a [BikeEndpoint];
 * Layer 2 [BikeLink] (EasyConn, falling back to Yunmo) speaks the bike's app protocol over that
 * endpoint and yields a [LinkSession]. [BikeConnection] owns the single [ConnState] lifecycle;
 * [PlatformIO] is the seam the app supplies (Activity, logging, the video sink) so this package
 * stays free of platform/video wiring (Overtake discipline).
 *
 * Types only in this file — no logic; implementations arrive in later tasks of the same plan.
 *
 * Note: [BikeLink] and [Phase] intentionally share their simple name with unrelated legacy types in
 * the parent `dev.zanderp.opencfmoto` package (`BikeLink.kt`'s process-global PXC handoff object,
 * `ConnectionState.kt`'s richer `Phase` enum) — different package, no collision, wrap-not-rewrite.
 */

/** How the phone reaches the bike's control channel. */
enum class TransportKind { SOFT_AP, P2P, PHONE_HOTSPOT }

/** A step of [ConnState.Connecting], surfaced to the UI as coarse progress. */
enum class Phase { Discovering, JoinTransport, Handshake, Starting }

/**
 * The network/address a [BikeTransport] handed back once the phone can reach the bike, plus enough
 * detail for the link layer (and reconnects) to use it.
 */
data class BikeEndpoint(
    val network: Network?,
    val host: Inet4Address,
    val bindIp: Inet4Address?,
    val kind: TransportKind,
    val phoneIsServer: Boolean,
)

/** The single connection lifecycle state, reduced by `DefaultBikeConnection`'s `reduce` (later task). */
sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val phase: Phase, val detail: String? = null) : ConnState
    data class Connected(val endpoint: BikeEndpoint) : ConnState
    data class Retrying(val reason: String, val nextInMs: Long) : ConnState
    data class Error(val reason: String, val recoverable: Boolean) : ConnState
}

/** App-facing handle to a bike connection: observe [state], drive it with [connect]/[disconnect]. */
interface BikeConnection {
    val state: StateFlow<ConnState>
    fun connect()
    fun disconnect()
}

/** A live link-layer session (EasyConn/Yunmo) established over a [BikeEndpoint]. */
interface LinkSession {
    fun close()
}

/**
 * Layer 1: gets the phone and the bike onto the same network (SoftAP/P2P/PhoneHotspot) and returns
 * the resulting [BikeEndpoint]. Does not speak the bike's app protocol — that is [BikeLink].
 */
interface BikeTransport {
    suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint
    fun close()
}

/**
 * Layer 2: speaks the bike's app protocol (EasyConn, falling back to Yunmo) over an already-open
 * [BikeEndpoint] and returns the resulting [LinkSession].
 */
interface BikeLink {
    suspend fun establish(ctx: Context, endpoint: BikeEndpoint, spec: ConnectionSpec, io: PlatformIO): LinkSession
    fun stop()
}

/** Platform seam the app supplies so this package stays free of Activity/UI/video wiring. */
interface PlatformIO {
    /**
     * The application [Context]. Headless SoftAP/P2P auto-connect (design section 6) runs with
     * [activityOrNull] == null yet still needs a Context for `ConnectivityManager` / `WifiManager` /
     * `WifiP2pManager`, so this is the always-available seam the transports/links reach through.
     * [activityOrNull] stays reserved for the interactive-dialog paths that genuinely need an Activity.
     */
    val appContext: Context
    val log: (String, String) -> Unit
    fun activityOrNull(): Activity?
    fun videoSink(): VideoSink
}

/** Where an established [LinkSession] hands its video off to (owned by the app, not this package). */
interface VideoSink {
    fun attach(session: LinkSession)
}
