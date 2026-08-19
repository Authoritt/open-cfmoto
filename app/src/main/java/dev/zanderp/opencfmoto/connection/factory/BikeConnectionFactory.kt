// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.content.Context
import androidx.annotation.StringRes
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.R
import dev.zanderp.opencfmoto.WifiTransport
import dev.zanderp.opencfmoto.connection.factory.link.EasyConnBikeLink
import dev.zanderp.opencfmoto.connection.factory.link.YunmoBikeLink
import dev.zanderp.opencfmoto.connection.factory.transport.P2pTransport
import dev.zanderp.opencfmoto.connection.factory.transport.PhoneHotspotTransport
import dev.zanderp.opencfmoto.connection.factory.transport.SoftApTransport
import dev.zanderp.opencfmoto.connection.factory.transport.TetherTransport
import dev.zanderp.opencfmoto.connection.factory.transport.WifiDirectMessages

/**
 * Assembles a two-layer [BikeConnection] from a scanned QR (design doc 2026-08-18 §3): pick the Layer-1
 * [BikeTransport] by the bike's [TransportKind], stack the Layer-2 [BikeLink]s (EasyConn first, Yunmo as
 * fallback), and hand both to a [DefaultBikeConnection] that owns the lifecycle.
 */
object BikeConnectionFactory {

    /**
     * Layer-1 selection: the transport that gets the phone onto this bike's network.
     *
     * [msgs] carries the rider-facing text for the Wi-Fi-Direct pre-flight failures (phone Wi-Fi off, the
     * Android 13+ nearby-devices grant missing, a phone with no Wi-Fi Direct). It defaults to empty so the
     * pure/plain-JVM callers — and every existing unit test — keep constructing transports with one
     * argument; [create] is the caller that has a Context and fills it in ([wifiDirectMessages]).
     */
    fun selectTransport(
        spec: ConnectionSpec,
        msgs: WifiDirectMessages = WifiDirectMessages(),
    ): BikeTransport = when (spec.mode) {
        TransportKind.SOFT_AP -> SoftApTransport()
        TransportKind.P2P -> P2pTransport(msgs)
        TransportKind.PHONE_HOTSPOT -> PhoneHotspotTransport(msgs)
        TransportKind.TETHER -> TetherTransport()
    }

    /**
     * The three "Wi-Fi Direct cannot work" sentences, localized HERE — the layer with a Context — exactly
     * like `ovk_conn_failed_rescan` and friends, so the factory package stays free of Android resources.
     *
     * The Wi-Fi-off line is per-connector on purpose: the BLE connector needs the radio because the PHONE
     * creates the network ("this bike connects to a network your phone creates"), while the P2P connector
     * joins the dash's — telling a 450NK owner their phone creates the network would be a small lie that
     * costs them a real minute of confusion. The other two sentences are the same either way.
     */
    private fun wifiDirectMessages(ctx: Context, mode: TransportKind) = WifiDirectMessages(
        wifiOff = ctx.getString(
            if (mode == TransportKind.PHONE_HOTSPOT) R.string.ovk_conn_wifi_off_phone_hosts
            else R.string.ovk_conn_wifi_off_direct,
        ),
        nearbyPermission = ctx.getString(R.string.ovk_conn_nearby_denied),
        unsupported = ctx.getString(R.string.ovk_conn_wifi_direct_unsupported),
    )

