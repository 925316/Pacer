package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteClockTest {

    @Test
    fun `an unpaused run advances with the wall clock`() {
        assertEquals(12.5, RouteClock.secondsAt(0.0, 1_000L, 13_500L, paused = false), 1e-9)
    }

    @Test
    fun `a resumed run continues from the offset`() {
        // Paused at 100 s, resumed ten seconds ago: the run is at 110 s, not at 10 s.
        assertEquals(110.0, RouteClock.secondsAt(100.0, 1_000L, 11_000L, paused = false), 1e-9)
    }

    @Test
    fun `a paused run does not advance`() {
        val offset = RouteClock.offsetAtPause(100.0, 1_000L, 11_000L)
        assertEquals(110.0, offset, 1e-9)
        for (now in listOf(11_000L, 60_000L, 3_600_000L)) {
            assertEquals(
                "paused run moved at t=$now",
                offset,
                RouteClock.secondsAt(offset, 11_000L, now, paused = true),
                1e-9,
            )
        }
    }

    @Test
    fun `pausing twice does not compound the offset`() {
        // The second pause must use the clock as it stands, not add elapsed time to an
        // offset that was already frozen.
        val first = RouteClock.offsetAtPause(0.0, 0L, 10_000L)
        val second = RouteClock.offsetAtPause(first, 10_000L, 10_500L)
        assertEquals(10.0, second, 1e-9)
    }

    @Test
    fun `a resume stamped ahead of the wall clock holds the subject in place`() {
        // Rather than sending it backwards before the pause point.
        assertEquals(50.0, RouteClock.secondsAt(50.0, 10_000L, 9_000L, paused = false), 1e-9)
    }

    @Test
    fun `a negative offset is treated as the start`() {
        assertEquals(0.0, RouteClock.secondsAt(-5.0, 1_000L, 1_000L, paused = true), 1e-9)
    }

    @Test
    fun `a run without pause history measures from its start`() {
        assertEquals(7.5, RouteClock.secondsSinceStart(1_000L, 8_500L), 1e-9)
        assertEquals(0.0, RouteClock.secondsSinceStart(5_000L, 4_000L), 1e-9)
    }
}
