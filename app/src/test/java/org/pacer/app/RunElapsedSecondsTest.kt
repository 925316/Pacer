package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which clock the state line's elapsed figure comes from.
 *
 * The line used to compute a straight run from its steering anchor. Steering rewrites that
 * anchor on every touch move so the line of travel follows the finger, so the stopwatch
 * restarted dozens of times a second and the figure counted backwards while the user dragged
 * the joystick. The run had not restarted, so the figure must not either.
 */
class RunElapsedSecondsTest {

    private val start = 1_000_000L

    private fun straight(
        startedAtMs: Long = start,
        anchorAtMs: Long = start,
        nowMs: Long = start,
    ): Long = runElapsedSeconds(
        onRoute = false,
        startedAtMs = startedAtMs,
        anchorAtMs = anchorAtMs,
        routeOffsetMs = 0L,
        routeResumedAtMs = 0L,
        routePaused = false,
        nowMs = nowMs,
    )

    @Test
    fun `steering the anchor does not reset a straight run's clock`() {
        // The defect itself: a run seven seconds old, after the anchor moved to "now".
        assertEquals(7L, straight(anchorAtMs = start, nowMs = start + 7_000L))
        assertEquals(7L, straight(anchorAtMs = start + 4_000L, nowMs = start + 7_000L))
        assertEquals(7L, straight(anchorAtMs = start + 7_000L, nowMs = start + 7_000L))
    }

    @Test
    fun `a straight run's clock grows one second per second`() {
        val samples = (0..5).map { straight(nowMs = start + it * 1_000L) }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), samples)
    }

    @Test
    fun `a paused route is frozen at its stored offset`() {
        // A pause stores the route second reached; nothing advances it until the resume, and
        // a run frozen for a minute must not print a minute of elapsed time.
        val pausedAt = 42_000L
        assertEquals(
            42L,
            runElapsedSeconds(
                onRoute = true,
                startedAtMs = start,
                anchorAtMs = start,
                routeOffsetMs = pausedAt,
                routeResumedAtMs = start + 90_000L,
                routePaused = true,
                nowMs = start + 900_000L,
            ),
        )
    }

    @Test
    fun `a running route continues from the offset it was resumed at`() {
        // 30 s reached, paused, resumed 10 s after the start: at 15 s the answer is 35 s.
        assertEquals(
            35L,
            runElapsedSeconds(
                onRoute = true,
                startedAtMs = start,
                anchorAtMs = start,
                routeOffsetMs = 30_000L,
                routeResumedAtMs = start + 10_000L,
                routePaused = false,
                nowMs = start + 15_000L,
            ),
        )
    }

    @Test
    fun `a never resumed route falls back to the run's start`() {
        // A run started ten seconds ago has no pause history, so its resume stamp is zero.
        assertEquals(
            10L,
            runElapsedSeconds(
                onRoute = true,
                startedAtMs = start,
                anchorAtMs = start + 6_000L,
                routeOffsetMs = 0L,
                routeResumedAtMs = 0L,
                routePaused = false,
                nowMs = start + 10_000L,
            ),
        )
    }

    @Test
    fun `a missing start or a clock behind it never counts negative seconds`() {
        // A run is always stored with a start, but a table without one must not make the line
        // print the age since the epoch, which is what subtracting from 0 would do.
        assertEquals(0L, straight(startedAtMs = 0L, anchorAtMs = 0L, nowMs = start))
        // A wall clock that stepped backwards must not print a negative stopwatch.
        assertEquals(0L, straight(nowMs = start - 5_000L))
        assertEquals(0L, straight(nowMs = start))
    }
}
