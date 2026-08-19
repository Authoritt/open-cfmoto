// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.content.Context
import android.net.Network
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Inputs to the connection state machine (design doc 2026-08-18 section 6). Emitted by
 * [DefaultBikeConnection] as it drives the two layers, and by the (Task 6) transport/link watchdogs when
 * something drops.
 *
 * Note [LinkEstablished] carries its [endpoint] (mirroring [TransportOpened]): the committed
 * [ConnState.Connecting]/[ConnState.Retrying] do not store an endpoint, so a *pure* [reduce] can only
 * rebuild [ConnState.Connected] if the endpoint rides on the success event. Since the link always
 * establishes over a known endpoint (fresh from `transport.open`, or the still-live one on a mid-ride
 * re-establish), this is free to supply and keeps the reducer referentially transparent.
 */
internal sealed interface ConnEvent {
    data object StartRequested : ConnEvent
    data class TransportOpened(val endpoint: BikeEndpoint) : ConnEvent
    data class LinkEstablished(val endpoint: BikeEndpoint) : ConnEvent
    data class LinkDropped(val reason: String) : ConnEvent
    data class TransportLost(val reason: String) : ConnEvent
    data class Failed(val reason: String, val recoverable: Boolean) : ConnEvent
    data object Disconnected : ConnEvent
}

/** First retry delay; the driver grows it capped-exponentially per consecutive attempt. */
internal const val RETRY_BASE_MS = 1_000L

/** Upper bound on the backoff so a long outage still retries about twice a minute. */
internal const val RETRY_CAP_MS = 30_000L

/** Consecutive failures (no clean Connected in between) before a retry is declared fatal ([ConnState.Error]). */
internal const val MAX_ATTEMPTS = 6

/** Flap window: total drops within this span (even with brief successes between) escalate to fatal (F6). */
internal const val FLAP_WINDOW_NS = 60_000_000_000L // 60 s

/** Total drops within [FLAP_WINDOW_NS] before a pathologically flapping link is declared fatal (F6). */
internal const val FLAP_MAX_FAILURES = 12

/** Bounded wait for `disconnectAndAwaitTeardown` — teardown does no network I/O, so this only caps a wedged
 *  driver; the caller (main thread at a mode switch) must never hang unboundedly (review I1). */
internal const val TEARDOWN_AWAIT_MS = 2_000L

