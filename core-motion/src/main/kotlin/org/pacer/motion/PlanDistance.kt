package org.pacer.motion

/**
 * The one place that decides which plan shape a straight run is driven by.
 *
 * A timed [SpeedPlan] answers "how fast at this moment" and a [PacePlan] answers "how fast at
 * this place"; both travel in one stored key, so every caller that picks between them is a
 * second copy of the choice. Two such copies already existed and drifted - both read the
 * timed plan, only one read the curve - so a per-kilometre session moved at the flat speed.
 */
object PlanDistance {

    /** Metres covered after [elapsedSec]; a pace curve supplies its integral's inverse. */
    fun metersAt(
        pace: PacePlan?,
        timed: SpeedPlan?,
        elapsedSec: Double,
        speedMps: Double,
    ): Double = pace?.metersAt(elapsedSec)
        ?: timed?.metersAt(elapsedSec)
        ?: (speedMps * elapsedSec)

    /**
     * Speed at the moment that reached [meters].
     *
     * Both arguments name one moment: the curve is read at the distance, the timed plan at the
     * clock. Reading a distance-indexed curve by time would report the speed of another metre.
     */
    fun speedAt(
        pace: PacePlan?,
        timed: SpeedPlan?,
        elapsedSec: Double,
        meters: Double,
        speedMps: Double,
    ): Double = pace?.speedAtDistance(meters)
        ?: timed?.speedAt(elapsedSec)
        ?: speedMps
}
