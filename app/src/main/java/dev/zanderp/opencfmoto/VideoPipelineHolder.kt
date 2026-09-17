// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * The live browser pipeline, for UI that must drive it without owning it.
 *
 * The control screen on the phone outlives no pipeline and owns none: it composes and recomposes while
 * connections come and go. Set when the browser presentation comes up and cleared on stop, so a screen
 * composed after a disconnect gets `null` instead of a destroyed WebView.
 */
object VideoPipelineHolder {

    @Volatile private var pipeline: VideoPipeline? = null

    internal fun set(p: VideoPipeline?) {
        pipeline = p
    }

    fun browser(): android.webkit.WebView? = pipeline?.browserView

    fun compositor(): AaCompositor? = pipeline?.browserCompositor

    /**
     * The canvas the browser is actually laid out at, straight from the live pipeline.
     *
     * Deliberately NOT from `BikeProfileHolder`: the profile carries no canvas size, and the bike
     * negotiates its dimensions at connect time, so the pipeline is the only place that knows them.
     */
    fun canvasSize(): Pair<Int, Int>? = pipeline?.let { it.canvasWidth to it.canvasHeight }
}
