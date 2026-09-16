// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * The ONE switch that decides what the bike's handlebar does. Off (the default) it drives the dash —
 * the map, or the Android Auto UI when that is what is projected. On, it controls the phone's audio:
 * volume and track keys behave exactly as they would with no bike attached.
 *
 * It is one switch on purpose. There used to be two — "handlebar controls Android Auto" and "▲/▼
 * navigate vs volume" — plus a third, invisible opinion: the app guessed whether a bike even HAD a
 * ▲/▼ rocker, and that guess silently outranked both switches. Set to navigate, guessed absent,
 * nothing engaged, arrows fell through to phone volume, and no screen said why. Three controls for
 * one decision is how a setting ends up meaning nothing.
 *
 * Trade-off, stated: while off, the phone's music volume is held so the dash's absolute-volume writes
 * can be read as button presses. A rider whose pod has no rocker, or who would rather keep volume on
 * the bars, turns this on — one switch, immediate, and it always wins.
 *
 * Per-bike via [BikeScope], so a rider can have the arrows on the map for one bike and on audio for
 * another. The legacy key is still read once so nobody's earlier choice is lost.
 */
object HandlebarAudioMode {
    private const val PREF = "handlebar_volume_mode"
    private const val KEY = "audio"

    /** Pre-2.0.14 key: `true` meant "▲/▼ drive the dash", i.e. the inverse of [KEY]. */
    private const val LEGACY_KEY = "navigate"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** True = the handlebar controls the phone's audio. False (default) = it drives the dash. */
    fun isAudio(context: Context): Boolean {
        val p = prefs(context)
        if (BikeScope.hasBoolean(p, context, KEY)) return BikeScope.getBoolean(p, context, KEY, false)
        if (BikeScope.hasBoolean(p, context, LEGACY_KEY)) {
            return !BikeScope.getBoolean(p, context, LEGACY_KEY, true)
        }
        return false
    }

    fun setAudio(context: Context, audio: Boolean) {
        BikeScope.putBoolean(prefs(context), context, KEY, audio)
        LogBus.log("[BTN] handlebar set to ${if (audio) "AUDIO (volume/tracks on the phone)" else "DASH (drives the map)"}")
        MediaButtonBridge.instance?.refreshCapturePolicy()
    }
}
