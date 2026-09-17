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
