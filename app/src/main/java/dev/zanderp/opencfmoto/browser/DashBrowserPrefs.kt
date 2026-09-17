// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

import android.content.Context

/**
 * Whether the projected content is the browser.
 *
 * `MapProvider.WEB` in the DataStore is the user-facing truth; this is its mirror in plain
 * SharedPreferences, because [dev.zanderp.opencfmoto.VideoPipeline] reads it while choosing the
 * presentation on a non-UI path and cannot suspend to collect a Flow. The UI writes both in the same
 * action, so they cannot drift.
 */
object DashBrowserPrefs {
    private const val PREFS = "dash_browser"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
