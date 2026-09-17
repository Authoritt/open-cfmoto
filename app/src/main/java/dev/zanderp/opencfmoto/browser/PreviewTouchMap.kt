// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

/**
 * Phone-preview coordinates → bike-canvas coordinates.
 *
 * The preview is laid out at the canvas aspect ratio (letterboxed by the layout, not here), so this is
 * a pure scale. It lives apart from the UI for one reason: a wrong scale does not crash and does not
 * show up in a code review — it just puts every touch a few centimetres from where the rider aimed,
 * which you only discover by poking at a motorcycle dashboard and wondering why nothing responds.
 */
object PreviewTouchMap {

    /**
     * @return the canvas pixel under [previewX]/[previewY], or null when the preview has no size yet
     *   or the touch fell outside it. Null means "ignore", never "clamp to the edge": clamping would
     *   turn a stray touch on the bezel into a real tap on whatever sits at the canvas border.
     */
    fun toCanvas(
        previewX: Float,
        previewY: Float,
        previewW: Int,
        previewH: Int,
        canvasW: Int,
        canvasH: Int,
    ): Pair<Int, Int>? {
        if (previewW <= 0 || previewH <= 0 || canvasW <= 0 || canvasH <= 0) return null

        // The compositor draws the canvas FITTED and CENTRED into the preview surface, so unless the
        // two happen to share an aspect ratio there are black bars. Mapping through the whole view —
        // which this did at first — puts every touch off by the height of a bar: aiming at the zoom
        // button on Google Maps produced canvas y=259 where the button sits at y≈357, and nothing
        // ever responded. Measured, not guessed.
        val scale = minOf(previewW.toFloat() / canvasW, previewH.toFloat() / canvasH)
        val drawW = canvasW * scale
        val drawH = canvasH * scale
        val offX = (previewW - drawW) / 2f
        val offY = (previewH - drawH) / 2f

        val rx = previewX - offX
        val ry = previewY - offY
        // Outside the drawn image (i.e. on a black bar) is rejected, never clamped: a clamp would turn
        // a touch on the bezel into a real tap at the canvas edge.
        if (rx < 0f || ry < 0f || rx > drawW || ry > drawH) return null

        val x = (rx / drawW * canvasW).toInt().coerceIn(0, canvasW - 1)
        val y = (ry / drawH * canvasH).toInt().coerceIn(0, canvasH - 1)
        return x to y
    }
}
