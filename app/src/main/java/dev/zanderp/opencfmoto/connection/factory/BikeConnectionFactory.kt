// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory

import android.content.Context
import dev.zanderp.opencfmoto.AppSettings
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.WifiTransport
import dev.zanderp.opencfmoto.connection.factory.link.EasyConnBikeLink
import dev.zanderp.opencfmoto.connection.factory.link.YunmoBikeLink
import dev.zanderp.opencfmoto.connection.factory.transport.P2pTransport
import dev.zanderp.opencfmoto.connection.factory.transport.PhoneHotspotTransport
import dev.zanderp.opencfmoto.connection.factory.transport.SoftApTransport
import dev.zanderp.opencfmoto.connection.factory.transport.TetherTransport

/**
 * Assembles a two-layer [BikeConnection] from a scanned QR (design doc 2026-08-18 §3): pick the Layer-1
 * [BikeTransport] by the bike's [TransportKind], stack the Layer-2 [BikeLink]s (EasyConn first, Yunmo as
 * fallback), and hand both to a [DefaultBikeConnection] that owns the lifecycle.
 */
object BikeConnectionFactory {

    /** Layer-1 selection: the transport that gets the phone onto this bike's network. */
    fun selectTransport(spec: ConnectionSpec): BikeTransport = when (spec.mode) {
        TransportKind.SOFT_AP -> SoftApTransport()
        TransportKind.P2P -> P2pTransport()
        TransportKind.PHONE_HOTSPOT -> PhoneHotspotTransport()
        TransportKind.TETHER -> TetherTransport()
    }

    /**
     * Retry caps by transport — **FINITE for every kind**, returning `(maxAttempts, flapMaxFailures)`.
     *
     * SoftAP/P2P previously got `Int.MAX_VALUE` "for parity with classic's unbounded retry". That premise was
     * WRONG, and it turned the CRITICAL mis-selection bug into an un-escapable one: classic's unbounded retry
     * is unbounded **SoftAP** retry, reached only AFTER a fast (~6 s) P2P bail-out, never unbounded retry on
     * the transport that cannot work. With the connector now DECIDED ONCE at pairing and used verbatim (no
     * cascade — see [detectedAtPairing]), an infinite cap on the wrong connector means a bike that retries
     * forever in silence, wedging `ConnectionState` in a busy phase and latching auto-connect OFF.
     *
     * The rule instead: a connect that cannot succeed must fail BOUNDED and VISIBLY — terminal
     * [ConnState.Error] (mirrored into the legacy `ConnectionState`, so auto-connect re-arms) carrying the
     * connector worth recommending ([suggestAlternativeConnector]). Written as an exhaustive `when` on
     * purpose: a future transport kind must DECIDE its retry policy here, not inherit one from an `else`.
     * Pure/internal for testing.
     */
    internal fun retryCapsFor(mode: TransportKind): Pair<Int, Int> = when (mode) {
        TransportKind.SOFT_AP, TransportKind.P2P -> MAX_ATTEMPTS to FLAP_MAX_FAILURES
        TransportKind.PHONE_HOTSPOT, TransportKind.TETHER -> MAX_ATTEMPTS to FLAP_MAX_FAILURES
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
     * A rider PIN is law and is never touched ([choice] != AUTO returns [stored] as-is): `RIEJU_BLE`
     * deliberately forces PHONE_HOTSPOT even for a QR whose `modelid` isn't known
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
        val (maxAttempts, flapMax) = retryCapsFor(spec.mode)
        return DefaultBikeConnection(
            transport = selectTransport(spec),
            links = listOf(EasyConnBikeLink(), YunmoBikeLink()),
            spec = spec,
            io = io,
            maxAttempts = maxAttempts,
            flapMaxFailures = flapMax,
            // Advice for the rider if this connector fails — never acted on here (no cascade, §1b).
            alternative = suggestAlternativeConnector(qr, spec.mode),
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
