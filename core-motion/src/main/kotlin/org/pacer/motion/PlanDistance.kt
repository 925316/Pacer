package org.pacer.motion

/**
 * Where a straight run is at a moment, under whichever plan shape is configured.
 *
 * A timed [SpeedPlan] answers "how fast at this moment" and a [PacePlan] answers "how fast at
 * this place"; both travel in one stored key, so every caller that picks between them is a
 * second copy of the choice. Two such copies existed and drifted - both read the timed plan,
 * only one read the curve - so a per-kilometre session moved at the flat speed.
 */
object PlanDistance {

    /**
     * Distance covered and the speed that produced it, at one moment.
     *
     * Both answers come out of one call because they belong to the same instant: a caller that
     * could pass a distance from one moment and a time from another would report a speed its
     * own displacement contradicts.
     */
    data class Step(val meters: Double, val speedMps: Double)

    fun at(
        pace: PacePlan?,
        timed: SpeedPlan?,
        elapsedSec: Double,
        flatSpeedMps: Double,
    ): Step {
        val meters = pace?.metersAt(elapsedSec)
            ?: timed?.metersAt(elapsedSec)
            ?: (flatSpeedMps * elapsedSec)
        // The curve is read at the distance and the timed plan at the clock, so whichever shape
        // is set answers for this metre rather than for another one.
        val speed = pace?.speedAtDistance(meters)
            ?: timed?.speedAt(elapsedSec)
            ?: flatSpeedMps
        return Step(meters, speed)
    }
}
