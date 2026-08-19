// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection

import android.net.Network
import dev.zanderp.opencfmoto.connection.factory.BikeConnection

/**
 * The one live factory-built [BikeConnection] (mirrors `ProjectionHolder` / `BikeLink` process-globals; see
 * `flip-work-design.md` §1). Null = no factory connection active ⇒ the classic 450NK path owns
 * reconnect/teardown byte-for-byte. Set in `CfmotoConnect.joinWifi` at the factory call sites (the dev-toggle
 * SoftAP/P2P branch AND the Rieju phone-hotspot branch); cleared by [disconnectAndClear] from every teardown
 * call-site. Holds the [BikeConnection] interface (not the concrete `DefaultBikeConnection`) so
 * `BikeConnectionFactory.create` keeps returning the interface.
 */
object BikeConnectionHolder {
    @Volatile
    var connection: BikeConnection? = null
        private set

    /** Replace any prior live connection (defensive: callers always tear down first); await the prior's release. */
    fun set(c: BikeConnection) {
        val prev = connection
        connection = c
        prev?.disconnectAndAwaitTeardown()
    }

    /**
     * Idempotent teardown: null FIRST (a concurrent re-acquire — which fires on the BikeWifi ConnectivityThread,
     * NOT the main looper — then sees "no factory"), then AWAIT the handle's teardown (transport.close() →
     * BikeWifi.leave()) so a connect that FOLLOWS (e.g. a mode switch) is strictly ordered after the Wi-Fi
     * release and can't race an in-flight leave() that would null the just-started session (review I1). No-op
     * when null ⇒ the OFF/classic path is unaffected (byte-for-byte).
     */
    fun disconnectAndClear() {
        val c = connection
        connection = null
        c?.disconnectAndAwaitTeardown()
    }

    /** SoftAP re-acquire hinge (design §2): drive the live factory connection's own re-establish. No-op when
     *  null. This is the single path `BikeLink.onWifiReacquired`'s fork calls (review M4). */
    fun onWifiReacquired(network: Network?) {
        connection?.onWifiReacquired(network)
    }
}
