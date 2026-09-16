// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import java.io.File

/**
 * The dash panel as an HTML file instead of a native layout.
 *
 * ## Why this exists
 * The bike dash is fed by a [android.app.Presentation] on a private VirtualDisplay. Anything we can
 * put in that Presentation reaches the dash — and a `WebView` is our OWN View in our OWN process, so
 * it is not blocked by the `ADD_TRUSTED_DISPLAY` wall that stops third-party apps from being projected.
 *
 * A probe on the owner's phone measured the part that was actually in doubt: with the phone SCREEN OFF
 * the page keeps running at full speed (rAF 59.5 Hz, `setInterval` 10/s, encoder 30 fps, and
 * `document.visibilityState` never leaves `visible`). The reason is structural, not luck: the page
 * lives on the VirtualDisplay, whose display state is independent of the physical panel, so Chromium
 * never sees a visibility change and never throttles. See `knowledge/open-cfmoto/LEARNINGS.md`.
 *
 * ## Where the panel lives
 * [dir] — the app's own external files dir, so there are **no runtime permissions** and no SAF picker.
 * It is still reachable from a PC, which is the point: the panel can be changed without recompiling or
 * reinstalling, which is what the build→sign→7z→RelayCore loop costs today.
 *
 * ```
 * adb push panel.html /sdcard/Android/data/dev.authoritforge.opencfmoto/files/paneles/panel.html
 * ```
 *
 * (In Git Bash on Windows prefix that with `MSYS_NO_PATHCONV=1`, or the shell rewrites `/sdcard/...`
 * into a path under the Git install and `adb push` fails with a misleading `secure_mkdirs()` error.)
 *
 * The dash reloads on its own when the file changes — no reconnect, no restart.
 */
object DashHtmlPanel {

    private const val PREFS = "dash_html_panel"
    private const val KEY_ENABLED = "enabled"

    /** Panel folder: `<externalFilesDir>/paneles`. */
    fun dir(context: Context): File =
        File(context.applicationContext.getExternalFilesDir(null), "paneles")

    /** The panel the dash renders. */
    fun file(context: Context): File = File(dir(context), PANEL_NAME)

    /** DEV toggle: when on, the dash Presentation hosts the HTML panel instead of the map/placeholder. */
    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * Writes the starter panel if there is none, and returns the file the dash should load, or null if
     * the folder could not be created (no external storage) — the caller then keeps the native content
     * rather than showing a blank dash.
     *
     * Never overwrites an existing panel: once the owner edits it, it is his.
     */
    fun ensureSeeded(context: Context): File? {
        val f = file(context)
        try {
            if (f.exists()) return f
            if (!dir(context).exists() && !dir(context).mkdirs()) return null
            f.writeText(STARTER)
            return f
        } catch (_: Exception) {
            return if (f.exists()) f else null
        }
    }

    /**
     * PURE, so it can be unit-tested without Android: given a panel folder, which file does the dash
     * render? [PANEL_NAME] wins; otherwise the alphabetically first `.html`, so dropping a single file
     * with any name into an empty folder still works. Null means "nothing renderable here".
     */
    fun resolve(panelDir: File): File? {
        val named = File(panelDir, PANEL_NAME)
        if (named.isFile) return named
        val html = panelDir.listFiles { f -> f.isFile && f.name.endsWith(".html", ignoreCase = true) }
            ?: return null
        return html.sortedBy { it.name.lowercase() }.firstOrNull()
    }

    const val PANEL_NAME = "panel.html"

    /**
     * The starter panel. Deliberately shows a CLOCK and a tick counter: the first question anyone asks
     * of a projected panel is "is this live or is it a frozen screenshot?", and this answers it from
     * across the handlebars. Big, high-contrast, dark — it is read at a glance, in sunlight, at speed.
     */
    private val STARTER = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          html,body{margin:0;height:100%;background:#000;color:#fff;overflow:hidden;
                    font-family:system-ui,-apple-system,sans-serif}
          .wrap{height:100%;display:flex;flex-direction:column;justify-content:center;
                padding:0 28px;box-sizing:border-box}
          .clock{font-size:104px;font-weight:700;line-height:1;letter-spacing:-2px;
                 font-variant-numeric:tabular-nums}
          .sub{font-size:26px;color:#8b8b8b;margin-top:10px}
          .tick{position:absolute;right:20px;bottom:14px;font-size:20px;color:#FF6A2C;
                font-variant-numeric:tabular-nums}
        </style></head><body>
          <div class="wrap">
            <div class="clock" id="clock">--:--</div>
            <div class="sub">Edita este archivo y el tablero se actualiza solo.</div>
          </div>
          <div class="tick" id="tick">vivo 0</div>
        <script>
          var n = 0;
          function paint(){
            var d = new Date();
            document.getElementById('clock').textContent =
              String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
            document.getElementById('tick').textContent = 'vivo ' + (++n);
          }
          paint();
          setInterval(paint, 1000);
        </script></body></html>
    """.trimIndent()
}
