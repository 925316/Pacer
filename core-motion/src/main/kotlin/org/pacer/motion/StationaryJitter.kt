// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Micro-movement applied to a *stationary* GPS fix so the position does not
 * sit pixel-identical across samples. Uses a small Ornstein-Uhlenbeck walk in
 * a local ENU frame (~1-2 m radius) plus a slow azimuth sweep of the wind-up.
 *
 * Pure Kotlin, unit-testable: only math. Android location provider wraps this
 * and converts back to lat/lon with the meters-per-degree factors.
 */
class StationaryJitter(
    private val radiusMeters: Double = 1.5,
    private val reversion: Double = 0.4,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)
    private var x = 0.0
    private var y = 0.0

    data class Offset(val eastMeters: Double, val northMeters: Double)

    /**
     * Returns an east/north offset (meters) for the current sample; call
     * repeatedly with the same time step [dtSec] to evolve the walk.
     */
    fun next(dtSec: Double): Offset {
        // OU step for each axis.
        x += -reversion * x * dtSec + (radiusMeters * 0.3) * sqrt(dtSec) * rng.nextGaussian()
        y += -reversion * y * dtSec + (radiusMeters * 0.3) * sqrt(dtSec) * rng.nextGaussian()
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
