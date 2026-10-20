package org.pacer.motion

/**
 * The clock of a route run, which a pause has to be able to stop.
 *
 * A route run is a pure function of "seconds into the run": position, speed, steps,
 * cadence and the lap all come from one sample. Pausing therefore needs no stored
 * position and no second engine - only a clock that stops, and a resume that continues
 * from the same route second. Because every reported field is read from that one
 * sample, a pause cannot make distance and steps disagree with the coordinates; the
 * subject simply stands at the pause point with the run's own step count.
 *
 * The run's start timestamp is deliberately NOT rebased on resume. It seeds the
 * humanized track, so changing it would rebuild the run with different noise and move
 * the subject; it also identifies the run to the step counter, whose base must survive
 * a pause or the count would jump.
 */
object RouteClock {

    /**
     * Seconds into the route run at [nowMs].
     *
     * [offsetSec] is the route second at which the last pause or resume happened;
     * [resumedAtMs] is the wall clock of that resume. While paused the answer is
     * [offsetSec] for every [nowMs], which freezes the lookup instead of accumulating.
     *
     * Negative elapsed time (a wall clock that moved backwards, or a resume stamped in
     * the future) clamps to the offset: the subject waits at the pause point rather than
     * being sent back before it.
     */
    fun secondsAt(offsetSec: Double, resumedAtMs: Long, nowMs: Long, paused: Boolean): Double {
        if (paused) return offsetSec.coerceAtLeast(0.0)
        val elapsed = (nowMs - resumedAtMs).coerceAtLeast(0L) / 1000.0
        return offsetSec.coerceAtLeast(0.0) + elapsed
    }

    /**
     * The offset to store when pausing at [nowMs]: the route second the run has reached.
     *
     * A function rather than `secondsAt(..., paused = false)` at the call site so the
     * pause and the freeze cannot use different arithmetic.
     */
    fun offsetAtPause(offsetSec: Double, resumedAtMs: Long, nowMs: Long): Double =
        secondsAt(offsetSec, resumedAtMs, nowMs, paused = false)

    /**
     * The route second a run has reached, from a run that only knows its start.
     *
     * The default run has no pause history: the offset is zero and the clock is the
     * start timestamp. Reading it through this function rather than subtracting by hand
     * keeps the app and the module on one formula.
     */
    fun secondsSinceStart(startedAtMs: Long, nowMs: Long): Double =
        ((nowMs - startedAtMs).coerceAtLeast(0L)) / 1000.0
}
