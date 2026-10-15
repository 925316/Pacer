// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure joystick -> velocity mapping for the floating joystick (M0 feature).
 * Angle in degrees (0 = right/east, 90 = up/north). Magnitude clamped to
 * [0,1]; dead-zone applied near center.
 */
object JoystickMath {

    const val DEAD_ZONE = 0.12

    data class Velocity(val vx: Double, val vy: Double, val magnitude: Double, val angleDeg: Double)

    fun compute(x: Double, y: Double, maxSpeedMps: Double): Velocity {
        val m = sqrt(x * x + y * y).coerceIn(0.0, 1.0)
        val mag = if (m < DEAD_ZONE) 0.0 else ((m - DEAD_ZONE) / (1 - DEAD_ZONE)).coerceIn(0.0, 1.0)
        val angle = Math.toDegrees(atan2(y, x)) // x=east, y=north
        val normAngle = if (angle < 0) angle + 360 else angle
        val vx = mag * cos(Math.toRadians(normAngle)) * maxSpeedMps
        val vy = mag * sin(Math.toRadians(normAngle)) * maxSpeedMps
        return Velocity(vx, vy, mag, normAngle)
    }
}
