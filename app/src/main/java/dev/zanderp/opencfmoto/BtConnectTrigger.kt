// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Optional: when a chosen bonded device ACL-connects (helmet remote, watch, …),
 * open [MainActivity] and start Connect with the saved bike QR.
 */
class BtConnectTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val want = AppSettings.btTriggerMac(context) ?: return
        val device = deviceFrom(intent) ?: return
        val mac = try {
            device.address
        } catch (_: SecurityException) {
            return
        } ?: return
        if (!mac.equals(want, ignoreCase = true)) return
        if (AndroidAutoService.isRunning) return
        if (BikeMemory.lastQr(context) == null) {
            LogBus.log("→ BT trigger $mac ignored — no saved bike")
            return
        }
        LogBus.log("→ BT trigger: $mac connected — starting Connect")
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_BT_TRIGGER, true)
        }
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            LogBus.log("BT trigger launch failed: $e")
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