/** Capped exponential backoff: base * 2^(attempt-1), clamped to [RETRY_CAP_MS]. `attempt` starts at 1. */
internal fun backoffMs(attempt: Int): Long =
    (RETRY_BASE_MS shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(RETRY_CAP_MS)

/**
 * The single, pure transition of the connection lifecycle (design doc section 6). Given the current
 * [ConnState] and an incoming [ConnEvent], compute the next state — no I/O, no time, no mutation — so the
 * whole reconnect policy is unit-testable without Android or a bike.
 *
 * Both drop kinds go to [ConnState.Retrying] so the UI shows a uniform "reconnecting in N ms" during
 * backoff (F5). They differ only in what the *driver* re-runs afterward: a [ConnEvent.LinkDropped]
 * re-`establish()`s the LINK on the still-live transport, whereas a [ConnEvent.TransportLost] re-`open()`s
 * the transport first (the driver re-enters [Phase.JoinTransport] via `ensureConnected`, since the reducer
 * cannot store the endpoint it would need to rebuild that phase).
 *
 * `cur` is deliberately not consulted: the driver only emits events valid for the current state, so each
 * event fully determines the next state. Keeping it event-dominant is what makes the table trivial to test.
 */
internal fun reduce(cur: ConnState, ev: ConnEvent): ConnState = when (ev) {
    ConnEvent.StartRequested -> ConnState.Connecting(Phase.Discovering)
    is ConnEvent.TransportOpened -> ConnState.Connecting(Phase.Handshake)
    is ConnEvent.LinkEstablished -> ConnState.Connected(ev.endpoint)
    is ConnEvent.LinkDropped -> ConnState.Retrying(ev.reason, RETRY_BASE_MS)
    is ConnEvent.TransportLost -> ConnState.Retrying(ev.reason, RETRY_BASE_MS)
    is ConnEvent.Failed ->
        if (ev.recoverable) ConnState.Retrying(ev.reason, RETRY_BASE_MS)
        else ConnState.Error(ev.reason, recoverable = false)
    ConnEvent.Disconnected -> ConnState.Idle
}

/**
 * Owns one bike connection's lifecycle (design doc section 6): a coroutine walks Layer 1 (`transport.open`)
 * then Layer 2 (`links` in order until one `establish`es), publishing coarse progress on [state]; the same
 * coroutine's supervising loop consumes drop/loss signals and applies [reduce]'s recovery with capped
 * exponential backoff.
 *
 * Concurrency model (adversarial-review fixes F1-F4): **the coroutine owns the entire lifecycle.** All
 * teardown and terminal-state emission live in one `finally` inside the driver, so they run exactly once on
 * normal completion, on cancellation (`disconnect`), and on the fatal-[ConnState.Error] return — never on a
 * caller thread. [disconnect] therefore only cancels the job; it touches no shared state. The mutable
 * fields [session]/[endpoint] are confined to the driver coroutine (kotlinx dispatch gives the needed
 * happens-before across suspensions, so they need no locking); only [runJob], read from caller threads, is
 * `@Volatile`, and [connect]/[disconnect] serialize on [lifecycleLock] with a cancel-then-join handoff so a
 * new run cannot start while the previous driver is still unwinding (F3).
 *
 * Constructed by [BikeConnectionFactory.create]. As of Task 6 the transport/link wrappers are real
 * (SoftAP/P2P over `BikeWifi`/`BikeWifiP2p`, EasyConn/Yunmo over `EasyConnProber`/`YunmoLink`), so the
 * happy path is device-runnable; the state machine and the reducer it drives are complete and tested here.
 *
 * @param maxAttempts consecutive-failure cap before fatal (injected so tests can force a fast fatal).
 * @param flapWindowNs / [flapMaxFailures] the F6 flap cap (injected for tests).
 * @param onConnected Garage persistence hook (design doc §5, Task 8): invoked with the live [spec] —
 *   carrying a refreshed [ConnectionSpec.lastEndpointHint] — every time the driver (re)reaches
 *   [ConnState.Connected], whether a fresh connect or a link-only re-establish after a drop. A plain
 *   callback (not a [BikeMemory][dev.zanderp.opencfmoto.BikeMemory] + [Context] pair) so this class stays
 *   free of the outer app's persistence singleton and this behavior stays unit-testable without Android
 *   (default no-op keeps every existing test call site compiling unchanged).
 *   [BikeConnectionFactory.create] wires the real `memory.saveSpec(ctx, _)`.
 */
class DefaultBikeConnection(
    private val transport: BikeTransport,
    private val links: List<BikeLink>,
    private val spec: ConnectionSpec,
    private val io: PlatformIO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val flapWindowNs: Long = FLAP_WINDOW_NS,
    private val flapMaxFailures: Int = FLAP_MAX_FAILURES,
    private val onConnected: (ConnectionSpec) -> Unit = {},
) : BikeConnection {

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    override val state: StateFlow<ConnState> = _state.asStateFlow()

    /**
     * Problems reported by the (Task 6) transport/link watchdogs. The supervising loop consumes these and
     * applies the section-6 recovery: link-only re-establish on [ConnEvent.LinkDropped] (transport stays
     * up), full transport re-open on [ConnEvent.TransportLost].
     */
    private val events = Channel<ConnEvent>(Channel.UNLIMITED)

    /** Serializes [connect]/[disconnect] so the driver handoff (cancel old, start new) is atomic. */
    private val lifecycleLock = Any()

    @Volatile
    private var runJob: Job? = null

    // Confined to the driver coroutine (see class KDoc): never touched from a caller thread.
    private var session: LinkSession? = null

    /** Last good endpoint. Non-null means the transport is up, so a link drop re-establishes Layer 2 only. */
    private var endpoint: BikeEndpoint? = null

    /**
     * Fresh [Network] handed in by a Wi-Fi re-acquire ([onWifiReacquired], from the BikeWifi ConnectivityThread —
     * NOT the main looper); consumed EXACTLY ONCE by the driver coroutine on the next [ConnEvent.LinkDropped] via
     * [AtomicReference.getAndSet] so a second re-acquire landing between the read and the clear can't drop a fresh
     * [Network] (review M1). A stale endpoint Network is fatal to `EasyConnProber.start` (null link props →
     * abort), so the newest fresh one MUST reach the prober; last-write-wins on the set is exactly what we want.
     */
    private val reacquiredNetwork = AtomicReference<Network?>(null)

    override fun connect() {
        synchronized(lifecycleLock) {
            // Idempotency guard: a healthy/starting driver is left alone, so a redundant or auto-connect
            // re-trigger is a no-op, not a cancel+relaunch that would tear down a live transport/LinkSession
            // and bounce the state through Idle. A cancelled or finished job reports isActive == false at
            // once, so disconnect->connect and post-fatal reconnect still proceed — this does NOT reintroduce
            // F3 (which was about disconnect nulling runJob, now fixed by the cancel-then-join handoff below).
            if (runJob?.isActive == true) return

            // section-6 auto-connect gate: the phone-hotspot path needs a foreground Activity; headless defers.
            if (spec.mode == TransportKind.PHONE_HOTSPOT && io.activityOrNull() == null) {
                _state.value = ConnState.Error("needs foreground", recoverable = false)
                return
            }

            val previous = runJob
            previous?.cancel() // stop any prior (finished/cancelled) driver eagerly...
            runJob = scope.launch {
                previous?.join() // ...and wait for its finally (teardown + terminal state) before starting.
                supervise()
            }
        }
    }

    override fun disconnect() {
        // Only cancel — the driver's finally performs teardown and drives the state to Idle (F1).
        synchronized(lifecycleLock) { runJob?.cancel() }
    }

    /**
     * Wi-Fi re-acquire hinge (design §3), called from the shared `BikeWifi` callback thread via
     * `BikeConnectionHolder`/`BikeLink.onWifiReacquired`: stash the fresh [network] and enqueue a
     * [ConnEvent.LinkDropped] so the driver re-establishes the LINK on it (transport kept up). Safe with no
     * live driver — the event lands in the UNLIMITED channel and is drained on the next `supervise()` (or GC'd
     * with it); the `@Volatile` write is last-write-wins. The raised SoftAP/P2P caps keep the driver — and
     * thus this event's receiver — alive across a long outage so a later re-acquire always recovers (design §2).
     */
    override fun onWifiReacquired(network: Network?) {
        reacquiredNetwork.set(network)
        events.trySend(ConnEvent.LinkDropped("wifi re-acquired"))
    }

    /**
     * Cancel the driver and BLOCK (bounded) until its `finally` — teardownInternal → `transport.close()` →
     * `BikeWifi.leave()` — has run, so a connect that follows (mode switch) is strictly ordered after the Wi-Fi
     * release (review I1). Called from the main thread at teardown; teardown does no network I/O so it returns
     * in ms, and the timeout caps a wedged driver so we never hang the caller. The job is captured+cancelled
     * under [lifecycleLock]; the join runs OUTSIDE the lock (the driver's own `connect`/`disconnect` also take
     * it, so holding it during join could deadlock).
     */
    override fun disconnectAndAwaitTeardown() {
        val job = synchronized(lifecycleLock) { runJob?.also { it.cancel() } } ?: return
        runCatching { runBlocking { withTimeoutOrNull(TEARDOWN_AWAIT_MS) { job.join() } } }
    }

    /**
     * The lifecycle coroutine. One `try`/`finally` owns cleanup: whether the loop exits by cancellation
     * (disconnect) or by the fatal-error `return`, the `finally` tears everything down once and publishes the
     * terminal state ([ConnState.Idle] for a disconnect via the pure `Disconnected` transition, or the
     * captured [ConnState.Error]). Teardown is all non-suspending, so it completes even while cancelling.
     */
    private suspend fun supervise() {
        var attempt = 0 // consecutive failures -> backoff size; reset on a clean Connected
        var windowStartNs = System.nanoTime()
        var windowFailures = 0 // failures within the current flap window (NOT reset by a brief success)
        var fatal: ConnState? = null
        try {
            dispatch(ConnEvent.StartRequested) // Idle -> Connecting(Discovering)
            while (true) {
                val problem: ConnEvent = try {
                    ensureConnected() // opens transport if needed, then establishes a link -> Connected
                    attempt = 0
                    events.receive() // suspends until a watchdog reports trouble
                } catch (c: CancellationException) {
                    throw c // disconnect() cancelled us — unwind into the finally
                } catch (t: Throwable) {
                    ConnEvent.LinkDropped(t.message ?: "connect failed") // open/establish threw -> treat as drop
                }

                when (problem) {
                    is ConnEvent.LinkDropped -> {
                        // Driver-coroutine-only mutation (respects the F1-F4 endpoint confinement): if a Wi-Fi
                        // re-acquire handed us a fresh Network, swap it into the endpoint so establishAnyLink
                        // re-runs the prober on the LIVE net — a stale endpoint Network aborts
                        // EasyConnProber.start (null link props → "could not resolve our IPv4"), design §3.
                        // getAndSet consumes it atomically so a concurrent re-acquire can't drop a fresh net (M1).
                        reacquiredNetwork.getAndSet(null)?.let { fresh -> endpoint = endpoint?.copy(network = fresh) }
                        closeSession() // keep the transport (endpoint stays non-null) — re-establish the link only
                    }
                    is ConnEvent.TransportLost -> {
                        closeSession()
                        closeTransport() // endpoint -> null, so the next pass re-opens the transport first
                    }
                    else -> continue // progress events are driven inline, not fed through the channel
                }

                attempt++
                val now = System.nanoTime()
                if (now - windowStartNs > flapWindowNs) {
                    windowStartNs = now
                    windowFailures = 0
                }
                windowFailures++

                val exhausted = attempt >= maxAttempts // cannot connect at all
                val unstable = windowFailures >= flapMaxFailures // connects but flaps (F6)
                if (exhausted || unstable) {
                    val why =
                        if (unstable) "connection unstable ($windowFailures drops within ${flapWindowNs / 1_000_000_000}s)"
                        else "connection failed after $attempt attempts"
                    fatal = ConnState.Error(why, recoverable = false)
                    return // -> finally: teardown + publish Error
                }

                retryThen(problem, attempt) // -> Retrying(reason, backoff); wait
            }
        } finally {
            teardownInternal()
            _state.value = fatal ?: reduce(_state.value, ConnEvent.Disconnected) // Error, else Idle
        }
    }

    /** Bring the connection up to [ConnState.Connected], reusing a live transport when there is one. */
    private suspend fun ensureConnected() {
        val ep = endpoint ?: run {
            _state.value = ConnState.Connecting(Phase.JoinTransport)
            val opened = transport.open(requireContext(), spec, io)
            endpoint = opened
            dispatch(ConnEvent.TransportOpened(opened)) // -> Connecting(Handshake)
            opened
        }
        if (session == null) {
            _state.value = ConnState.Connecting(Phase.Handshake)
            session = establishAnyLink(ep)
            dispatch(ConnEvent.LinkEstablished(ep)) // -> Connected(ep)
            // Garage persistence (design doc §5, Task 8): save the known-good spec on every fresh
            // Connected, including a link-only re-establish (ep is still the live, still-good endpoint).
            runCatching { onConnected(spec.copy(lastEndpointHint = ep.host.hostAddress)) }
                .onFailure { io.log("BikeConnection", "onConnected callback failed: ${it.message}") }
        }
    }

    /** Try each link in order (EasyConn, then Yunmo); the first to establish wins. */
    private suspend fun establishAnyLink(ep: BikeEndpoint): LinkSession {
        var last: Throwable? = null
        for (link in links) {
            try {
                return link.establish(requireContext(), ep, spec, io)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                io.log("BikeConnection", "link ${link::class.simpleName} failed: ${t.message}")
            }
        }
        throw last ?: IllegalStateException("no link could be established")
    }

    /** Canonical transition via [reduce], overlaid with the real capped-exponential backoff, then wait. */
    private suspend fun retryThen(ev: ConnEvent, attempt: Int) {
        val wait = backoffMs(attempt)
        val next = reduce(_state.value, ev)
        _state.value = if (next is ConnState.Retrying) next.copy(nextInMs = wait) else next
        delay(wait)
    }

    // Task 6 watchdogs call these to drive recovery through the supervising loop without re-entering it.
    // Thread-safe: they only enqueue on the channel; they never touch session/endpoint.
    internal fun signalLinkDropped(reason: String) {
        events.trySend(ConnEvent.LinkDropped(reason))
    }

    internal fun signalTransportLost(reason: String) {
        events.trySend(ConnEvent.TransportLost(reason))
    }

    /** All resource cleanup, driver-coroutine only (called solely from [supervise]'s `finally`). */
    private fun teardownInternal() {
        closeSession()
        runCatching { links.forEach { it.stop() } }
        closeTransport()
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun closeTransport() {
        runCatching { transport.close() }
        endpoint = null
    }

    private fun dispatch(ev: ConnEvent) {
        _state.value = reduce(_state.value, ev)
    }

    /**
     * Context seam. Headless SoftAP/P2P auto-connect (design section 6) runs with
     * `activityOrNull() == null`, so the app Context comes from [PlatformIO.appContext] (wired in Task 6),
     * NOT from the optional Activity. [PlatformIO.activityOrNull] stays reserved for the interactive
     * phone-hotspot path (gated in [connect]); the transports/links only ever need this app Context.
     */
    private fun requireContext(): Context = io.appContext
}
