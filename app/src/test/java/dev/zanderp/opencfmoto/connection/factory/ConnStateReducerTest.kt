package dev.zanderp.opencfmoto.connection.factory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

/**
 * Task 5: the pure [reduce] transition table (design doc 2026-08-18 section 6). No Android, no coroutines
 * — just current-state + event -> next-state. Clean assertions on the phase (the brief's sample had a
 * `.let { it }.let { it }` typo).
 */
class ConnStateReducerTest {

    private val ep = BikeEndpoint(
        network = null,
        host = Inet4Address.getByName("192.168.0.1") as Inet4Address,
        bindIp = null,
        kind = TransportKind.SOFT_AP,
        phoneIsServer = false,
    )

    // ---- CLASS 1: a link that WAS alive and got LOST — this, and only this, reconnects ----

    @Test
    fun `link drop AFTER Connected goes Retrying then re-establishes to Connected`() {
        // Mid-ride link drop with the transport still associated: recover, do not error out.
        val dropped = reduce(ConnState.Connected(ep), ConnEvent.LinkDropped("pxc timeout"), everConnected = true)
        assertTrue("link drop with transport up must retry, not error", dropped is ConnState.Retrying)

        // Re-establishing the LINK only (transport untouched) returns to Connected on the same endpoint.
        val reconnected = reduce(dropped, ConnEvent.LinkEstablished(ep), everConnected = true)
        assertTrue("re-establish must return to Connected", reconnected is ConnState.Connected)
        assertEquals(ep, (reconnected as ConnState.Connected).endpoint)
    }

    @Test
    fun `transport lost AFTER Connected surfaces Retrying during backoff (F5)`() {
        // F5: transport loss backs off as Retrying (with nextInMs), not a nextInMs-less Connecting, so the
        // UI shows a uniform "reconnecting in N" for both drop kinds. Re-opening the transport (re-entering
        // Phase.JoinTransport) happens in the driver AFTER the backoff, not in this pure reducer.
        val s = reduce(ConnState.Connected(ep), ConnEvent.TransportLost("wifi gone"), everConnected = true)
        assertTrue("transport loss must back off as Retrying, like a link drop", s is ConnState.Retrying)
        assertEquals("wifi gone", (s as ConnState.Retrying).reason)
    }

    @Test
    fun `both drop kinds reduce to the same Retrying shape (F5)`() {
        val fromLink = reduce(ConnState.Connected(ep), ConnEvent.LinkDropped("pxc"), everConnected = true)
        val fromTransport = reduce(ConnState.Connected(ep), ConnEvent.TransportLost("wifi"), everConnected = true)
        assertTrue(fromLink is ConnState.Retrying)
        assertTrue(fromTransport is ConnState.Retrying)
    }

    // ---- CLASS 2: it NEVER established — the Garage entry is wrong, so fail fast (no retry, no fallback) ----

    @Test
    fun `a failure BEFORE ever connecting is terminal Error, never Retrying`() {
        // The rider picked the connector at scan and it is saved in the Garage; if it cannot establish, the
        // saved entry is wrong. Retrying (or substituting another connector) would only make connecting slow
        // and hide the real problem — the app must say so and let the rider re-scan.
        val fromLink = reduce(ConnState.Connecting(Phase.JoinTransport), ConnEvent.LinkDropped("join failed"), everConnected = false)
        val fromTransport = reduce(ConnState.Connecting(Phase.JoinTransport), ConnEvent.TransportLost("no group"), everConnected = false)
        assertTrue("initial connect failure must be terminal, not Retrying", fromLink is ConnState.Error)
        assertTrue("initial connect failure must be terminal, not Retrying", fromTransport is ConnState.Error)
        assertEquals("join failed", (fromLink as ConnState.Error).reason)
        assertEquals(false, fromLink.recoverable)
    }

    @Test
    fun `a RECOVERABLE Failed before ever connecting is still terminal`() {
        // "recoverable" only means "the transport thinks it could be retried"; before a first success there
        // is nothing to recover TO — the connector itself is the suspect.
        val s = reduce(ConnState.Connecting(Phase.Handshake), ConnEvent.Failed("timeout", recoverable = true), everConnected = false)
        assertTrue(s is ConnState.Error)
    }

    @Test
    fun `a recoverable Failed AFTER Connected retries`() {
        val s = reduce(ConnState.Connected(ep), ConnEvent.Failed("timeout", recoverable = true), everConnected = true)
        assertTrue(s is ConnState.Retrying)
    }

    @Test
    fun `Retrying carries the attempt counter the gauge shows (defaults to 1 of the 3-attempt cap)`() {
        val s = reduce(ConnState.Connected(ep), ConnEvent.LinkDropped("network"), everConnected = true) as ConnState.Retrying
        assertEquals(1, s.attempt)
        assertEquals(RECONNECT_MAX_ATTEMPTS, s.maxAttempts)
        assertEquals("the owner's cap: máximo 3 reintentos", 3, RECONNECT_MAX_ATTEMPTS)
    }

    @Test
    fun `everConnected defaults to the conservative class (terminal)`() {
        // The default exists so progress-event call sites stay clean; it must never make a failure retry.
        assertTrue(reduce(ConnState.Connecting(Phase.Discovering), ConnEvent.LinkDropped("x")) is ConnState.Error)
    }

    @Test
    fun `disconnect always goes Idle`() {
        assertEquals(ConnState.Idle, reduce(ConnState.Connected(ep), ConnEvent.Disconnected))
    }

    @Test
    fun `forward path walks Discovering then Handshake then Connected`() {
        val discovering = reduce(ConnState.Idle, ConnEvent.StartRequested)
        assertEquals(Phase.Discovering, (discovering as ConnState.Connecting).phase)

        val handshake = reduce(discovering, ConnEvent.TransportOpened(ep))
        assertEquals(Phase.Handshake, (handshake as ConnState.Connecting).phase)

        val connected = reduce(handshake, ConnEvent.LinkEstablished(ep))
        assertEquals(ep, (connected as ConnState.Connected).endpoint)
    }

    @Test
    fun `unrecoverable failure goes to Error`() {
        val s = reduce(ConnState.Connecting(Phase.Handshake), ConnEvent.Failed("no link", recoverable = false))
        assertTrue(s is ConnState.Error)
        assertEquals(false, (s as ConnState.Error).recoverable)
    }
}
