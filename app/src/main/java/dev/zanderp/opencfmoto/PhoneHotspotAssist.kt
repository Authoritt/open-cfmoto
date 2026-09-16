// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * UX helpers for Carbit `action=128` (phone hosts hotspot; dash is STA).
 *
 * Android does **not** let a normal app create a hotspot with a chosen SSID/password — that has been
 * system-only since Android 10, and the dash looks for one specific network name and password. So this step
 * cannot be automated away; it can only be made painless:
 * - the dash's network name / password are remembered and can be COPIED with one tap, so the rider pastes
 *   them into Android's hotspot settings instead of retyping them (by far the most error-prone part: one
 *   wrong character and the dash simply never appears),
 * - one tap deep-links into the system hotspot / tethering settings,
 * - then we poll for a tether interface + EasyConn peer and continue on our own.
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
     * The two-step hotspot dialog: **1)** here are the dash's network name and password — copy them;
     * **2)** open hotspot settings, set them there, come back.
     *
     * It used to be two bare, unlabelled text fields and a button, which left the rider to work out on
     * their own what the fields were for and what to do next. The typing is the part that fails (one wrong
     * character in the password and the dash never shows up), so each field gets a Copy button and the
     * steps get numbers. What we still cannot do is switch the hotspot on for them: Android reserves that
     * for the system.
     *
     * Continues to [onContinue] from BOTH buttons — "open settings" too, so the polling is already running
     * while the rider is in Android's settings screen and the dash joins without another tap.
     */
    fun showSetupDialog(
        activity: Activity,
        qr: QrData,
        onContinue: () -> Unit,
        onCancel: () -> Unit = {},
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val saved = loadCreds(activity)

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }

        fun step(text: String, topGap: Int) {
            col.addView(
                TextView(activity).apply {
                    this.text = text
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(topGap) }
                },
            )
        }

        /** A remembered credential + its one-tap Copy — the whole point of this dialog. */
        fun credential(hint: String, value: String, password: Boolean, @StringRes copiedRes: Int): EditText {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            }
            val til = TextInputLayout(activity).apply {
                this.hint = hint
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val field = TextInputEditText(til.context).apply {
                setText(value)
                isSingleLine = true
                imeOptions = if (password) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                // Visible password on purpose: the rider is copying it ONTO another screen, so hiding it
                // behind dots would only make the mistake we are trying to prevent harder to catch.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            til.addView(field)
            row.addView(til)
            row.addView(
                MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = activity.getString(R.string.main_phone_hotspot_copy)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(8) }
                    setOnClickListener {
                        copyCredential(activity, hint, field.text?.toString().orEmpty(), password, copiedRes)
                    }
                },
            )
            col.addView(row)
            return field
        }

        step(activity.getString(R.string.main_phone_hotspot_step1), topGap = 0)
        val ssidField = credential(
            hint = activity.getString(R.string.main_phone_hotspot_ssid_hint),
            value = saved.ssid,
            password = false,
            copiedRes = R.string.main_phone_hotspot_copied_ssid,
        )
        val pwdField = credential(
            hint = activity.getString(R.string.main_phone_hotspot_pwd_hint),
            value = saved.pwd,
            password = true,
            copiedRes = R.string.main_phone_hotspot_copied_pwd,
        )
        step(activity.getString(R.string.main_phone_hotspot_step2), topGap = 14)

        val macHint = qr.mac?.takeIf { it.isNotBlank() }?.let { "\nMAC: $it" }.orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.main_phone_hotspot_dialog_title)
            .setMessage(activity.getString(R.string.main_phone_hotspot_dialog_message) + macHint)
            .setView(col)
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
     * Put one credential on the clipboard so it can be PASTED into Android's hotspot settings.
     *
     * The password is marked sensitive on Android 13+ so the system's clipboard preview doesn't display it
     * on screen; and the confirmation toast is skipped there too, because that Android version already
     * shows its own "copied" confirmation and two of them is just noise.
     */
    private fun copyCredential(
        activity: Activity,
        label: String,
        value: String,
        sensitive: Boolean,
        @StringRes copiedRes: Int,
    ) {
        if (value.isBlank()) {
            // Nothing to copy yet: the first time round, the rider still has to read these off the dash.
            Toast.makeText(activity, R.string.main_phone_hotspot_copy_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        LogBus.log("[HOTSPOT] copied ${if (sensitive) "password" else "network name"} to the clipboard")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, copiedRes, Toast.LENGTH_SHORT).show()
        }
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
