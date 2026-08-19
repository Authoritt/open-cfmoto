package dev.zanderp.opencfmoto.connection

import android.net.Network
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
        override val state: StateFlow<ConnState> = MutableStateFlow(ConnState.Idle)
        override fun connect() { connects++ }
        override fun disconnect() { /* the holder uses disconnectAndAwaitTeardown, not this (review I1) */ }
        override fun onWifiReacquired(network: Network?) { reacquires++ }
        override fun disconnectAndAwaitTeardown() { teardowns++ }
    }

    @Before fun reset() = BikeConnectionHolder.disconnectAndClear()
    @After fun clear() = BikeConnectionHolder.disconnectAndClear()

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
}
