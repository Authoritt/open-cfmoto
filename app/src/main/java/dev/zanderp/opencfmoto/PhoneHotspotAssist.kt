// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * UX helpers for Carbit `action=128` (phone hosts hotspot; dash is STA).
 *
 * Android does **not** let a normal app create a hotspot with a chosen SSID/password, so we:
 * - let the rider type the SSID/pwd shown on the dash (reminder only),
 * - deep-link into system hotspot / tethering settings,
 * - then poll for a tether interface + EasyConn peer.
 */
object PhoneHotspotAssist {

    private const val PREFS = "opencfmoto_phone_hotspot"
    private const val KEY_SSID = "dash_hotspot_ssid"
    private const val KEY_PWD = "dash_hotspot_pwd"

    data class Creds(val ssid: String, val pwd: String)

    fun loadCreds(ctx: Context): Creds {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Creds(
            ssid = p.getString(KEY_SSID, "").orEmpty(),
            pwd = p.getString(KEY_PWD, "").orEmpty(),
        )
    }

    fun saveCreds(ctx: Context, ssid: String, pwd: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SSID, ssid.trim())
            .putString(KEY_PWD, pwd)
            .apply()
    }

    /**
     * Best-effort open of the system hotspot / tethering UI. Returns true if an activity started.
     */
    fun openHotspotSettings(ctx: Context): Boolean {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (raw in candidates) {
            val intent = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                LogBus.log("[HOTSPOT] opened settings via ${intent.action ?: intent.component}")
                return true
            } catch (e: Exception) {
                LogBus.log("[HOTSPOT] settings intent failed (${intent.action}): ${e.message}")
            }
        }
        return false
    }

    /**
     * Dialog: enter dash-printed hotspot SSID/pwd → open system hotspot settings → [onContinue].
     */
    fun showSetupDialog(
        activity: Activity,
        qr: QrData,
        onContinue: () -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val saved = loadCreds(activity)
        val ssidField = EditText(activity).apply {
            hint = activity.getString(R.string.main_phone_hotspot_ssid_hint)
            setText(saved.ssid)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setPadding(pad, pad, pad, pad / 2)
        }
        val pwdField = EditText(activity).apply {
            hint = activity.getString(R.string.main_phone_hotspot_pwd_hint)
            setText(saved.pwd)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(pad, pad / 2, pad, pad)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(ssidField)
            addView(pwdField)
        }
        val macHint = qr.mac?.takeIf { it.isNotBlank() }?.let { "\nMAC: $it" }.orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.main_phone_hotspot_dialog_title)
            .setMessage(activity.getString(R.string.main_phone_hotspot_dialog_message) + macHint)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setNeutralButton(R.string.main_phone_hotspot_open_settings) { _, _ ->
                persistFromFields(activity, ssidField, pwdField)
                if (!openHotspotSettings(activity)) {
                    Toast.makeText(activity, R.string.main_phone_hotspot_settings_failed, Toast.LENGTH_LONG)
                        .show()
                }
                // Still continue so we poll while the rider configures hotspot.
                onContinue()
            }
            .setPositiveButton(R.string.main_phone_hotspot_ready) { _, _ ->
                persistFromFields(activity, ssidField, pwdField)
                onContinue()
            }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /**
     * Improved manual fallback for the Rieju phone-hotspot path (design doc §8 step 5): when the BLE AP-info
     * push (EcBtp `0x52`) can't complete, the phone is *already* the Wi-Fi Direct group owner, so its
     * `networkName`/`passphrase` are **readable** — show them for the rider to enter on the dash (with the
     * 2.4 GHz note), rather than the old "type the dash's creds" guessing. Best-effort, marshalled to the UI
     * thread ([Activity.runOnUiThread]) since the transport opens on a background dispatcher.
     */
    fun showReadableHotspotGuidance(activity: Activity, ssid: String, pwd: String, goAddress: String) {
        LogBus.log(
            "[HOTSPOT] BLE push unavailable — phone hosts readable creds: ssid='$ssid' pwdLen=${pwd.length} " +
                "phoneIp=$goAddress. Enter these on the dash; if the dash can't see the network, it may be 2.4 GHz-only.",
        )
        activity.runOnUiThread {
            runCatching {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.main_phone_hotspot_readable_title)
                    .setMessage(activity.getString(R.string.main_phone_hotspot_readable_message, ssid, pwd, goAddress))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun persistFromFields(ctx: Context, ssidField: EditText, pwdField: EditText) {
        val ssid = ssidField.text?.toString().orEmpty().trim()
        val pwd = pwdField.text?.toString().orEmpty()
        saveCreds(ctx, ssid, pwd)
        if (ssid.isNotEmpty()) {
            LogBus.log(
                "[HOTSPOT] dash hotspot creds noted ssid='$ssid' pwdLen=${pwd.length} — " +
                    "set these in Android hotspot / tethering settings (app cannot create the AP)",
            )
        } else {
            LogBus.log(
                "[HOTSPOT] no SSID typed — use the SSID/password printed on the dash " +
                    "in Android hotspot settings",
            )
        }
    }
}
