// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
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
 *    drives the step stream), NOT from distance/stride, because the constant
 *    stride model and the clamped cadence band disagree at high running
 *    speeds (3 m/s at 0.7 m stride implies 257 SPM, clamped to 220).
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
        strideMeters: Double = StrideLinker.DEFAULT_STRIDE_METERS,
    ): Report {
        require(fixes.isNotEmpty()) { "no fixes" }
        val distance = fixes.last().distanceMeters
        val steps = fixes.last().cumulativeSteps
        val distanceStepError = if (steps == 0L) 1.0 else abs(steps * strideMeters - distance) / distance.coerceAtLeast(1e-9)

        var violations = 0
        for (f in fixes) {
            if (f.speedMps > 0.5) {
                if (f.cadenceSpm < StrideLinker.MIN_CADENCE_SPM || f.cadenceSpm > StrideLinker.MAX_CADENCE_SPM) {
                    violations++
                }
            }
        }

        val expectedEvents = cadenceIntegratesToSteps(fixes, strideMeters)
        val stepEventError = abs(stepEvents.size - expectedEvents).toDouble() / expectedEvents.coerceAtLeast(1)

        var monotonic = true
        for (i in 1 until fixes.size) if (fixes[i].tSec < fixes[i - 1].tSec) monotonic = false
        for (i in 1 until accel.size) if (accel[i].tNanos < accel[i - 1].tNanos) monotonic = false
        for (i in 1 until stepEvents.size) if (stepEvents[i].tNanos < stepEvents[i - 1].tNanos) monotonic = false

        return Report(distanceStepError, violations, stepEventError, monotonic)
    }

    /**
     * Expected step count as integral of cadence over time. Cadence is the
     * same source that drives the step-event stream, so this is the fair
     * baseline for comparing event counts against the speed curve.
     */
    private fun cadenceIntegratesToSteps(fixes: List<FixSample>, strideMeters: Double): Long {
        var acc = 0.0
        for (i in 1 until fixes.size) {
            val dt = fixes[i].tSec - fixes[i - 1].tSec
            val avgCadence = (fixes[i].cadenceSpm + fixes[i - 1].cadenceSpm) / 2
            acc += avgCadence / 60.0 * dt
        }
        return acc.toLong().coerceAtLeast(1)
    }
}
