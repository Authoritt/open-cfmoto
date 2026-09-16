package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapTimerTest {
    private val t = LapTimer()

    @Test
    fun flyingStartThenLap() {
        t.arm(45.0, 7.0)
        assertTrue(t.hud(0L).waiting)
        assertFalse(t.onFix(45.0, 7.0, 0L))
        // ~80 m north
        assertFalse(t.onFix(45.00072, 7.0, 5_000L))
        // back through the gate — clock starts, no finished lap yet
        assertFalse(t.onFix(45.0, 7.0, 10_000L))
        val started = t.hud(10_000L)
        assertFalse(started.waiting)
        assertEquals(1, started.lap)

        assertFalse(t.onFix(45.00072, 7.0, 20_000L))
        assertTrue(t.onFix(45.0, 7.0, 40_000L))
        val done = t.hud(40_000L)
        assertEquals(1, done.finished)
        assertEquals(30_000L, done.lastMs)
        assertEquals(30_000L, done.bestMs)
        assertEquals(2, done.lap)
    }

    @Test
    fun bounceAtGateDoesNotCount() {
        t.arm(45.0, 7.0)
        t.onFix(45.00072, 7.0, 1_000L)
        t.onFix(45.0, 7.0, 2_000L)
        t.onFix(45.00072, 7.0, 3_000L)
        assertFalse(t.onFix(45.0, 7.0, 4_000L))
        assertEquals(0, t.hud(4_000L).finished)
    }

    @Test
    fun formatMs() {
        assertEquals("0:01.50", LapTimer.formatMs(1_500L))
        assertEquals("1:02.03", LapTimer.formatMs(62_030L))
    }
}
