package dev.zanderp.opencfmoto

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * The handlebar media/call buttons on the CFMoto dash don't ride the mirroring (PXC) link — the dash
 * sends them as ordinary Bluetooth AVRCP/HFP commands to whatever phone is paired to the bike. So the
 * app can't "capture" them; the fix is simply making sure the phone is paired to the bike over
 * Bluetooth. This helper reports that pairing/connection status and deep-links to Bluetooth settings
 * so pairing is one tap away.
 */
object BluetoothHelper {

    data class Status(
        val supported: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
        val deviceName: String?,
    ) {
        /** One-line summary for the Setup card. */
        fun describe(context: Context): String = when {
            !supported -> context.getString(R.string.setup_bt_no_bluetooth)
            !enabled -> context.getString(R.string.setup_bt_off)
            connected -> context.getString(
                R.string.setup_bt_connected,
                deviceName ?: context.getString(R.string.setup_bt_audio_device),
            )
            else -> context.getString(R.string.setup_bt_not_connected)
        }

        /** Short line for the mapping screen header. */
        fun shortLine(): String = when {
            !supported -> "Bluetooth: not available"
            !enabled -> "Bluetooth: off — tap to open settings"
            connected -> "BT connected: ${deviceName ?: "audio device"}"
            else -> "BT: on, no audio device connected — tap to pair"
        }
    }

    fun status(context: Context): Status {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        if (adapter == null) return Status(supported = false, enabled = false, connected = false, deviceName = null)

        if (!hasConnectPermission(context)) {
            // Can't read device names/state without the runtime permission; report enabled-only.
            return Status(supported = true, enabled = adapter.isEnabled, connected = false, deviceName = null)
        }

        val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
        val headset = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
        val connected = a2dp == BluetoothProfile.STATE_CONNECTED || headset == BluetoothProfile.STATE_CONNECTED

        val name = if (connected) liveConnectedName(adapter) else null

        return Status(supported = true, enabled = adapter.isEnabled, connected = connected, deviceName = name)
    }

    /**
     * Name of a *currently connected* bonded device (not “first paired ever”). Uses the hidden
     * [BluetoothDevice.isConnected] when present; prefers bike-looking names if several are up.
     */
    private fun liveConnectedName(adapter: BluetoothAdapter): String? {
        return try {
            val bonded = adapter.bondedDevices ?: return null
            val live = bonded.filter { it.isConnectedCompat() && !it.name.isNullOrBlank() }
            val pool = if (live.isNotEmpty()) live else bonded.filter { !it.name.isNullOrBlank() }
            val bikeish = pool.firstOrNull { d ->
                val n = d.name ?: return@firstOrNull false
                n.contains("CFMOTO", ignoreCase = true) ||
                    n.contains("CFDL", ignoreCase = true) ||
                    n.contains("Bike", ignoreCase = true) ||
                    n.contains("Moto", ignoreCase = true) ||
                    n.contains("VOGE", ignoreCase = true) ||
                    n.contains("QJ", ignoreCase = true)
            }
            (bikeish ?: pool.firstOrNull())?.name
        } catch (_: SecurityException) {
            null
        }
    }

    /** Hidden BluetoothDevice.isConnected() — public on some OEMs / via reflection elsewhere. */
    private fun BluetoothDevice.isConnectedCompat(): Boolean = try {
        val m = javaClass.getMethod("isConnected")
        m.invoke(this) as? Boolean == true
    } catch (_: Exception) {
        false
    }

    /** True if we hold BLUETOOTH_CONNECT (Android 12+) — needed to read state/names. */
    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Bonded devices the rider can pick as a Connect trigger (MAC + display name). */
    fun bondedDevices(context: Context): List<Pair<String, String>> {
        if (!hasConnectPermission(context)) return emptyList()
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .mapNotNull { d ->
                    val mac = d.address?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = d.name?.takeIf { it.isNotBlank() } ?: mac
                    mac to name
                }
                .sortedBy { it.second.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Open the system Bluetooth settings so the rider can pair/connect the bike. */
    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
