// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import kotlin.math.abs
import kotlin.math.min

/**
 * Tuning thresholds for [TurnModel.brakingFactor].
 *
 * @param smallTurnDeg heading change at or below which no braking is applied
 * @param largeTurnDeg heading change at or above which the floor is reached
 * @param minFactor lowest speed multiplier the model will return
 */
data class TurnThresholds(
    val smallTurnDeg: Double = 20.0,
    val largeTurnDeg: Double = 90.0,
    val minFactor: Double = 0.45,
)

/**
 * Turn braking model. Real runners slow before a sharp corner, so the
 * simulated speed is scaled down as the heading change between the incoming
 * and outgoing bearings grows. Original Pacer implementation; pure math and
 * independent of any map or route library.
 */
object TurnModel {

    /**
     * Smallest angle between two bearings, in [0, 180] degrees. Inputs need
     * not be normalised, so negative and > 360 values are accepted.
     */
    fun headingChangeDegrees(bearingIn: Double, bearingOut: Double): Double {
        val delta = abs(bearingIn - bearingOut) % 360.0
        return min(delta, 360.0 - delta)
    }

    /**
     * Speed multiplier in (0, 1] for a turn of [headingChangeDeg] degrees.
     *
     * Gentle turns (at or below [TurnThresholds.smallTurnDeg]) keep a factor of
     * 1.0; the factor then falls linearly and reaches the configured floor at
     * [TurnThresholds.largeTurnDeg] and beyond. The result is monotonically
     * non-increasing in the turn magnitude.
     */
    fun brakingFactor(
        headingChangeDeg: Double,
        thresholds: TurnThresholds = TurnThresholds(),
    ): Double {
        // A degenerate threshold pair cannot be interpolated; fall back to a step.
        if (thresholds.largeTurnDeg <= thresholds.smallTurnDeg) {
            return if (headingChangeDeg >= thresholds.largeTurnDeg) floorOf(thresholds) else 1.0
        }
        val turn = headingChangeDeg
        if (turn <= thresholds.smallTurnDeg) return 1.0
        if (turn >= thresholds.largeTurnDeg) return floorOf(thresholds)
        val k = (turn - thresholds.smallTurnDeg) /
            (thresholds.largeTurnDeg - thresholds.smallTurnDeg)
        return 1.0 + (floorOf(thresholds) - 1.0) * k
    }

    private fun floorOf(thresholds: TurnThresholds): Double =
        thresholds.minFactor.coerceIn(1e-6, 1.0)
}
