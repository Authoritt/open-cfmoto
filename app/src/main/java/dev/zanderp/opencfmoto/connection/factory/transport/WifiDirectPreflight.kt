// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.connection.factory.transport

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import dev.zanderp.opencfmoto.NearbyDevices
import dev.zanderp.opencfmoto.connection.factory.TransportUnavailableException

/**
 * Why Wi-Fi Direct cannot work on this phone right now. Each value is a DIFFERENT thing the rider has to
 * do, which is the whole point: "couldn't connect" was the same sentence for all three.
 */
enum class WifiDirectBlocker {
    /** Phone Wi-Fi is off. Wi-Fi Direct needs the radio even though we never join a normal network. */
    WIFI_OFF,

    /** Android 13+ `NEARBY_WIFI_DEVICES` is not granted — every P2P call is rejected with `ERROR (0)`. */
    NEARBY_PERMISSION,

    /** No Wi-Fi Direct on this device at all (no `FEATURE_WIFI_DIRECT` / no Wi-Fi P2P system service). */
    UNSUPPORTED,
}

/**
 * The rider-facing sentences for the three blockers, INJECTED (localized) by
 * [dev.zanderp.opencfmoto.connection.factory.BikeConnectionFactory] — the only layer with a `Context` —
 * exactly like `ovk_conn_failed_rescan` / `ovk_conn_lost_retry` / `ovk_conn_needs_app`, so this package
 * stays free of Android resources. Null (the default, used by unit tests) keeps the technical text.
 */
data class WifiDirectMessages(
    val wifiOff: String? = null,
    val nearbyPermission: String? = null,
    val unsupported: String? = null,
)

/**
 * The pre-flight both Wi-Fi Direct connectors run BEFORE touching `WifiP2pManager`, and the reason-code
 * vocabulary they log with.
 *
 * Born from a real field failure (Rieju owner, 2026-08-19): `createGroup` was rejected twice, ~11 ms each
 * time, and all the log said was `createGroup failed: ERROR` — no code, no precondition, no advice. `ERROR`
 * is `WifiP2pManager`'s generic 0 and is precisely what a MISSING `NEARBY_WIFI_DEVICES` grant produces on
 * Android 13+, so the app was blind to its own most likely cause.
 */
object WifiDirectPreflight {

    /**
     * The framework's `ActionListener.onFailure` reason, NAMED and with its number — the line the field log
     * did not have. Pure (plain `Int` in, `String` out) so it is unit-testable off-device; the constants are
     * `WifiP2pManager`'s own, so this cannot drift from the platform.
     */
    fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR (0)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1)"
        WifiP2pManager.BUSY -> "BUSY (2)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS (3)"
        else -> "UNKNOWN ($reason)"
    }

    /**
     * The verdict a framework rejection carries about the PHONE. Only `P2P_UNSUPPORTED` is one: it says this
     * device cannot do Wi-Fi Direct at all, which is a different message from "it didn't work this time".
     * `ERROR`/`BUSY` are transient or unexplained by themselves — they are handled by the caller's bounded
     * stale-group repair, not by telling the rider their phone is incapable.
     */
    fun blockerForReason(reason: Int): WifiDirectBlocker? = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> WifiDirectBlocker.UNSUPPORTED
        else -> null
    }

    /**
     * The pure decision: given the three facts, which blocker (if any) stops Wi-Fi Direct. Ordered by what
     * the rider can fix fastest — the radio switch first, the grant second, and "this phone can't" last,
     * because that one is the only verdict they cannot act on.
     */
    fun blockerOf(wifiEnabled: Boolean, nearbyGranted: Boolean, wifiDirectSupported: Boolean): WifiDirectBlocker? = when {
        !wifiEnabled -> WifiDirectBlocker.WIFI_OFF
        !nearbyGranted -> WifiDirectBlocker.NEARBY_PERMISSION
        !wifiDirectSupported -> WifiDirectBlocker.UNSUPPORTED
        else -> null
    }

    /** The rider-facing sentence for [blocker], or null when the caller injected none (tests). Pure. */
    fun riderMessage(blocker: WifiDirectBlocker, msgs: WifiDirectMessages): String? = when (blocker) {
        WifiDirectBlocker.WIFI_OFF -> msgs.wifiOff
        WifiDirectBlocker.NEARBY_PERMISSION -> msgs.nearbyPermission
        WifiDirectBlocker.UNSUPPORTED -> msgs.unsupported
    }

    /** What goes in the LOG for [blocker] — precise, technical, and support-readable. Pure. */
    fun technical(blocker: WifiDirectBlocker): String = when (blocker) {
        WifiDirectBlocker.WIFI_OFF ->
            "phone Wi-Fi is OFF — Wi-Fi Direct needs the radio on even though we never join a normal network"
        WifiDirectBlocker.NEARBY_PERMISSION ->
            "NEARBY_WIFI_DEVICES is not granted (Android 13+, declared neverForLocation so a location grant " +
                "does NOT substitute) — every WifiP2pManager call is rejected with ERROR (0)"
        WifiDirectBlocker.UNSUPPORTED ->
            "this phone reports no Wi-Fi Direct support (no FEATURE_WIFI_DIRECT / no Wi-Fi P2P service)"
    }

    /**
     * Read the three facts off the device. Deliberately biased AGAINST false alarms: an absent `WifiManager`
     * counts as "Wi-Fi on" (same bias as [dev.zanderp.opencfmoto.WifiGate.isWifiEnabled] — never block on a
     * fact we could not read), while support is only denied when the platform says so twice over (no feature
     * flag AND no system service), so a phone with a quirky feature list still gets its attempt.
     */
    fun inspect(ctx: Context): WifiDirectBlocker? {
        val appCtx = ctx.applicationContext
        val wifiOn = (appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled ?: true
        val hasFeature = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val hasService = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager != null
        return blockerOf(
            wifiEnabled = wifiOn,
            nearbyGranted = NearbyDevices.granted(appCtx),
            wifiDirectSupported = hasFeature || hasService,
        )
    }

    /**
     * Run the pre-flight for a connector and either log that it passed or throw the rider-facing failure.
     * One line either way, so the next field log answers "why did Wi-Fi Direct not start?" by itself.
     *
     * Throws [TransportUnavailableException] — a NEVER-connected failure, so the driver turns it into a
     * terminal error (one attempt, no retry: the connect-time contract) whose text says what to DO instead
     * of the generic "scan the QR again", which would be wrong advice for all three causes.
     */
    fun requireReady(
        ctx: Context,
        msgs: WifiDirectMessages,
        log: (String) -> Unit,
        activity: Activity? = null,
    ) {
        val blocker = inspect(ctx)
        if (blocker == null) {
            log("preflight OK: Wi-Fi on, nearby-devices ${NearbyDevices.state(ctx, activity)}, Wi-Fi Direct supported")
            return
        }
        val technical = technical(blocker)
        // For the permission case the WAY it is missing decides what the rider must do (be asked again at
        // the next pairing, or open Settings), so it goes in the same line, not two greps apart.
        val detail = if (blocker == WifiDirectBlocker.NEARBY_PERMISSION) {
            "$technical [${NearbyDevices.state(ctx, activity)}]"
        } else {
            technical
        }
        log("preflight FAILED: $detail")
        throw TransportUnavailableException(riderMessage(blocker, msgs), detail)
    }
}
