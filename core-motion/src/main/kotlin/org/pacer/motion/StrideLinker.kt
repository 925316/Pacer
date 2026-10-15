package org.pacer.motion

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Links speed to step cadence with a physically consistent triangle:
 * steps = distance / stride, cadence = speed / stride.
 *
 * Original Pacer implementation; numeric bands below are our own choices.
 */
object StrideLinker {
    const val DEFAULT_STRIDE_METERS = 0.7
    const val MIN_CADENCE_SPM = 140.0
    const val MAX_CADENCE_SPM = 220.0
    const val CADENCE_JITTER_SPM = 5.0

    /**
     * Total steps for a distance, rounded to whole steps.
     */
    fun stepsForDistance(distanceMeters: Double, strideMeters: Double = DEFAULT_STRIDE_METERS): Long {
        require(distanceMeters >= 0) { "distanceMeters must be non-negative" }
        require(strideMeters > 0) { "strideMeters must be positive" }
        return (distanceMeters / strideMeters).roundToLong()
    }

    /**
     * Instantaneous cadence (steps/min) for a speed, with small random jitter,
     * clamped into [MIN_CADENCE_SPM, MAX_CADENCE_SPM]. Zero speed yields zero.
     */
    fun cadenceForSpeed(
        speedMps: Double,
        strideMeters: Double = DEFAULT_STRIDE_METERS,
        rng: Random = Random,
    ): Double {
        require(speedMps >= 0) { "speedMps must be non-negative" }
        if (speedMps <= 0.05) return 0.0
        val raw = speedMps / strideMeters * 60.0
        val jittered = raw + (rng.nextDouble() * 2 - 1) * CADENCE_JITTER_SPM
        return jittered.coerceIn(MIN_CADENCE_SPM, MAX_CADENCE_SPM)
    }

    /**
     * Cumulative step counts matching a speed curve sample-by-sample.
     * Returns a list parallel to [samples].
     */
    fun cumulativeSteps(
        samples: List<SpeedSample>,
        strideMeters: Double = DEFAULT_STRIDE_METERS,
    ): List<Long> {
        var acc = 0.0
        return samples.mapIndexed { idx, s ->
            if (idx == 0) {
                0L
            } else {
                val dt = s.tSec - samples[idx - 1].tSec
                acc += s.speedMps * dt / strideMeters
                acc.roundToLong()
            }
        }
    }
}
