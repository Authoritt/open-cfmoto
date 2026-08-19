package dev.zanderp.opencfmoto.connection.factory

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driver-lifecycle tests for [DefaultBikeConnection] (adversarial-review fixes F1-F4). The concurrent
 * coroutine driver can't be made perfectly deterministic without a test dispatcher (not on the classpath),
 * so these assert the observable contract on both teardown paths: the recording fakes prove `transport.close()`
 * and `link.stop()` run in the driver's `finally` whether it exits by the fatal-error return OR by a
 * disconnect cancellation, and that `state` settles on the right terminal value. `maxAttempts` is injected so
 * the fatal path is reached in one failure with no real backoff wait; the disconnect path is exercised while
 * the driver sits in its (default) backoff. Neither needs an Android Context — `activityOrNull() == null`
 * makes the first attempt fail via `requireContext()`, which is exactly the failure we want to observe.
 */
class DefaultBikeConnectionTest {

    private class RecordingTransport : BikeTransport {
        @Volatile var opened = 0
        @Volatile var closed = 0
        override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint {
            opened++
            throw IllegalStateException("transport open fails in this fake")
        }
        override fun close() { closed++ }
    }

    private class RecordingLink : BikeLink {
        @Volatile var stopped = 0
        override suspend fun establish(
            ctx: Context,
            endpoint: BikeEndpoint,
            spec: ConnectionSpec,
            io: PlatformIO,
        ): LinkSession = throw IllegalStateException("link establish not reached")
        override fun stop() { stopped++ }
    }

    private object NoActivityIo : PlatformIO {
        // Throwing getter (not a stored value): a plain JVM unit test has no real Context, and this
        // preserves the exact failure these tests rely on — the first ensureConnected() throws at
        // requireContext() -> io.appContext (was: activityOrNull()==null), i.e. before the fake
        // transport.open() even runs — so the teardown/terminal-state assertions are unchanged.
        override val appContext: Context get() = throw IllegalStateException("no app Context in unit test")
        override val log: (String, String) -> Unit = { _, _ -> }
        override fun activityOrNull(): Activity? = null
        override fun videoSink(): VideoSink = throw IllegalStateException("no video sink in unit test")
    }

    // --- Success fakes for the re-establish MECHANISM test (review I2). Reaching Connected needs a Context
    //     argument for transport.open/link.establish; the fakes never call a method on it, and
    //     testOptions.unitTests.isReturnDefaultValues (build.gradle.kts) lets ContextWrapper(null) be an inert
    //     pass-through instead of a "Stub!" throw — no Robolectric/mocking on the classpath. ---
    private class OpeningTransport(private val ep: BikeEndpoint) : BikeTransport {
        @Volatile var opened = 0
        @Volatile var closed = 0
        override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint { opened++; return ep }
        override fun close() { closed++ }
    }

    private class EstablishingLink : BikeLink {
        @Volatile var established = 0
        @Volatile var lastEndpoint: BikeEndpoint? = null
        override suspend fun establish(ctx: Context, endpoint: BikeEndpoint, spec: ConnectionSpec, io: PlatformIO): LinkSession {
            established++
            lastEndpoint = endpoint
            return object : LinkSession { override fun close() {} }
        }
        override fun stop() {}
    }

    private object ContextIo : PlatformIO {
        override val appContext: Context = ContextWrapper(null) // inert token; no methods are ever called on it
        override val log: (String, String) -> Unit = { _, _ -> }
        override fun activityOrNull(): Activity? = null
        override fun videoSink(): VideoSink = throw IllegalStateException("no video sink in unit test")
    }

    private fun softApSpec() = ConnectionSpec(bikeId = "test-bike", mode = TransportKind.SOFT_AP)

    private fun softApEndpoint() = BikeEndpoint(
        network = null,
        host = InetAddress.getByName("192.168.49.1") as Inet4Address,
        bindIp = null,
        kind = TransportKind.SOFT_AP,
        phoneIsServer = false,
    )

