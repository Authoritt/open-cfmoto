package dev.zanderp.opencfmoto.connection.factory

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * disconnect cancellation, and that `state` settles on the right terminal value.
 *
 * They also pin the TWO FAILURE CLASSES (owner's design): a connector that never establishes fails on the
 * first attempt (terminal Error carrying the re-scan text, no Retrying, no backoff), while a link that WAS
 * alive and got lost reconnects. The tests that need a live driver park it in `Connecting` via
 * [SlowTransport] — the initial-connect path has no backoff `Retrying` to park in any more, by design.
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

    /**
     * A transport whose `open()` never completes — the driver parks in `Connecting(JoinTransport)`. The tests
     * that need a LIVE driver use this now: with the initial-connect fast-fail there is no Retrying state to
     * park in on the first attempt any more (that is the whole point of the split), so "active" must be
     * observed as Connecting instead.
     */
    private class SlowTransport : BikeTransport {
        @Volatile var opened = 0
        @Volatile var closed = 0
        override suspend fun open(ctx: Context, spec: ConnectionSpec, io: PlatformIO): BikeEndpoint {
            opened++
            delay(60_000) // cancellable; no test waits this long
            throw IllegalStateException("unreachable in tests")
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

    /** Establishes [successes] times, then always fails — drives the post-Connected reconnect cap. */
    private class FlakyLink(private val successes: Int) : BikeLink {
        @Volatile var attempts = 0
        override suspend fun establish(ctx: Context, endpoint: BikeEndpoint, spec: ConnectionSpec, io: PlatformIO): LinkSession {
            attempts++
            if (attempts > successes) throw IllegalStateException("bike link gone (network)")
            return object : LinkSession { override fun close() {} }
        }
        override fun stop() {}
    }

    /** [ContextIo] + a log recorder: the technical give-up detail must survive in the LOG, not in the state. */
    private class RecordingIo : PlatformIO {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        override val appContext: Context = ContextWrapper(null) // inert token; no methods are ever called
        override val log: (String, String) -> Unit = { _, msg -> lines += msg }
        override fun activityOrNull(): Activity? = null
        override fun videoSink(): VideoSink = throw IllegalStateException("no video sink in unit test")
    }

    private object ContextIo : PlatformIO {
        override val appContext: Context = ContextWrapper(null) // inert token; no methods are ever called on it
        override val log: (String, String) -> Unit = { _, _ -> }
        override fun activityOrNull(): Activity? = null
        override fun videoSink(): VideoSink = throw IllegalStateException("no video sink in unit test")
    }

    private fun softApSpec() = ConnectionSpec(bikeId = "test-bike", mode = TransportKind.SOFT_AP)

    /** Stand-ins for the localized texts `BikeConnectionFactory` injects (`ovk_conn_*`). */
    private val RESCAN = "Couldn't connect with CFMoto Wi-Fi. Scan the bike's QR again to update the garage."
    private val LOST = "Lost the connection to the bike. Tap Connect to try again."
    private val NEEDS_APP = "This bike only connects with the app open. Tap Connect."

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
                // The reconnect cap is irrelevant on this path — an initial-connect failure is terminal on
                // the first attempt regardless — but keep it at 1 so the test still documents "no backoff".
                maxReconnectAttempts = 1,
            )

            conn.connect()
            val terminal = withTimeout(5_000) { conn.state.first { it is ConnState.Error } }

            assertEquals(false, (terminal as ConnState.Error).recoverable)
            assertTrue("transport.close() must run in the fatal-error finally (no FGS leak)", transport.closed >= 1)
            assertTrue("link.stop() must run in the fatal-error finally", link.stopped >= 1)
        }
    }

    // ---- THE TWO FAILURE CLASSES (owner's design) ----
    // The connector is chosen by the rider at scan and saved in the Garage. If it never establishes, the
    // Garage entry is wrong: fail on the FIRST attempt and tell the rider to re-scan. Only a link that WAS
    // alive and got lost is worth reconnecting.

    @Test
    fun `an initial connect failure is ONE attempt and a terminal Error — no Retrying, no backoff`() {
        runBlocking {
            val transport = RecordingTransport() // counts opens, then throws
            val link = RecordingLink()
            val seen = java.util.concurrent.CopyOnWriteArrayList<ConnState>()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = ContextIo, // so transport.open() actually RUNS and can be counted
                // default reconnect cap (3): under the old policy this run would have retried with
                // 1+2+4 s of backoff before turning fatal — here it must not retry at all.
                initialFailureReason = RESCAN,
            )
            val watcher = launch { conn.state.collect { seen += it } }

            conn.connect()
            val startedAt = System.currentTimeMillis()
            val terminal = withTimeout(5_000) { conn.state.first { it is ConnState.Error } } as ConnState.Error
            val elapsed = System.currentTimeMillis() - startedAt
            watcher.cancel()

            assertEquals("the rider-facing re-scan text must be the terminal reason", RESCAN, terminal.reason)
            assertEquals(false, terminal.recoverable)
            assertEquals("exactly ONE attempt — a wrong connector is not retried", 1, transport.opened)
            assertTrue("no Retrying may be published on the initial-connect path", seen.none { it is ConnState.Retrying })
            assertTrue("must fail fast, with no backoff wait (took ${elapsed}ms)", elapsed < RETRY_BASE_MS)
            assertTrue("teardown still runs on the fast-fail path", transport.closed >= 1)
            assertTrue("link.stop() still runs on the fast-fail path", link.stopped >= 1)
        }
    }

    @Test
    fun `a background connect on an app-only connector is refused with rider-facing text, never opened`() {
        // Third terminal case: PHONE_HOTSPOT (Rieju BLE handoff) and TETHER (manual hotspot) need the app on
        // screen. CfmotoConnect refuses this earlier with its own localized message, so this driver gate is a
        // BACKSTOP — and a backstop that is "never reached" is exactly the one that surfaces on someone
        // else's phone, on the least-proven connector. It must not leak an internal English string either.
        for (mode in listOf(TransportKind.PHONE_HOTSPOT, TransportKind.TETHER)) {
            val transport = RecordingTransport()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(RecordingLink()),
                spec = ConnectionSpec(bikeId = "test-bike", mode = mode),
                io = ContextIo, // activityOrNull() == null -> the app is not on screen
                needsForegroundReason = NEEDS_APP,
            )

            conn.connect()

            val s = conn.state.value
            assertTrue("$mode must be refused, not attempted: $s", s is ConnState.Error)
            assertEquals(NEEDS_APP, (s as ConnState.Error).reason)
            assertEquals("the connector must never be opened in the background", 0, transport.opened)
        }
    }

    @Test
    fun `a drop AFTER Connected goes Retrying (reconnect is only for a link that was alive)`() {
        runBlocking {
            val transport = OpeningTransport(softApEndpoint())
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(EstablishingLink()),
                spec = softApSpec(),
                io = ContextIo,
                initialFailureReason = RESCAN,
            )

            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Connected } }

            conn.signalLinkDropped("bike switched off at the fuel stop")
            val retrying = withTimeout(5_000) { conn.state.first { it is ConnState.Retrying } } as ConnState.Retrying

            assertEquals("bike switched off at the fuel stop", retrying.reason)
            assertEquals("the gauge shows 'Reconectando 1/3' — the attempt in flight", 1, retrying.attempt)
            assertEquals(RECONNECT_MAX_ATTEMPTS, retrying.maxAttempts)
            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }
        }
    }

    @Test
    fun `a drop after Connected retries the SAME connector at most 3 times, then goes terminal`() {
        runBlocking {
            val transport = OpeningTransport(softApEndpoint())
            val link = FlakyLink(successes = 1) // connects once, then every re-establish fails
            val counters = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val recording = RecordingIo()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = recording,
                initialFailureReason = RESCAN,
                lostLinkReason = LOST,
            )
            val watcher = launch { conn.state.collect { if (it is ConnState.Retrying) counters += it.attempt } }

            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Connected } }
            conn.signalLinkDropped("network dropped mid-ride")

            // 1 s + 2 s + 4 s of backoff between the three attempts.
            val terminal = withTimeout(30_000) { conn.state.first { it is ConnState.Error } } as ConnState.Error
            watcher.cancel()

            assertEquals("1 initial establish + exactly 3 reconnect attempts", 4, link.attempts)
            assertEquals("the rider sees 1/3, 2/3, 3/3", listOf(1, 2, 3), counters.distinct())
            assertEquals("the SAME connector throughout — a retry never re-opens another transport", 1, transport.opened)
            // Rider-facing, and NOT the "your connector is wrong" text — the two classes must never be
            // confused: here the Garage entry is fine, the network went away.
            assertEquals(LOST, terminal.reason)
            assertTrue("the give-up text must not be the wrong-connector one", terminal.reason != RESCAN)
            // …and the technical account is still available to support, in the log.
            assertTrue(
                "the attempt count must survive in the log: ${recording.lines}",
                recording.lines.any { it.startsWith("giving up:") && it.contains("3") },
            )
        }
    }

    @Test
    fun `the reconnect counter restarts at 1 after a successful re-establish`() {
        runBlocking {
            val transport = OpeningTransport(softApEndpoint())
            val link = EstablishingLink() // every re-establish succeeds
            val seen = java.util.concurrent.CopyOnWriteArrayList<ConnState.Retrying>()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = ContextIo,
            )
            val watcher = launch { conn.state.collect { if (it is ConnState.Retrying) seen += it } }

            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Connected } }

            conn.signalLinkDropped("first drop")
            withTimeout(10_000) { while (link.established < 2) delay(20) } // recovered
            withTimeout(10_000) { conn.state.first { it is ConnState.Connected } }

            conn.signalLinkDropped("a later, unrelated drop")
            withTimeout(10_000) { while (seen.size < 2) delay(20) }
            watcher.cancel()

            assertEquals("a later drop must start over at 1/3, not continue at 2/3", listOf(1, 1), seen.map { it.attempt })
            assertEquals(RECONNECT_MAX_ATTEMPTS, seen.last().maxAttempts)
            conn.disconnect()
        }
    }

    @Test
    fun `disconnect during connect tears down and ends Idle (F1, F3)`() {
        runBlocking {
            val transport = SlowTransport() // parks the driver in Connecting (no Retrying to park in now)
            val link = RecordingLink()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = ContextIo,
            )

            conn.connect()
            // The driver is mid-`transport.open()`; disconnect while it waits there.
            withTimeout(5_000) { conn.state.first { it is ConnState.Connecting } }
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
            val transport = SlowTransport()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(RecordingLink()),
                spec = softApSpec(),
                io = ContextIo,
            )

            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Connecting } }
            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }

            // A second connect must spin up a new driver cleanly (old job's finally has run; no zombie loop).
            conn.connect()
            withTimeout(5_000) { while (transport.opened < 2) delay(20) }
            assertTrue("the fresh driver must re-open the transport", transport.opened >= 2)
            conn.disconnect()
            withTimeout(5_000) { conn.state.first { it == ConnState.Idle } }
        }
    }

    @Test
    fun `connect while a driver is active is a no-op (idempotency guard, no bounce)`() {
        runBlocking {
            val transport = SlowTransport()
            val link = RecordingLink()
            val conn = DefaultBikeConnection(
                transport = transport,
                links = listOf(link),
                spec = softApSpec(),
                io = ContextIo,
            )

            // Park the driver in an ACTIVE state: mid-`transport.open()`, i.e. Connecting(JoinTransport).
            // (Before the fast-fail split this test parked in the backoff Retrying, which the initial-connect
            // path no longer has.) The guard keys on runJob.isActive — true for Connecting/Connected/Retrying
            // alike — so this proves the same invariant: a redundant connect() while active must NOT bounce.
            conn.connect()
            withTimeout(5_000) { conn.state.first { it is ConnState.Connecting } }

            // Redundant connect(): with the guard it returns at once; without it, it would cancel the live
            // job, whose finally runs teardownInternal() (transport.close + link.stop) and flashes Idle. The
            // teardown counters are the reliable, monotonic bounce detector.
            conn.connect()
            delay(300) // give any (erroneous) cancel+finally time to tear down before we assert it did not

            assertEquals("redundant connect must not tear down the transport (no bounce)", 0, transport.closed)
            assertEquals("redundant connect must not stop the links (no bounce)", 0, link.stopped)
            assertEquals("redundant connect must not re-open the transport", 1, transport.opened)
            assertTrue(
                "the driver must still be actively connecting, not bounced to a terminal state",
                conn.state.value is ConnState.Connecting,
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