    /**
     * Retry caps by transport — **FINITE for every kind**, returning `(maxReconnectAttempts, flapMaxFailures)`.
     *
     * These caps bound the RECONNECT class only — a link that was alive and got LOST (`reduce`'s two failure
     * classes), retried on the SAME connector and shown to the rider as "Reconectando 1/3". A connector that
     * never established does not reach them at all: it fails on the FIRST attempt with no backoff, because
     * the connector is a Garage setting and a wrong one is fixed by re-scanning.
     *
     * SoftAP/P2P previously got `Int.MAX_VALUE` "for parity with classic's unbounded retry". That premise was
     * WRONG: classic's unbounded retry is unbounded **SoftAP** retry, reached only AFTER a fast (~6 s) P2P
     * bail-out, never unbounded retry on the transport that cannot work. Infinite caps here meant a bike that
     * retried in silence, wedging `ConnectionState` in a busy phase and latching auto-connect OFF.
     *
     * Written as an exhaustive `when` on purpose: a future transport kind must DECIDE its retry policy here,
     * not inherit one from an `else`. Pure/internal for testing.
     */
    internal fun retryCapsFor(mode: TransportKind): Pair<Int, Int> = when (mode) {
        TransportKind.SOFT_AP, TransportKind.P2P -> RECONNECT_MAX_ATTEMPTS to FLAP_MAX_FAILURES
        TransportKind.PHONE_HOTSPOT, TransportKind.TETHER -> RECONNECT_MAX_ATTEMPTS to FLAP_MAX_FAILURES
    }

    /**
     * **Under AUTO, a stored mode is a DETECTION RESULT, so it must equal what detection says TODAY**
     * ([detectedAtPairing] — the same function that seeds it at pairing). Pure/internal, no probing: one QR
     * read plus the two prefs the caller already fetched, so this is a decision, never a cascade.
     *
     * It heals two classes of stale/wrong stored mode without asking the rider to re-pair:
     *  - specs persisted BEFORE [TransportKind.TETHER] existed (every phone-hotspot QR was stored as
     *    PHONE_HOTSPOT, so a Zontes would now select the Rieju BLE connector — the exact regression
     *    `isBleHotspotQr` exists to prevent);
     *  - specs whose Wi-Fi mode was a bare `fromQr` GUESS that ignored the rider's Setup preference and the
     *    learned per-bike winner — the CRITICAL bug: the owner's `DIRECT-go-CFMOTO-*` 450NK (learned winner
     *    `"AP"`) was stored/derived as P2P and re-tried P2P on every single ride.
     *
     * A rider PIN is law and is never touched ([choice] != AUTO returns [stored] as-is):
     * [ConnectorChoice.BLE] deliberately forces PHONE_HOTSPOT even for a QR whose `modelid` isn't known
     * (`BikeMemory.setConnectorChoice`).
     *
     * @param pref `AppSettings.transport` — the rider's Setup Wi-Fi preference.
     * @param remembered `BikeMemory.winningTransport`, or null; refines AUTO only (see [resolveWifiTransport]).
     */
    internal fun reconcileStoredMode(
        stored: ConnectionSpec,
        qr: QrData,
        choice: ConnectorChoice,
        pref: WifiTransport = WifiTransport.AUTO,
        remembered: String? = null,
    ): ConnectionSpec {
        if (choice != ConnectorChoice.AUTO) return stored // a pin is law
        val detected = ConnectionSpec.detectedAtPairing(qr, pref, remembered).mode
        return if (stored.mode == detected) stored else stored.copy(mode = detected)
    }

    /**
     * The rider-facing NAME of a connector — the very strings the Garage/Scan picker shows, so the failure
     * message blames the connector by the same MECHANISM name the rider chose it with ("SoftAP", "Hotspot",
     * …) instead of an internal token or a brand.
     *
     * Mind the crossing (see `ConnectorChoice.forTransport`, the one place it is spelled out): the BLE
     * mechanism is [TransportKind.PHONE_HOTSPOT] and the Hotspot mechanism is [TransportKind.TETHER].
     */
    @StringRes
    private fun connectorNameRes(mode: TransportKind): Int = when (mode) {
        TransportKind.SOFT_AP -> R.string.ovk_conn_softap
        TransportKind.P2P -> R.string.ovk_conn_p2p
        TransportKind.PHONE_HOTSPOT -> R.string.ovk_conn_ble
        TransportKind.TETHER -> R.string.ovk_conn_hotspot
    }

