package org.pacer.motion

import kotlin.math.abs

/**
 * Cross-checks the three simulated data streams (position, steps, accel) so a
 * run can be validated for physical self-consistency before it ever reaches a
 * hook or UI. Pure Kotlin, unit-testable.
 *
 * The invariants (Pacer's own acceptance thresholds):
 * 1. distance vs steps: steps * stride ~= distance (<= 5%)
 * 2. speed vs cadence: moving speed implies cadence inside [MIN, MAX] band
 * 3. step events vs curve: event count ~= integral of cadence over time (<= 12%).
 *    Note: expected events are integrated from cadence (the same source that
 *    drives the step stream), NOT from distance/stride. The two agree everywhere the
 *    reported cadence is the raw speed/stride value, and differ only where the band
 *    clamps it, which is below [MOVING_SPEED_MPS] at the bottom and above the speed
 *    implied by [StrideLinker.MAX_CADENCE_SPM] at the top.
 * 4. timestamps are monotonic across every stream
 */
object ConsistencyChecker {

    data class Report(
        val distanceStepError: Double,
        val movingCadenceViolations: Int,
        val stepEventError: Double,
        val timestampMonotonic: Boolean,
    ) {
        val ok: Boolean
            get() = distanceStepError <= 0.05 &&
                movingCadenceViolations == 0 &&
                stepEventError <= 0.12 &&
                timestampMonotonic
    }

    fun check(
        fixes: List<FixSample>,
        accel: List<SensorWaveGenerator.AccelSample>,
        stepEvents: List<SensorWaveGenerator.StepEvent>,
    ): Report {
        require(fixes.isNotEmpty()) { "no fixes" }
        val expectedSteps = expectedSteps(fixes)
        val actualSteps = fixes.last().cumulativeSteps
        val distanceStepError = if (expectedSteps == 0L) 1.0
        else abs(actualSteps - expectedSteps).toDouble() / expectedSteps

        var violations = 0
        for (f in fixes) {
            // The band check only applies once the subject is moving fast enough that the
            // cadence is read off the speed model rather than from the floor. Below
            // [MOVING_SPEED_MPS] the raw cadence is legitimately under the floor (a walk at
            // 0.8 m/s is about 84 SPM), so flagging it would report a violation for walking.
            if (f.speedMps > MOVING_SPEED_MPS) {
                if (f.cadenceSpm < StrideLinker.MIN_CADENCE_SPM || f.cadenceSpm > StrideLinker.MAX_CADENCE_SPM) {
                    violations++
                }
            }
        }

        val expectedEvents = cadenceIntegratesToSteps(fixes)
        val stepEventError = abs(stepEvents.size - expectedEvents).toDouble() / expectedEvents.coerceAtLeast(1)

        var monotonic = true
        for (i in 1 until fixes.size) if (fixes[i].tSec < fixes[i - 1].tSec) monotonic = false
        for (i in 1 until accel.size) if (accel[i].tNanos < accel[i - 1].tNanos) monotonic = false
        for (i in 1 until stepEvents.size) if (stepEvents[i].tNanos < stepEvents[i - 1].tNanos) monotonic = false

        return Report(distanceStepError, violations, stepEventError, monotonic)
    }

    /**
     * Expected cumulative steps for the run, integrating speed / stride(speed).
     * Mirrors StrideLinker so the checker validates the produced series rather
     * than re-deriving it from a constant stride.
     */
    private fun expectedSteps(fixes: List<FixSample>): Long {
        var acc = 0.0
        for (i in 1 until fixes.size) {
            val dt = fixes[i].tSec - fixes[i - 1].tSec
            val speed = (fixes[i].speedMps + fixes[i - 1].speedMps) / 2
            acc += speed * dt / StrideLinker.strideForSpeed(speed)
        }
        return acc.toLong()
    }

    /**
     * Expected step count as integral of cadence over time. Cadence is the
     * same source that drives the step-event stream, so this is the fair
     * baseline for comparing event counts against the speed curve.
     */
    private fun cadenceIntegratesToSteps(fixes: List<FixSample>): Long {
        var acc = 0.0
        for (i in 1 until fixes.size) {
            val dt = fixes[i].tSec - fixes[i - 1].tSec
            val avgCadence = (fixes[i].cadenceSpm + fixes[i - 1].cadenceSpm) / 2
            acc += avgCadence / 60.0 * dt
        }
        return acc.toLong().coerceAtLeast(1)
    }

    /**
     * Speed above which the cadence band applies.
     *
     * Must sit above the speed at which the stride taper lifts the raw cadence over
     * [StrideLinker.MIN_CADENCE_SPM] - that crossover is about 1.016 m/s - or the checker
     * would flag a walk for a cadence the model is right to produce. Kept at 1.1 for margin
     * rather than at the crossover itself, where floating point decides the answer.
     */
    private const val MOVING_SPEED_MPS = 1.1
}
