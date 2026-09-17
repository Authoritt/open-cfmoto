// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.Network
import java.net.Inet4Address

/**
 * Process-global handle to the bike PXC client ([EasyConnProber]).
 *
 * Held here — not only as a [MainActivity] field — so the Android Auto → bike hand-off and the
 * Stop control survive a [MainActivity] recreation. Triggering Google Android Auto (self-mode)
 * brings Gearhead to the foreground, which can destroy and recreate [MainActivity] while the AA
 * receiver keeps running in [AndroidAutoService]. The bike connection must not be torn down or
 * orphaned when that happens: a fresh activity re-reads the SAME prober instance from here instead
 * of constructing a new one that would leave the running one leaked and unstoppable.
 *
 * Matches the existing process-global style ([AaVideoBridge], [ProjectionHolder], [BikeProfileHolder]).
 */
object BikeLink {
    @Volatile var prober: EasyConnProber? = null

    private val rearmHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRearm: Runnable? = null

    /**
     * Pedir que el tablero vuelva a decidir QUE muestra, agrupando peticiones seguidas.
     *
     * La ficha del cockpit cicla de proveedor a cada toque, asi que tres toques rapidos pedirian tres
     * re-armados -- y cada uno tira y reconstruye el VideoPipeline. Zarandear asi la fuente de video
     * de una moto conectada es pedir un problema, y ademas los dos primeros no los llega a ver nadie.
     * Se queda el ultimo.
     */
    fun requestDashRearm(reason: String, delayMs: Long = 700L) {
        rearmHandler.post {
            pendingRearm?.let { rearmHandler.removeCallbacks(it) }
            val r = Runnable {
                pendingRearm = null
                val p = prober ?: return@Runnable
                runCatching { p.reattachOwnedVideo(reason) }
                    .onFailure { LogBus.log("[MAP] re-armar el tablero fallo: $it") }
            }
            pendingRearm = r
            rearmHandler.postDelayed(r, delayMs)
        }
    }
    @Volatile private var appContext: Context? = null

    // ---- Android Auto → bike start coordination (parallel-startup gate) ----
    // The two slow steps used to be serial: wait for AA "steady video", THEN pop the Wi-Fi join
    // dialog, THEN probe the bike. We now kick off the Wi-Fi join IN PARALLEL with AA boot and only
    // start the probe once BOTH are ready — the user accepts the Wi-Fi dialog while AA is still
    // spinning up, shaving several seconds. The bike still never gets probed before AA has frames to
    // serve, so streaming correctness is unchanged. Lives here so a MainActivity recreation
    // mid-startup doesn't lose the pending gate.
    @Volatile private var aaVideoSteady = false
    @Volatile private var bikeNetwork: Network? = null
    @Volatile private var networkReady = false
    @Volatile private var proberStarted = false
    /** Wi‑Fi Direct path: no [Network], so the prober binds/probes with these overrides. */
    @Volatile private var p2pBindIp: Inet4Address? = null
    @Volatile private var p2pGatewayIp: Inet4Address? = null
    @Volatile private var aaDropRetried = false

    /** Reset the gate at the start of a fresh Android Auto connection attempt. */
    @Synchronized
    fun beginHandoff(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        aaVideoSteady = false
        bikeNetwork = null
        networkReady = false
        proberStarted = false
        p2pBindIp = null
        p2pGatewayIp = null
        aaDropRetried = false
        AaVideoBridge.aaSessionSeen = false
        // Leave the bike Network held, but unpin the process so AA can use 127.0.0.1.
        appContext?.let { BikeWifi.unbindProcess(context = it) }
    }

    /**
     * True while this hand-off runs on a transport the PHONE hosts and that Wi-Fi Direct does not drive:
     * the classic phone-hotspot route (`CfmotoConnect.joinPhoneHotspot` → [markP2pReady] with the tether
     * interface's bind IP). It is the one AA-reachable transport whose liveness NOTHING in the Wi-Fi layer
     * can report — there is no bike `Network` ([BikeWifi.currentNetwork] stays null for the whole ride)
     * and no Wi-Fi Direct group ([BikeWifiP2p.isSessionActive] is false because that helper is never used
     * on this route). Read by [AndroidAutoService.bikeTransportConnected] so the reconnect supervisor does
     * not read "unreportable" as "down" and park a live dash. Cleared by [beginHandoff] with the rest of
     * the gate (`p2pBindIp = null`).
     *
     * Deliberately NOT true for Wi-Fi Direct, which also lands in [markP2pReady]: there [BikeWifiP2p]
     * reports the group, and upstream's 7e198ae fix depends on a lost group still being observable.
     */
    val onPhoneHostedTransport: Boolean
        get() = p2pBindIp != null && !BikeWifiP2p.isSessionActive

