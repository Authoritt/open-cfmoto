// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection

import android.net.Network
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.connection.factory.BikeConnection
import dev.zanderp.opencfmoto.connection.factory.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one live factory-built [BikeConnection] (mirrors `ProjectionHolder` / `BikeLink` process-globals; see
 * `flip-work-design.md` §1). Null = no factory connection active ⇒ the classic 450NK path owns
 * reconnect/teardown byte-for-byte. Set in `CfmotoConnect.joinWifi` at the factory call sites (the dev-toggle
 * SoftAP/P2P branch AND the Rieju phone-hotspot branch); cleared by [disconnectAndClear] from every teardown
 * call-site. Holds the [BikeConnection] interface (not the concrete `DefaultBikeConnection`) so
 * `BikeConnectionFactory.create` keeps returning the interface.
 */
object BikeConnectionHolder {
    // Backed by a StateFlow so the Compose cockpit gauge can react the instant a factory connection appears
    // or clears (a plain @Volatile var isn't observable, and the holder is set AFTER ConnectionState flips to
    // JOINING_WIFI, so the UI can't key off the phase). Thread-safe reads/writes via the StateFlow.
    private val _connection = MutableStateFlow<BikeConnection?>(null)

    /** Reactive view of the live factory connection (null = none) — for the cockpit's connection gauge. */
    val connectionFlow: StateFlow<BikeConnection?> = _connection.asStateFlow()

    /** The one live factory-built connection, or null. Read-only snapshot of [connectionFlow]. */
    val connection: BikeConnection? get() = _connection.value

    /**
     * Scope for the terminal-state mirror ([mirrorTerminalStates]); exactly one collector at a time. The
     * swap is serialized on [mirrorLock] because `set` runs on the main thread for an interactive connect
     * but on the service thread for the background auto-connect.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mirrorLock = Any()
    @Volatile private var mirrorJob: Job? = null

    /** Replace any prior live connection (defensive: callers always tear down first); await the prior's release. */
    fun set(c: BikeConnection) {
        val prev = _connection.value
        // Snapshot BEFORE subscribing: a connection that is already past Idle has, by definition, started,
        // so any Idle we later see is a TERMINAL one. Without this, a driver that reached its terminal Idle
        // before the collector was scheduled would look like a fresh, never-started connection.
        val alreadyStarted = c.state.value !is ConnState.Idle
        _connection.value = c
        synchronized(mirrorLock) {
            mirrorJob?.cancel() // the outgoing connection's terminal Idle must not land on the new one's phase
            mirrorJob = scope.launch { mirrorTerminalStates(c, alreadyStarted) }
        }
        // Outside the lock: this blocks (bounded) on the prior driver's teardown — see review I1.
        prev?.disconnectAndAwaitTeardown()
    }

    /**
     * Bridge the factory's [ConnState] back into the legacy process-global [ConnectionState] — TERMINAL
     * states only.
     *
     * Why this exists: `CfmotoConnect.joinWifi` sets [Phase.JOINING_WIFI] (`busy = true`) for every connect,
     * and on the factory path nothing ever cleared it — the driver publishes its outcome on its own
     * `state` flow, which the legacy state machine never saw. So once a factory connection ended in
     * [ConnState.Error] (now genuinely reachable: the retry caps are finite), `autoConnectAllowed` kept
     * refusing ("already Connecting to bike Wi-Fi…") and BOTH auto-connect paths stayed dead until the
     * rider hit Stop by hand.
     *
     * Rules:
     *  - [ConnState.Error] → [Phase.ERROR] with the driver's reason as the detail (the rider sees WHY).
     *  - [ConnState.Idle] → [Phase.STOPPED] (not busy), but only once the connection has left Idle (either
     *    observed here, or [alreadyStarted] at subscribe time): a StateFlow replays its current value to a
     *    new collector and every connection starts Idle, so mirroring that first one would stamp STOPPED
     *    over the JOINING_WIFI the caller just set.
     *  - Never touch [Phase.STREAMING]: the prober owns the happy path and drives it itself.
     *  - Never mirror for a connection that is no longer the one this holder points at.
     *
     * Progress states are deliberately NOT mirrored: the classic path's own phases (JOINING_WIFI →
     * PXC_CONNECTING → STREAMING) already narrate the connect, and duplicating them here would fight the
     * prober for the same global.
     */
    private suspend fun mirrorTerminalStates(c: BikeConnection, alreadyStarted: Boolean) {
        var leftIdle = alreadyStarted
        c.state.collect { st ->
            if (_connection.value !== c) return@collect // superseded — its terminal state is not ours
            when (st) {
                is ConnState.Error -> {
                    if (ConnectionState.phase != Phase.STREAMING) {
                        LogBus.log("[FACTORY] connection failed: ${st.reason}")
                        ConnectionState.set(Phase.ERROR, st.reason)
                    }
                }
                is ConnState.Idle -> {
                    if (leftIdle && ConnectionState.phase != Phase.STREAMING) {
                        ConnectionState.set(Phase.STOPPED)
                    }
                }
                else -> leftIdle = true
            }
        }
    }

    /**
     * Idempotent teardown: null FIRST (a concurrent re-acquire — which fires on the BikeWifi ConnectivityThread,
     * NOT the main looper — then sees "no factory"), then AWAIT the handle's teardown (transport.close() →
     * BikeWifi.leave()) so a connect that FOLLOWS (e.g. a mode switch) is strictly ordered after the Wi-Fi
     * release and can't race an in-flight leave() that would null the just-started session (review I1). No-op
     * when null ⇒ the OFF/classic path is unaffected (byte-for-byte).
     */
    fun disconnectAndClear() {
        val c = _connection.value
        _connection.value = null
        // Stop mirroring BEFORE the teardown: `stop()` sets its own STOPPED right after this call, and the
        // driver's terminal Idle must not race it (the `_connection.value !== c` guard already covers it;
        // cancelling is the cheap belt-and-suspenders that also ends the collector).
        synchronized(mirrorLock) {
            mirrorJob?.cancel()
            mirrorJob = null
        }
        c?.disconnectAndAwaitTeardown()
    }

    /** SoftAP re-acquire hinge (design §2): drive the live factory connection's own re-establish. No-op when
     *  null. This is the single path `BikeLink.onWifiReacquired`'s fork calls (review M4). */
    fun onWifiReacquired(network: Network?) {
        _connection.value?.onWifiReacquired(network)
    }
}
