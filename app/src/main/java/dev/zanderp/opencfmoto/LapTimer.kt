// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flying-start / flying-finish lap timer for OpenCfMoto Map.
 * Arm at GPS, ride out, each later pass of the gate closes a lap and opens the next.
 * Not a Starlane clone (no sectors / beacons).
 */
class LapTimer {
    data class Gate(val lat: Double, val lon: Double)
    data class Hud(
        val on: Boolean,
        val waiting: Boolean,
        val lap: Int,
        val currentMs: Long,
        val lastMs: Long?,
        val bestMs: Long?,
        val finished: Int,
    )

    var gate: Gate? = null
        private set
    private var away = false
    private var lapStartMs = 0L
    private var lap = 0
    private var lastMs: Long? = null
    private var bestMs: Long? = null
    private var finished = 0
    private var lastCrossMs = 0L

    val isOn: Boolean get() = gate != null

    fun arm(lat: Double, lon: Double) {
        gate = Gate(lat, lon)
        away = false
        lapStartMs = 0L
        lap = 0
        lastMs = null
        bestMs = null
        finished = 0
        lastCrossMs = 0L
    }

    fun stop() {
        gate = null
        away = false
        lapStartMs = 0L
        lap = 0
        lastMs = null
        bestMs = null
        finished = 0
        lastCrossMs = 0L
    }

    /** Call on each GPS fix. Returns true when a lap just finished. */
    fun onFix(lat: Double, lon: Double, nowMs: Long): Boolean {
        val g = gate ?: return false
        val d = meters(lat, lon, g.lat, g.lon)
        if (d > EXIT_M) {
            away = true
            return false
        }
        if (!away || d > CROSS_M) return false
        if (lastCrossMs > 0L && nowMs - lastCrossMs < MIN_LAP_MS) return false
        away = false
        lastCrossMs = nowMs
        if (lapStartMs == 0L) {
            lapStartMs = nowMs
            lap = 1
            return false
        }
        val elapsed = (nowMs - lapStartMs).coerceAtLeast(0L)
        lastMs = elapsed
        bestMs = min(bestMs ?: elapsed, elapsed)
        finished += 1
        lapStartMs = nowMs
        lap = finished + 1
        return true
    }

    fun hud(nowMs: Long): Hud {
        val g = gate
        if (g == null) {
            return Hud(false, false, 0, 0L, null, null, 0)
        }
        val waiting = lapStartMs == 0L
        val current = if (waiting) 0L else (nowMs - lapStartMs).coerceAtLeast(0L)
        return Hud(true, waiting, lap, current, lastMs, bestMs, finished)
    }

    companion object {
        internal const val CROSS_M = 25.0
        internal const val EXIT_M = 55.0
        internal const val MIN_LAP_MS = 15_000L

        fun formatMs(ms: Long): String {
            val total = ms.coerceAtLeast(0L)
            val m = total / 60_000L
            val s = (total % 60_000L) / 1000L
            val cs = (total % 1000L) / 10L
            return String.format(Locale.US, "%d:%02d.%02d", m, s, cs)
        }

        fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(aLat)
            val p2 = Math.toRadians(bLat)
            val dp = Math.toRadians(bLat - aLat)
            val dl = Math.toRadians(bLon - aLon)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(min(1.0, sqrt(h)))
        }
    }
}
