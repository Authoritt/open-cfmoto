package dev.zanderp.opencfmoto.connection.factory

import dev.zanderp.opencfmoto.connection.factory.transport.P2pTransport
import dev.zanderp.opencfmoto.connection.factory.transport.PhoneHotspotTransport
import dev.zanderp.opencfmoto.connection.factory.transport.SoftApTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 4: `BikeConnectionFactory.selectTransport` maps each [TransportKind] to its Layer-1 transport.
 * Pure and Android-free — only touches `selectTransport`, never `create` (which references Android types).
 */
class BikeConnectionFactoryTest {

    private fun spec(kind: TransportKind) = ConnectionSpec(bikeId = "test-bike", mode = kind)

    @Test
    fun `SOFT_AP selects SoftApTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.SOFT_AP))
        assertTrue("SOFT_AP must select SoftApTransport but was ${t::class.simpleName}", t is SoftApTransport)
    }

    @Test
    fun `P2P selects P2pTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.P2P))
        assertTrue("P2P must select P2pTransport but was ${t::class.simpleName}", t is P2pTransport)
    }

    @Test
    fun `PHONE_HOTSPOT selects PhoneHotspotTransport`() {
        val t = BikeConnectionFactory.selectTransport(spec(TransportKind.PHONE_HOTSPOT))
        assertTrue(
            "PHONE_HOTSPOT must select PhoneHotspotTransport but was ${t::class.simpleName}",
            t is PhoneHotspotTransport,
        )
    }

    // Reconnect-parity caps (review I2 / flip-work §2): SoftAP/P2P retry forever to match classic; the Rieju
    // phone-hotspot (one-shot BLE handoff + manual fallback) keeps the default give-up caps.
    @Test fun `retryCapsFor gives SoftAP effectively-infinite caps`() =
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, BikeConnectionFactory.retryCapsFor(TransportKind.SOFT_AP))

    @Test fun `retryCapsFor gives P2P effectively-infinite caps`() =
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, BikeConnectionFactory.retryCapsFor(TransportKind.P2P))

    @Test fun `retryCapsFor keeps the default caps for PHONE_HOTSPOT`() =
        assertEquals(MAX_ATTEMPTS to FLAP_MAX_FAILURES, BikeConnectionFactory.retryCapsFor(TransportKind.PHONE_HOTSPOT))
}