    /**
     * The learned winner to feed the AUTO refinement, or null. Mirrors classic `joinWifi` EXACTLY: the
     * per-bike winner index refines [WifiTransport.AUTO] only — an explicit `AP`/`P2P` in Setup is the
     * rider's call and no memory may override it.
     */
    private fun rememberedFor(ctx: Context, qr: QrData, memory: BikeMemory, pref: WifiTransport): String? =
        if (pref == WifiTransport.AUTO) memory.winningTransport(ctx, qr.ssid) else null

    /**
     * Build a ready-to-drive connection for [qr]. Garage fast-path (Task 8): a remembered [ConnectionSpec]
     * for this bike skips re-detection (auto-vs-P2P racing, mode probing); a never-seen bike (or one whose
     * spec pre-dates a rule change) gets the SAME decision the classic path would make
     * ([ConnectionSpec.detectedAtPairing] / [reconcileStoredMode] — Setup preference + learned winner, no
     * probing). Either way, [DefaultBikeConnection] saves the spec back to [memory] once it reaches
     * `Connected` (refreshing [ConnectionSpec.lastEndpointHint]), so the *next* connect always has the
     * freshest known-good spec — that is where the resolved connector gets PERSISTED.
     */
    fun create(ctx: Context, qr: QrData, memory: BikeMemory, io: PlatformIO): BikeConnection {
        // The SAME two inputs the classic path consults, so the factory's AUTO decision is classic-identical
        // (they are two SharedPreferences reads — no probing, nothing that could slow a connect down).
        val pref = AppSettings.transport(ctx)
        val remembered = rememberedFor(ctx, qr, memory, pref)
        val choice = memory.connectorChoice(ctx, qr)
        // No stored spec ⇒ the bike is un-pinned by construction (`BikeMemory.setConnectorChoice` always
        // writes one alongside the pin), so detection is the right answer for that branch.
        val spec = memory.specFor(ctx, qr)
            ?.let { reconcileStoredMode(it, qr, choice, pref, remembered) }
            ?: ConnectionSpec.detectedAtPairing(qr, pref, remembered)
        val (maxReconnects, flapMax) = retryCapsFor(spec.mode)
        return DefaultBikeConnection(
            transport = selectTransport(spec, wifiDirectMessages(ctx, spec.mode)),
            links = listOf(EasyConnBikeLink(), YunmoBikeLink()),
            spec = spec,
            io = io,
            maxReconnectAttempts = maxReconnects,
            flapMaxFailures = flapMax,
            // Rider-facing text for a connector that never establishes: the Garage entry is wrong, and the
            // fix is a re-scan — NOT a retry and NOT another connector. Localized here because this is the
            // only layer with a Context (the factory package stays free of Android resources).
            initialFailureReason = ctx.getString(
                R.string.ovk_conn_failed_rescan,
                ctx.getString(connectorNameRes(spec.mode)),
            ),
            // …and for the other terminal case (a link that WAS alive and could not be recovered), which the
            // rider reads ON THE BIKE. No connector name here on purpose: the connector is not the suspect —
            // the network is — so naming it would only add noise to "tap Connect to try again".
            lostLinkReason = ctx.getString(R.string.ovk_conn_lost_retry),
            // …and the third terminal case: a connector that only works with the app on screen (Rieju BLE
            // handoff / manual hotspot) asked to connect from the background auto-connect.
            needsForegroundReason = ctx.getString(R.string.ovk_conn_needs_app),
            onConnected = { saved ->
                memory.saveSpec(ctx, saved)
                // Record the REAL transport outcome (saveSpec no longer mirrors spec.mode — that was a
                // fromQr guess). The phone-hosts-the-network kinds have no legacy AP/P2P winner index
                // (the classic path never wrote one for them either), so skip them.
                when (saved.mode) {
                    TransportKind.SOFT_AP -> memory.setWinningTransport(ctx, saved.ssid.orEmpty(), "AP")
                    TransportKind.P2P -> memory.setWinningTransport(ctx, saved.ssid.orEmpty(), "P2P")
                    TransportKind.PHONE_HOTSPOT, TransportKind.TETHER -> {}
                }
            },
        )
    }
}
