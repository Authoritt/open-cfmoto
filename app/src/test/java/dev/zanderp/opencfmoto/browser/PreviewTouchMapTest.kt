// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewTouchMapTest {

    /** The preview keeps the canvas aspect, so the map is a pure scale. */
    @Test
    fun `centre maps to centre`() {
        assertEquals(512 to 232, PreviewTouchMap.toCanvas(256f, 116f, 512, 232, 1024, 464))
    }

    @Test
    fun `the top-left corner maps to the origin`() {
        assertEquals(0 to 0, PreviewTouchMap.toCanvas(0f, 0f, 512, 232, 1024, 464))
    }

    /** The last pixel must stay inside the canvas, not land one past the edge. */
    @Test
    fun `the bottom-right corner clamps to the last pixel`() {
        assertEquals(1023 to 463, PreviewTouchMap.toCanvas(512f, 232f, 512, 232, 1024, 464))
    }

    /**
     * Rejected, not clamped. Clamping would turn a stray touch on the bezel into a real tap on
     * whatever happens to sit at the canvas border.
     */
    @Test
    fun `a touch outside the preview is rejected rather than clamped`() {
        assertNull(PreviewTouchMap.toCanvas(-1f, 10f, 512, 232, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(10f, -1f, 512, 232, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(513f, 10f, 512, 232, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(10f, 233f, 512, 232, 1024, 464))
    }

    /** A SurfaceView reports 0x0 before layout; that must not divide by zero. */
    @Test
    fun `a zero-sized preview yields null instead of dividing by zero`() {
        assertNull(PreviewTouchMap.toCanvas(1f, 1f, 0, 232, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(1f, 1f, 512, 0, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(1f, 1f, 512, 232, 0, 464))
    }

    /** A preview larger than the canvas (big phone, small dash) must still map back down. */
    @Test
    fun `an upscaled preview maps back down`() {
        assertEquals(512 to 232, PreviewTouchMap.toCanvas(1024f, 464f, 2048, 928, 1024, 464))
    }

    /**
     * A preview TALLER than the canvas aspect gets black bars above and below, and the touch must be
     * measured from the image, not from the view.
     *
     * This is the case that shipped broken: aiming at Google Maps' zoom button (canvas y≈357) produced
     * y=259 on the device, because the map ignored the bars. 1024x464 fitted into 1024x664 leaves a
     * 100 px bar top and bottom.
     */
    @Test
    fun `black bars are discounted, not mapped through`() {
        // Dead centre of the view is dead centre of the image.
        assertEquals(512 to 232, PreviewTouchMap.toCanvas(512f, 332f, 1024, 664, 1024, 464))
        // The image starts 100 px down: that row is canvas y=0, not y=155.
        assertEquals(0 to 0, PreviewTouchMap.toCanvas(0f, 100f, 1024, 664, 1024, 464))
        // Three quarters down the IMAGE, which is not three quarters down the view.
        assertEquals(512 to 348, PreviewTouchMap.toCanvas(512f, 100f + 348f, 1024, 664, 1024, 464))
    }

    /** A touch on a black bar is not a touch on the page. */
    @Test
    fun `a touch on a letterbox bar is rejected`() {
        assertNull(PreviewTouchMap.toCanvas(512f, 40f, 1024, 664, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(512f, 620f, 1024, 664, 1024, 464))
    }

    /** Bars on the sides (a view wider than the canvas) work the same way. */
    @Test
    fun `side bars are discounted too`() {
        // 1024x464 fitted into 1424x464 leaves a 200 px bar left and right.
        assertEquals(0 to 0, PreviewTouchMap.toCanvas(200f, 0f, 1424, 464, 1024, 464))
        assertNull(PreviewTouchMap.toCanvas(50f, 232f, 1424, 464, 1024, 464))
    }

    /**
     * The axes must not be swapped.
     *
     * MEASURED, not assumed: with the two axes deliberately transposed in the implementation, **six of
     * the seven tests in this class still passed** — centre, origin, the bottom-right corner, the
     * out-of-bounds rejections, the zero-size guards and the upscaled case are all symmetric enough to
     * survive it. Only this asymmetric point caught it.
     *
     * That is the whole reason it exists: a transposed map on a 2.2:1 canvas still lands *inside* the
     * canvas, so it neither crashes nor looks wrong in a log — the dash just reacts somewhere the rider
     * did not touch. Do not delete this case for being redundant; it is the only one that is not.
     */
    @Test
    fun `the axes are not transposed`() {
        assertEquals(768 to 116, PreviewTouchMap.toCanvas(384f, 58f, 512, 232, 1024, 464))
    }
}
