package dev.zanderp.opencfmoto.connection

import android.net.Network
import dev.zanderp.opencfmoto.ConnectionState
import dev.zanderp.opencfmoto.Phase
import dev.zanderp.opencfmoto.connection.factory.BikeConnection
import dev.zanderp.opencfmoto.connection.factory.ConnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The process-global [BikeConnectionHolder] (flip-work §1): the single source of truth for "a factory
 * connection is live." Fully JVM-testable — [BikeConnection] is an interface (no Android), so a recording
 * fake covers set/replace/disconnect + the re-acquire forwarding without a Context. Reset around each test
 * because the holder is a singleton shared across the JVM run.
 */
class BikeConnectionHolderTest {

    private class FakeBikeConnection : BikeConnection {
        @Volatile var connects = 0
        @Volatile var teardowns = 0
        @Volatile var reacquires = 0
        private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
        override val state: StateFlow<ConnState> = _state
        /** Drive the driver-side state the way a real `DefaultBikeConnection` would. */
        fun emit(s: ConnState) { _state.value = s }
        override fun connect() { connects++ }
        override fun disconnect() { /* the holder uses disconnectAndAwaitTeardown, not this (review I1) */ }
        override fun onWifiReacquired(network: Network?) { reacquires++ }
        override fun disconnectAndAwaitTeardown() { teardowns++ }
    }

    @Before fun reset() {
        BikeConnectionHolder.disconnectAndClear()
        ConnectionState.set(Phase.IDLE, "")
    }

    @After fun clear() {
        BikeConnectionHolder.disconnectAndClear()
        ConnectionState.set(Phase.IDLE, "")
    }

    /** The mirror collector runs on a background dispatcher — poll briefly instead of sleeping blind. */
    private fun awaitPhase(expected: Phase, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ConnectionState.phase == expected) return
            Thread.sleep(10)
        }
        assertEquals("legacy phase never became " + expected, expected, ConnectionState.phase)
    }

    @Test fun `set stores the connection`() {
        val c = FakeBikeConnection()
        BikeConnectionHolder.set(c)
        assertSame(c, BikeConnectionHolder.connection)
    }

    @Test fun `set replaces a prior connection and tears it down (awaitable)`() {
        val prev = FakeBikeConnection()
        val next = FakeBikeConnection()
        BikeConnectionHolder.set(prev)
        BikeConnectionHolder.set(next)
        assertSame(next, BikeConnectionHolder.connection)
        assertEquals("prior connection is torn down (awaited) on replace", 1, prev.teardowns)
        assertEquals(0, next.teardowns)
    }

    @Test fun `disconnectAndClear tears down the held connection (awaited) and nulls it`() {
        val c = FakeBikeConnection()
        BikeConnectionHolder.set(c)
        BikeConnectionHolder.disconnectAndClear()
        assertNull(BikeConnectionHolder.connection)
        assertEquals(1, c.teardowns)
    }

    @Test fun `disconnectAndClear is a no-op when already null`() {
        BikeConnectionHolder.disconnectAndClear() // must not throw
        assertNull(BikeConnectionHolder.connection)
    }

    @Test fun `onWifiReacquired forwards to the held connection`() {
        val c = FakeBikeConnection()
        BikeConnectionHolder.set(c)
        BikeConnectionHolder.onWifiReacquired(null)
        assertEquals(1, c.reacquires)
    }

    @Test fun `onWifiReacquired is a no-op when no factory connection is live`() {
        BikeConnectionHolder.onWifiReacquired(null) // null holder → inert, no throw (classic path owns reconnect)
        assertNull(BikeConnectionHolder.connection)
    }

    // ---- Terminal factory states must reach the legacy ConnectionState, or auto-connect latches OFF ----
    // joinWifi sets JOINING_WIFI (busy = true) for every connect; on the factory path nothing cleared it,
    // and `autoConnectAllowed` refuses while busy. So a factory connection that ended in Error killed BOTH
    // auto-connect paths until the rider hit Stop by hand.

    @Test fun `a terminal factory Error becomes Phase ERROR with the reason (auto-connect re-arms)`() {
        val c = FakeBikeConnection()
        ConnectionState.set(Phase.JOINING_WIFI, "450NK") // what joinWifi set before handing over
        BikeConnectionHolder.set(c)

        c.emit(ConnState.Error("connection failed after 6 attempts", recoverable = false))

        awaitPhase(Phase.ERROR)
        assertEquals("connection failed after 6 attempts", ConnectionState.detail)
        assertEquals("ERROR must not be busy — that is what re-arms auto-connect", false, ConnectionState.phase.busy)
    }

    @Test fun `a terminal factory Idle becomes a non-busy phase`() {
        val c = FakeBikeConnection()
        // Already connecting when the holder takes it, so the Idle below is unambiguously TERMINAL (a
        // StateFlow conflates, so an emit-then-emit here could otherwise be observed as the last value only).
        c.emit(ConnState.Connecting(dev.zanderp.opencfmoto.connection.factory.Phase.JoinTransport))
        ConnectionState.set(Phase.JOINING_WIFI, "450NK")
        BikeConnectionHolder.set(c)

        c.emit(ConnState.Idle) // driver finished its teardown

        awaitPhase(Phase.STOPPED)
        assertEquals(false, ConnectionState.phase.busy)
    }

    @Test fun `the INITIAL Idle of a fresh connection is not mirrored (it would stamp over JOINING_WIFI)`() {
        val c = FakeBikeConnection() // state starts at Idle, and StateFlow replays it to the collector
        ConnectionState.set(Phase.JOINING_WIFI, "450NK")

        BikeConnectionHolder.set(c)
        Thread.sleep(150) // give the collector time to (not) act

        assertEquals(Phase.JOINING_WIFI, ConnectionState.phase)
    }

    @Test fun `STREAMING is never overwritten — the prober owns the happy path`() {
        val c = FakeBikeConnection()
        c.emit(ConnState.Connecting(dev.zanderp.opencfmoto.connection.factory.Phase.Handshake))
        BikeConnectionHolder.set(c)
        ConnectionState.set(Phase.STREAMING, "450NK")

        c.emit(ConnState.Error("late drop", recoverable = false))
        Thread.sleep(150)

        assertEquals(Phase.STREAMING, ConnectionState.phase)
    }

    @Test fun `a superseded connection's terminal state cannot land on the new one's phase`() {
        val prev = FakeBikeConnection()
        val next = FakeBikeConnection()
        prev.emit(ConnState.Connecting(dev.zanderp.opencfmoto.connection.factory.Phase.JoinTransport))
        BikeConnectionHolder.set(prev)
        BikeConnectionHolder.set(next)
        ConnectionState.set(Phase.JOINING_WIFI, "450NK") // the new connect's phase

        prev.emit(ConnState.Idle) // the OLD driver finishes unwinding, late
        Thread.sleep(150)

        assertEquals(Phase.JOINING_WIFI, ConnectionState.phase)
    }
}
