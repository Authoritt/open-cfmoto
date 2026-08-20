// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.ui.connection

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.zanderp.opencfmoto.BikeMemory
import dev.zanderp.opencfmoto.LogBus
import dev.zanderp.opencfmoto.NearbyDevices
import dev.zanderp.opencfmoto.QrData
import dev.zanderp.opencfmoto.WifiGate
import dev.zanderp.opencfmoto.ui.components.RadioNeed
import dev.zanderp.opencfmoto.connection.factory.TransportKind
import dev.zanderp.opencfmoto.connection.factory.usesWifiDirect

/**
 * Ask for whatever THIS bike's connector needs, at the moment the rider taps Connect.
 *
 * Asking only while pairing was a real field failure, reported three times in different clothes: the
 * permission is declared, never requested on the path that actually needs it, and the connect then dies
 * in a way indistinguishable from broken hardware. The Rieju owner's log is the clearest case — his bike
 * was already paired, so he connects from the dashboard and the pairing screen never runs:
 *
 *     [BLE-AP] could not start the BLE scan: SecurityException: Need android.permission.BLUETOOTH_SCAN
 *     [BLE-AP] target DD:0D:…: bond=unknown type=unknown (never scanned/bonded)
 *     [BLE-AP] FAILED: timeout after 25000ms
 *
 * …with no permission prompt anywhere in the session. So every connect entry point calls this first.
 *
 * Only asks for what the bike's own connector uses: a SoftAP rider is never prompted for Bluetooth, and
 * nothing is asked below the API level where the permission exists.
 *
 * @return true when the connect may proceed; false when a prompt was raised — the grant arrives
 *   asynchronously and the rider taps Connect again, the same shape as the existing location gate.
 */
fun ensureConnectorReady(
    activity: Activity,
    qr: QrData?,
    onRadioNeeded: (RadioNeed) -> Unit = {},
): Boolean {
    qr ?: return true
    val mode = runCatching { BikeMemory.effectiveMode(activity, qr) }.getOrNull() ?: return true

    if (usesWifiDirect(mode) && NearbyDevices.required() && !NearbyDevices.granted(activity)) {
        LogBus.log("[preflight] '$mode' needs Wi-Fi Direct — asking for the nearby-devices permission")
        NearbyDevices.markAsked(activity)
        ActivityCompat.requestPermissions(activity, arrayOf(NearbyDevices.PERMISSION), REQ_CONNECTOR)
        return false
    }

    if (mode == TransportKind.PHONE_HOTSPOT) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                LogBus.log("[preflight] this bike hands its Wi-Fi credentials over Bluetooth — asking for ${missing.size} permission(s)")
                ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQ_CONNECTOR)
                return false
            }
        }
        // Granted but switched off fails exactly like missing, and the rider cannot tell the two apart
        // from a connect that simply times out. Raise the system's own enable dialog.
        val adapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter != null && !adapter.isEnabled) {
            // Radio OFF is reported, not acted on: the caller shows OUR dialog (RadioNeededDialog) first,
            // so the rider reads a Spanish explanation of why the bike needs it before the platform's own
            // system-styled English prompt appears.
            LogBus.log("[preflight] this bike needs Bluetooth and it is OFF — asking the rider to turn it on")
            onRadioNeeded(RadioNeed.BLUETOOTH)
            return false
        }
    }
    return true
}

/** Fire the platform action for a radio the rider agreed to switch on, from our dialog's button. */
fun enableRadio(activity: Activity, need: RadioNeed) {
    when (need) {
        RadioNeed.BLUETOOTH -> runCatching { activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        RadioNeed.WIFI -> WifiGate.openWifiSettings(activity)
    }
}

private const val REQ_CONNECTOR = 0x0C07
