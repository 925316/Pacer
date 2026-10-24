package org.pacer.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Micro-movement applied to a reported fix so the position does not sit exactly on the
 * line it is supposed to be following. Uses a small Ornstein-Uhlenbeck walk in a local
 * ENU frame, hard-clamped to [radiusMeters].
 *
 * Two callers, one reason. A stationary fix must not be pixel-identical across samples,
 * and a fix walking a route must not trace the polyline with zero width: a real receiver
 * is only accurate to within a few metres, so ten laps of an oval come back as a band a
 * metre wide, while perfect geometry comes back as one line drawn ten times. That band is
 * what a track has and a drawing does not.
 *
 * The walk's correlation time is 1/[reversion] and the amplitude is held independent of
 * it (the OU step is scaled by sqrt(2 * reversion)), so slowing the drift down does not
 * change how far the fix wanders. A slow drift is deliberate: a faster wobble is averaged
 * away by the fused provider and by a consumer's own smoothing and stops being visible at
 * all.
 *
 * Pure Kotlin, unit-testable: only math. The Android location provider and the route
 * simulator wrap this and convert back to lat/lon with the meters-per-degree factors.
 */
class PositionJitter(
    private val radiusMeters: Double = 1.5,
    private val reversion: Double = 0.1,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)
    private var x = 0.0
    private var y = 0.0

    /** Per-axis stationary std, kept constant when [reversion] changes. */
    private fun sigmaFor(radius: Double): Double = radius * PER_AXIS_SHARE * sqrt(2.0 * reversion)

    data class Offset(val eastMeters: Double, val northMeters: Double)

    /**
     * Returns an east/north offset (meters) for the current sample; call
     * repeatedly with the same time step [dtSec] to evolve the walk.
     *
     * [radiusMeters] overrides the constructor value for this sample. It exists for the
     * cold-start window: while the receiver still advertises a wide accuracy (tens of
     * metres) a fix drawn exactly on the line contradicts its own accuracy, and the
     * contradiction is what an audit reads. Widening it there removes the contradiction
     * without touching the steady state, where the constructor value applies again.
     *
     * The cap is deliberately NOT set to the advertised accuracy. A cap that matched it
     * would widen the walk to several metres for the whole run, and a slow wandering
     * offset of that size stretches a measured 3 km path - about 3.3 percent at a 5 m cap,
     * 4.8 at 6 m - trading the number the user actually reads for a tidier accuracy field.
     */
    fun next(dtSec: Double, radiusMeters: Double = this.radiusMeters): Offset {
        val radius = radiusMeters.coerceAtLeast(0.0)
        val sigma = sigmaFor(radius)
        // OU step for each axis.
        x += -reversion * x * dtSec + sigma * sqrt(dtSec) * rng.nextGaussian()
        y += -reversion * y * dtSec + sigma * sqrt(dtSec) * rng.nextGaussian()
        // Limit radius to keep it realistic.
        val r = sqrt(x * x + y * y)
        if (r > radius) {
            if (r > 1e-9) {
                x *= radius / r
                y *= radius / r
            } else {
                x = 0.0
                y = 0.0
            }
        }
        return Offset(x, y)
    }

    companion object {
        /**
         * Per-axis stationary std as a fraction of the cap. 0.335 puts the radial 68th
         * percentile at 0.52 of the cap, so a 1.5 m cap scatters by about 0.77 m.
         */
        const val PER_AXIS_SHARE = 0.335

        /**
         * How far the walk widens while the receiver is still cold, as a multiplier on
         * [STEADY_CAP_METERS].
         *
         * The defect being fixed: accuracy warmup reports 30 m at the start and settles at
         * the configured value (5 m by default), but the walk stayed at 1.5 m the whole
         * time, so a fix claiming 30 m of possible error was drawn on the line.
         *
         * A multiplier rather than a ratio of the claim, because the claim and the walk have
         * different jobs: the claim must reach tens of metres to look like a cold receiver,
         * while the walk must stay small enough that the fix does not contradict its own
         * reported speed. At 2.0 the largest one-second deviation is 2.05 m, which is 1.3x
         * the 1.6 m/s first step of the human-run preset; at 4.0 it would be 2.6x. Chosen
         * well short of that: the contradiction this removes is already gone at 2.0, so a
         * wider walk would only add a velocity the runner never had.
         */
        const val COLD_START_MULTIPLIER = 2.0

        /**
         * Cap for a fix that has settled.
         *
         * Steady state, not a target: [radiusForAccuracy] only ever returns this or the
         * cold-start widening above it, so a run whose accuracy has converged walks here
         * and nowhere else.
         */
        const val STEADY_CAP_METERS = 1.5

        /**
         * Cap for a fix taken while the reported accuracy is still above its steady value.
         *
         * [accuracyMeters] is what this fix is about to claim; [steadyAccuracyMeters] is
         * where that claim settles. Only the gap between them widens the walk, so a run
         * with accuracy warmup switched off walks at [STEADY_CAP_METERS] exactly as before.
         */
        fun radiusForAccuracy(accuracyMeters: Float, steadyAccuracyMeters: Float): Double {
            if (steadyAccuracyMeters <= 0f || accuracyMeters <= steadyAccuracyMeters) {
                return STEADY_CAP_METERS
            }
            val excess = accuracyMeters / steadyAccuracyMeters
            val scaled = STEADY_CAP_METERS * excess
            return scaled.coerceIn(STEADY_CAP_METERS, STEADY_CAP_METERS * COLD_START_MULTIPLIER)
        }
    }

    private fun Random.nextGaussian(): Double {
        var u: Double
        var v: Double
        do {
            u = nextDouble(); v = nextDouble()
        } while (u <= Double.MIN_VALUE)
        return sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)
    }
}