    // Block bodies (not `= runBlocking { ... }`): JUnit4 requires @Test methods to return void, and some of
    // these blocks end in a non-Unit expression (e.g. `first { ... }` returns a ConnState).
    @Test
    fun `fatal error path tears down transport and links and ends in Error (F1, F4)`() {
        runBlocking {
            val transport = RecordingTransport()
            val link = RecordingLink()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = NoActivityIo,
                maxAttempts = 1, // one failure -> fatal immediately, no backoff wait
            )

            conn.connect()
            val terminal = withTimeout(5_000) { conn.state.first { it is ConnState.Error } }

            assertEquals(false, (terminal as ConnState.Error).recoverable)
            assertTrue("transport.close() must run in the fatal-error finally (no FGS leak)", transport.closed >= 1)
            assertTrue("link.stop() must run in the fatal-error finally", link.stopped >= 1)
        }
    }

    @Test
    fun `disconnect during connect tears down and ends Idle (F1, F3)`() {
        runBlocking {
            val transport = RecordingTransport()
            val link = RecordingLink()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = NoActivityIo, // default maxAttempts -> stays retrying, does not fatal quickly
            )

            conn.connect()
            // First attempt fails -> driver enters a backoff Retrying; disconnect while it waits there.
            withTimeout(5_000) { conn.state.first { it is ConnState.Retrying } }
            conn.disconnect()

            val terminal = withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }
            assertEquals(ConnState.Idle, terminal)
            assertTrue("transport.close() must run on the disconnect finally", transport.closed >= 1)
            assertTrue("link.stop() must run on the disconnect finally", link.stopped >= 1)
        }
    }

    @Test
    fun `reconnect after disconnect starts a fresh driver (F3)`() {
        runBlocking {
            val conn = DefaultBikeConnection(
                transport = RecordingTransport(),
                links = listOf(RecordingLink()),
                spec = softApSpec(),
                io = NoActivityIo,
            )

            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Retrying } }
            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }

            // A second connect must spin up a new driver cleanly (old job's finally has run; no zombie loop).
            conn.connect()
            val retryingAgain = withTimeout(5_000) { conn.state.first { it is ConnState.Retrying } }
            assertTrue(retryingAgain is ConnState.Retrying)
            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }
        }
    }

    @Test
    fun `connect while a driver is active is a no-op (idempotency guard, no bounce)`() {
        runBlocking {
            val transport = RecordingTransport()
            val link = RecordingLink()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = NoActivityIo, // default maxAttempts -> the driver stays alive, retrying
            )

            // The ideal "healthy active" state is Connected, but reaching it needs transport.open() to run,
            // which needs a real Context from requireContext() -> a real Activity, which cannot be
            // instantiated in a plain JVM unit test (android.jar stubs throw; no Robolectric on the
            // classpath). So we park the driver in its (equally active) backoff Retrying instead. The guard
            // keys on runJob.isActive, true for Connecting/Connected/Retrying alike, so this proves the same
            // invariant: a redundant connect() while active must NOT bounce.
            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Retrying } }

            // Redundant connect(): with the guard it returns at once; without it, it would cancel the live
            // job, whose finally runs teardownInternal() (transport.close + link.stop) and flashes Idle. The
            // teardown counters are the reliable, monotonic bounce detector.
            conn.connect()
            delay(300) // give any (erroneous) cancel+finally time to tear down before we assert it did not

            assertEquals("redundant connect must not tear down the transport (no bounce)", 0, transport.closed)
            assertEquals("redundant connect must not stop the links (no bounce)", 0, link.stopped)
            assertTrue(
                "the driver must still be actively (re)connecting, not bounced to a terminal state",
                conn.state.value is ConnState.Retrying || conn.state.value is ConnState.Connecting,
            )

            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }
        }
    }

    @Test
    fun `onWifiReacquired before connect enqueues safely with no receiver (no throw, stays Idle)`() {
        // The re-acquire hinge (flip-work-design.md §3) sends a LinkDropped into the UNLIMITED events channel.
        // With no driver running there is no receiver — this must NOT throw (trySend on an unbounded channel
        // always succeeds) and the connection must stay Idle. This is exactly the "no receiver" safety the
        // raised SoftAP/P2P caps protect at runtime by keeping the driver (and its receiver) alive across an
        // outage so a later re-acquire always recovers. No Context needed: connect() is never called.
        val conn = DefaultBikeConnection(
            transport = RecordingTransport(),
            links = listOf(RecordingLink()),
            spec = softApSpec(),
            io = NoActivityIo,
        )
        conn.onWifiReacquired(null)
        conn.onWifiReacquired(null)
        assertEquals(ConnState.Idle, conn.state.value)
    }

    @Test
    fun `onWifiReacquired re-establishes the LINK only (no transport re-open)`() {
        // THE mechanism the feature hinges on (review I2): a Wi-Fi re-acquire must re-establish Layer 2 on the
        // live network WITHOUT re-opening the transport (BikeWifi stays up). Drive to Connected, fire a
        // re-acquire, and prove transport.open ran exactly once while link.establish ran again.
        runBlocking {
            val transport = OpeningTransport(softApEndpoint())
            val link = EstablishingLink()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = ContextIo,
            )
            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Connected } }
            assertEquals("transport opens once", 1, transport.opened)
            assertEquals("link establishes once", 1, link.established)

            conn.onWifiReacquired(null) // Wi-Fi came back → LinkDropped → re-establish the link only
            withTimeout(5_000) { while (link.established < 2) delay(20) }

            assertEquals("transport must NOT be re-opened on a Wi-Fi re-acquire", 1, transport.opened)
            assertTrue("link must be re-established (link-only recovery)", link.established >= 2)
            assertEquals("transport stays up (not closed) during a link-only re-establish", 0, transport.closed)

            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }
        }
    }
}
