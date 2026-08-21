package dev.zanderp.opencfmoto.connection.factory.transport

import org.junit.Test

/**
 * Bare-JVM guards for the tether connector. `open()` is device-only (assist dialog + tether interface +
 * EasyConn scan), so what a unit test CAN pin is the pair of properties that broke before:
 *  1. **constructible off-device** — `BikeConnectionFactory.selectTransport` runs in plain-JVM tests, so no
 *     field initializer may touch an Android type (the `PhoneHotspotTransport` `Handler` lesson: it had to
 *     move to `by lazy`). A field that reached the main Looper here would throw on construction.
 *  2. **`close()` is idempotent and safe before/without an `open()`** — the driver's `finally` calls
 *     `transport.close()` on every teardown path, including one where `open()` never ran (a cancel during
 *     the foreground gate) and again on a second teardown.
 */
class TetherTransportTest {

    @Test fun `constructs on a bare JVM (no Android in field initializers)`() {
        TetherTransport()
    }

    @Test fun `close before open is a no-op, and close is idempotent`() {
        val t = TetherTransport()
        t.close()
        t.close()
    }
}
