package org.pacer.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Micro-movement applied to a *stationary* GPS fix so the position does not
 * sit pixel-identical across samples. Uses a small Ornstein-Uhlenbeck walk in
 * a local ENU frame (~0.5 m standard deviation, hard-clamped to [radiusMeters])
 * plus a slow azimuth sweep of the wind-up.
 *
 * The walk's correlation time is 1/[reversion] and the amplitude is held
 * independent of it (the OU step is scaled by sqrt(2 * reversion)), so slowing
 * the drift down does not change how far the fix wanders. A slow drift is
 * deliberate: a faster wobble is averaged away by the fused provider and by a
 * consumer's own smoothing and stops being visible at all.
 *
 * Pure Kotlin, unit-testable: only math. Android location provider wraps this
 * and converts back to lat/lon with the meters-per-degree factors.
 */
class StationaryJitter(
    private val radiusMeters: Double = 1.5,
    private val reversion: Double = 0.1,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)
    private var x = 0.0
    private var y = 0.0

    /** Per-axis stationary std, kept constant when [reversion] changes. */
    private val sigma = radiusMeters * 0.335 * sqrt(2.0 * reversion)

    data class Offset(val eastMeters: Double, val northMeters: Double)

    /**
     * Returns an east/north offset (meters) for the current sample; call
     * repeatedly with the same time step [dtSec] to evolve the walk.
     */
    fun next(dtSec: Double): Offset {
        // OU step for each axis.
        x += -reversion * x * dtSec + sigma * sqrt(dtSec) * rng.nextGaussian()
        y += -reversion * y * dtSec + sigma * sqrt(dtSec) * rng.nextGaussian()
        // Limit radius to keep it realistic.
        val r = sqrt(x * x + y * y)
        if (r > radiusMeters) {
            x *= radiusMeters / r
            y *= radiusMeters / r
        }
        return Offset(x, y)
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