    /** One extra self-mode trigger after AA attaches then dies before video is steady. */
    @Synchronized
    fun takeAaDropRetry(): Boolean {
        if (aaDropRetried || aaVideoSteady) return false
        aaDropRetried = true
        return true
    }

    @Synchronized
    fun markAaVideoSteady() {
        aaVideoSteady = true
        maybeStartProbe()
    }

    /** [network] may be null on some devices (process already bound); readiness is the real signal. */
    @Synchronized
    fun markWifiReady(network: Network?) {
        bikeNetwork = network
        networkReady = true
        maybeStartProbe()
    }

    /** Wi‑Fi Direct: no [Network] — store bind/gateway IPs and mark ready. */
    @Synchronized
    fun markP2pReady(bindIp: Inet4Address, gatewayIp: Inet4Address) {
        p2pBindIp = bindIp
        p2pGatewayIp = gatewayIp
        bikeNetwork = null
        networkReady = true
        maybeStartProbe()
    }

    private fun maybeStartProbe() {
        if (proberStarted || !aaVideoSteady || !networkReady) return
        val p = prober ?: return
        proberStarted = true
        appContext?.let { ctx ->
            if (BikeWifi.rebindProcessToBike(ctx)) {
                LogBus.log("→ process bound to bike Wi-Fi (AA video is live)")
            }
        }
        LogBus.log("→ AA video + bike Wi-Fi both ready — starting EasyConn PXC flow …")
        ConnectionState.set(Phase.PXC_CONNECTING)
        appContext?.let { DashClockBle.start(it) }
        try {
            p.start(bikeNetwork, gatewayOverride = p2pGatewayIp, bindIpOverride = p2pBindIp)
        } catch (e: Exception) {
            LogBus.log("prober start failed: $e")
            ConnectionState.set(Phase.ERROR, "prober start failed")
        }
    }

    /**
     * The bike's Wi-Fi came back after a drop (e.g. the rider stopped and restarted the bike). The
     * old prober was probing a dead interface with stale IPs/server binds, so fully restart it on the
     * fresh [network]: stop it (this only closes sockets/servers — the shared Android Auto pipeline is
     * owned by [AndroidAutoService] and survives), then start again so it rebinds and re-probes. Called
     * by [BikeWifi] on every re-acquisition after the first.
     */
    @Synchronized
    fun onWifiReacquired(network: Network?) {
        // A factory connection owns its own reconnect. Drive it via the holder (single path, review M4) and DO
        // NOT touch the classic prober below (prevents the double-prober race). This runs on BikeWifi's
        // ConnectivityThread (NOT the main looper). Inert when no factory connection is live → the classic path
        // runs byte-for-byte. Single convergence point for ALL classic re-establish (real re-acquire + socket
        // recovery), so forking here covers both (flip-work-design.md §2).
        // The holder itself decides whether it can take it: a driver that already ended in a terminal Error
        // has no receiver for the event, so it hands the re-acquire BACK to the classic prober below instead
        // of swallowing it (that driver is dead — an initial-connect failure ends it on the first attempt).
        if (dev.zanderp.opencfmoto.connection.BikeConnectionHolder.onWifiReacquired(network)) {
            LogBus.log("→ Wi-Fi re-acquired → factory connection re-establish (classic prober untouched)")
            return
        }
        // If the service parked Android Auto (long outage → torn down to save battery), it must rebuild
        // AA before the bike link is useful — hand off to the service instead of restarting the prober
        // against a dead (stopped) pipeline.
        if (AndroidAutoService.isParked) {
            LogBus.log("→ Wi-Fi back while AA parked — asking service to resume")
            AndroidAutoService.requestResume()
            return
        }
        val p = prober ?: return
        LogBus.log("→ restarting bike link on re-acquired Wi-Fi")
        ConnectionState.set(Phase.PXC_CONNECTING, "reconnecting")
        try { p.stop() } catch (_: Exception) {}
        bikeNetwork = network
        networkReady = true
        proberStarted = true
        appContext?.let { DashClockBle.start(it) }
        try {
            p.start(network)
        } catch (e: Exception) {
            LogBus.log("prober restart failed: $e")
            ConnectionState.set(Phase.ERROR, "prober restart failed")
        }
    }
}
