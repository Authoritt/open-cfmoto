// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.content.Context
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

/** Consecutive failures before a recoverable retry is declared fatal ([ConnState.Error]). */
internal const val MAX_ATTEMPTS = 6

/** Capped exponential backoff: base * 2^(attempt-1), clamped to [RETRY_CAP_MS]. `attempt` starts at 1. */
internal fun backoffMs(attempt: Int): Long =
    (RETRY_BASE_MS shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(RETRY_CAP_MS)

/**
 * The single, pure transition of the connection lifecycle (design doc section 6). Given the current
 * [ConnState] and an incoming [ConnEvent], compute the next state — no I/O, no time, no mutation — so the
 * whole reconnect policy is unit-testable without Android or a bike.
 *
 * The recovery rule that matters (the shipped `recoverSocketLinkOnLiveNetwork` fix, formalized):
 *  - [ConnEvent.LinkDropped] (transport still up) -> [ConnState.Retrying]; the driver re-`establish()`s the
 *    LINK only and, on success, feeds [ConnEvent.LinkEstablished] -> back to [ConnState.Connected].
 *  - [ConnEvent.TransportLost] -> [ConnState.Connecting] at [Phase.JoinTransport]; the driver re-`open()`s
 *    the transport (then the link) with backoff.
 *
 * `cur` is deliberately not consulted: the driver only emits events valid for the current state, so each
 * event fully determines the next state. Keeping it event-dominant is what makes the table trivial to test.
 */
internal fun reduce(cur: ConnState, ev: ConnEvent): ConnState = when (ev) {
    ConnEvent.StartRequested -> ConnState.Connecting(Phase.Discovering)
    is ConnEvent.TransportOpened -> ConnState.Connecting(Phase.Handshake)
    is ConnEvent.LinkEstablished -> ConnState.Connected(ev.endpoint)
    is ConnEvent.LinkDropped -> ConnState.Retrying(ev.reason, RETRY_BASE_MS)
    is ConnEvent.TransportLost -> ConnState.Connecting(Phase.JoinTransport, ev.reason)
    is ConnEvent.Failed ->
        if (ev.recoverable) ConnState.Retrying(ev.reason, RETRY_BASE_MS)
        else ConnState.Error(ev.reason, recoverable = false)
    ConnEvent.Disconnected -> ConnState.Idle
}

/**
 * Owns one bike connection's lifecycle (design doc section 6): a coroutine walks Layer 1 (`transport.open`)
 * then Layer 2 (`links` in order until one `establish`es), publishing coarse progress on [state]; a
 * supervising loop consumes drop/loss signals and applies [reduce]'s recovery with capped exponential
 * backoff.
 *
 * Constructed by [BikeConnectionFactory.create]. The transport/link wrappers are the Task-6 shells for now
 * (their `open`/`establish` throw), so the happy path is not yet live on a bike — but the state machine and
 * the reducer it drives are complete and tested here.
 */
class DefaultBikeConnection(
    private val transport: BikeTransport,
    private val links: List<BikeLink>,
    private val spec: ConnectionSpec,
    private val io: PlatformIO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BikeConnection {

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    override val state: StateFlow<ConnState> = _state.asStateFlow()

    /**
     * Problems reported by the (Task 6) transport/link watchdogs. The supervising loop consumes these and
     * applies the section-6 recovery: link-only re-establish on [ConnEvent.LinkDropped] (transport stays
     * up), full transport re-open on [ConnEvent.TransportLost].
     */
    private val events = Channel<ConnEvent>(Channel.UNLIMITED)

    private var runJob: Job? = null
    private var session: LinkSession? = null

    /** Last good endpoint. Non-null means the transport is up, so a link drop re-establishes Layer 2 only. */
    private var endpoint: BikeEndpoint? = null

    override fun connect() {
        if (runJob?.isActive == true) return
        // section-6 auto-connect gate: the phone-hotspot path needs a foreground Activity; headless defers.
        if (spec.mode == TransportKind.PHONE_HOTSPOT && io.activityOrNull() == null) {
            _state.value = ConnState.Error("needs foreground", recoverable = false)
            return
        }
        runJob = scope.launch { supervise() }
    }

    override fun disconnect() {
        runJob?.cancel()
        runJob = null
        teardown()
        _state.value = reduce(_state.value, ConnEvent.Disconnected) // -> Idle
    }

    /** The lifecycle coroutine: (re)connect, then block on the next drop/loss and recover. */
    private suspend fun supervise() {
        var attempt = 0
        dispatch(ConnEvent.StartRequested) // Idle -> Connecting(Discovering)
        while (true) {
            try {
                ensureConnected() // opens transport if needed, then establishes a link
                attempt = 0
                when (val ev = events.receive()) { // suspends until a watchdog reports trouble
                    is ConnEvent.LinkDropped -> {
                        closeSession() // drop the link; KEEP the transport (endpoint stays non-null)
                        retryThen(ev, ++attempt)
                    }
                    is ConnEvent.TransportLost -> {
                        closeSession()
                        closeTransport() // endpoint -> null, so the next pass re-opens the transport first
                        retryThen(ev, ++attempt)
                    }
                    else -> Unit // progress events are driven inline, not fed through the channel
                }
            } catch (c: CancellationException) {
                throw c // disconnect() cancelled us — let it propagate
            } catch (t: Throwable) {
                closeSession() // establish/open failed; keep the transport if it was already up
                if (++attempt >= MAX_ATTEMPTS) {
                    _state.value = ConnState.Error(t.message ?: "connection failed", recoverable = false)
                    return
                }
                retryThen(ConnEvent.LinkDropped(t.message ?: "retrying"), attempt)
            }
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
    internal fun signalLinkDropped(reason: String) {
        events.trySend(ConnEvent.LinkDropped(reason))
    }

    internal fun signalTransportLost(reason: String) {
        events.trySend(ConnEvent.TransportLost(reason))
    }

    private fun teardown() {
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
     * Context seam. [PlatformIO] surfaces only the (optional) Activity, so we reach the app Context through
     * it. TODO(Task 6): headless SoftAP/P2P auto-connect (design section 6) runs with
     * `activityOrNull() == null` and today has no Context source — add an app-Context accessor to
     * [PlatformIO] when wiring the real transports, or thread the `create(ctx, ...)` context through here.
     */
    private fun requireContext(): Context =
        io.activityOrNull()?.applicationContext
            ?: throw IllegalStateException("no Context available (needs foreground)")
}
