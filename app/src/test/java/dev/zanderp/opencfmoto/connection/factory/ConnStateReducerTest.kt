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

    @Test
    fun `link drop while transport up goes Retrying then re-establishes to Connected`() {
        // Mid-ride link drop with the transport still associated: recover, do not error out.
        val dropped = reduce(ConnState.Connected(ep), ConnEvent.LinkDropped("pxc timeout"))
        assertTrue("link drop with transport up must retry, not error", dropped is ConnState.Retrying)

        // Re-establishing the LINK only (transport untouched) returns to Connected on the same endpoint.
        val reconnected = reduce(dropped, ConnEvent.LinkEstablished(ep))
        assertTrue("re-establish must return to Connected", reconnected is ConnState.Connected)
        assertEquals(ep, (reconnected as ConnState.Connected).endpoint)
    }

    @Test
    fun `transport lost re-enters at JoinTransport`() {
        val s = reduce(ConnState.Connected(ep), ConnEvent.TransportLost("wifi gone"))
        assertTrue("transport loss must go back to Connecting", s is ConnState.Connecting)
        assertEquals(Phase.JoinTransport, (s as ConnState.Connecting).phase)
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
