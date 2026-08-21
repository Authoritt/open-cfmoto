// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The `NEARBY_WIFI_DEVICES` runtime grant — the one Wi-Fi Direct cannot work without on Android 13+.
 *
 * **The bug this exists for.** The permission was declared in the manifest (with
 * `usesPermissionFlags="neverForLocation"`, so a location grant does NOT substitute for it) but NEVER
 * requested at runtime. On API 33+ `WifiP2pManager.createGroup`/`connect`/`discoverPeers` are then rejected
 * INSTANTLY with the generic `ERROR (0)` — indistinguishable from a radio problem, and exactly what the
 * Rieju owner's log shows (`createGroup failed: ERROR`, ~11 ms, twice). The same signature appears in this
 * repo's own notes about the 450NK ("discoverPeers/credential/MAC all ERROR instantly", `BikeWifiP2p.kt`),
 * which is why that bike learned `winningTransport = "AP"` — whether this grant also revives Wi-Fi Direct
 * there is a HYPOTHESIS for the owner to test on the bike, not a claim.
 *
 * **Where it is asked for**: at PAIRING (`ui/scan/ScanScreen`, once the QR resolves and the bike's connector
 * is known to need Wi-Fi Direct) and in first-run onboarding (`SetupHelper.onboardingPermissions`). Never
 * mid-connect: a system dialog on the road, or at a headless auto-connect that has no UI at all, is the
 * worst possible moment. At connect time the transports only CHECK ([granted]) and fail with a rider-facing
 * "turn it on in Settings" message.
 */
object NearbyDevices {

    /**
     * The permission string. A compile-time `String` constant, so naming it on an Android 12 phone is free
     * (nothing is loaded); [required] is what decides whether it means anything there.
     */
    const val PERMISSION: String = Manifest.permission.NEARBY_WIFI_DEVICES

    private const val PREFS = "opencfmoto_perms"
    private const val KEY_ASKED = "nearby_wifi_asked"

    /**
     * Android 13+ only. Below TIRAMISU the permission does not exist as a runtime grant (the P2P APIs are
     * gated on location there instead), so every check below answers "fine" and nothing changes for those
     * phones — the API guard the whole fix hangs on.
     */
    fun required(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** True when Wi-Fi Direct may be driven: granted on 13+, always true below. */
    fun granted(ctx: Context): Boolean = !required() ||
        ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Remember that the rider has been shown the system prompt at least once (see [state]). */
    fun markAsked(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun asked(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    /**
     * What support will read in the log. The three "not granted" cases need different actions from the
     * rider, and Android reports them only in combination: `shouldShowRequestPermissionRationale` is false
     * BOTH before the first prompt and after a permanent denial, so we pair it with our own "we have already
     * shown the prompt" flag ([markAsked]) to tell those two apart.
     */
    fun state(ctx: Context, activity: Activity? = null): String = when {
        !required() -> "not needed below Android 13"
        granted(ctx) -> "granted"
        !asked(ctx) -> "NOT granted, never requested (pair the bike again from Scan to be asked)"
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISSION) ->
            "NOT granted, denied once (the next pairing can ask again)"
        activity != null -> "NOT granted, denied permanently (only the app's Settings can restore it)"
        else -> "NOT granted, already requested before (no Activity here to tell 'denied once' from 'denied for good')"
    }
}
