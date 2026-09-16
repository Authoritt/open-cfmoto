// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Which physical handlebar sources this bike actually delivers over AVRCP. Some dashes (e.g. 800NK
 * Adventure) never emit volume ▲/▼ while still sending track keys — pinning phone volume waiting for
 * rockers that never arrive is a "volume hostage". Teach-my-handlebar / auto-probe write here;
 * [MediaButtonBridge] reads it before pinning.
 *
 * Per-bike via [BikeScope]. Unknown = not taught yet (pin as today); Present / Absent are sticky
 * until the rider re-teaches or we see an event that flips Present.
 */
enum class ButtonPresence {
    UNKNOWN,
    PRESENT,
    ABSENT,
}

object ButtonPresencePrefs {
    private const val PREF = "button_presence"
    private const val KEY_VOLUME = "volume_rocker"
    private const val KEY_TRACK = "track_keys"
    private const val KEY_TRACK_FWD = "track_fwd"
    private const val KEY_TRACK_BACK = "track_back"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun volumeRocker(ctx: Context): ButtonPresence =
        read(ctx, KEY_VOLUME)

    fun trackKeys(ctx: Context): ButtonPresence =
        read(ctx, KEY_TRACK)

    fun setVolumeRocker(ctx: Context, presence: ButtonPresence) {
        write(ctx, KEY_VOLUME, presence)
    }

    fun setTrackKeys(ctx: Context, presence: ButtonPresence) {
        write(ctx, KEY_TRACK, presence)
    }

    /** Call when a volume ▲/▼ event is observed — proves the rocker exists. */
    fun markVolumeSeen(ctx: Context) {
        if (volumeRocker(ctx) != ButtonPresence.PRESENT) {
            setVolumeRocker(ctx, ButtonPresence.PRESENT)
            LogBus.log("[BTN] volume rocker marked PRESENT (event seen)")
        }
    }

    /**
     * Record that a real ◀ or ▶ media key arrived. Both directions present means this pod can steer the
     * dash on its own, and the ▲/▼ rocker is not needed for it — which matters because the rocker is
     * this bike's VOLUME protocol: every press makes the dash pop its volume box and forces us to put
     * the level back. Pods without a second direction still need it.
     */
    fun markTrackDirection(ctx: Context, forward: Boolean) {
        val key = if (forward) KEY_TRACK_FWD else KEY_TRACK_BACK
        if (BikeScope.getBoolean(prefs(ctx), ctx, key, false)) return
        BikeScope.putBoolean(prefs(ctx), ctx, key, true)
        LogBus.log("[BTN] this handlebar has a real ${if (forward) "▶" else "◀"} key")
    }

    /** True when the pod proved BOTH directions with its own keys — no rocker needed to navigate. */
    fun hasBothTrackDirections(ctx: Context): Boolean =
        BikeScope.getBoolean(prefs(ctx), ctx, KEY_TRACK_FWD, false) &&
            BikeScope.getBoolean(prefs(ctx), ctx, KEY_TRACK_BACK, false)

    /** Call when ◀/▶ / Select media keys are observed. */
    fun markTrackSeen(ctx: Context) {
        if (trackKeys(ctx) != ButtonPresence.PRESENT) {
            setTrackKeys(ctx, ButtonPresence.PRESENT)
            LogBus.log("[BTN] track keys marked PRESENT (event seen)")
        }
    }

    /** True when we should pin STREAM_MUSIC to hijack ▲/▼ as nav. */
    fun shouldPinVolume(ctx: Context): Boolean =
        volumeRocker(ctx) != ButtonPresence.ABSENT

    fun summarize(ctx: Context): String {
        fun label(p: ButtonPresence) = when (p) {
            ButtonPresence.UNKNOWN -> "untested"
            ButtonPresence.PRESENT -> "works"
            ButtonPresence.ABSENT -> "absent"
        }
        return "▲/▼ ${label(volumeRocker(ctx))} · ◀/▶ ${label(trackKeys(ctx))}"
    }

    private fun read(ctx: Context, key: String): ButtonPresence {
        val raw = BikeScope.getString(prefs(ctx), ctx, key, null) ?: return ButtonPresence.UNKNOWN
        return try {
            ButtonPresence.valueOf(raw)
        } catch (_: Exception) {
            ButtonPresence.UNKNOWN
        }
    }

    private fun write(ctx: Context, key: String, presence: ButtonPresence) {
        BikeScope.putString(prefs(ctx), ctx, key, presence.name)
    }
}
