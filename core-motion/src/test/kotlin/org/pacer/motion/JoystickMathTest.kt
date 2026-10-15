// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoystickMathTest {
    @Test
    fun `center yields zero`() {
        val v = JoystickMath.compute(0.0, 0.0, 3.0)
        assertEquals(0.0, v.magnitude, 0.0)
        assertEquals(0.0, v.vx, 0.0)
        assertEquals(0.0, v.vy, 0.0)
    }

    @Test
    fun `dead zone yields zero`() {
        val v = JoystickMath.compute(0.05, 0.05, 3.0)
        assertEquals(0.0, v.magnitude, 0.0)
    }

    @Test
    fun `full east yields max speed`() {
        val v = JoystickMath.compute(1.0, 0.0, 3.0)
        assertEquals(1.0, v.magnitude, 1e-9)
        assertEquals(3.0, v.vx, 1e-9)
        assertEquals(0.0, v.vy, 1e-9)
        assertEquals(0.0, v.angleDeg, 1e-6)
    }

    @Test
    fun `full north yields max speed`() {
        val v = JoystickMath.compute(0.0, 1.0, 3.0)
        assertEquals(1.0, v.magnitude, 1e-9)
        assertEquals(0.0, v.vx, 1e-9)
        assertEquals(3.0, v.vy, 1e-9)
        assertEquals(90.0, v.angleDeg, 1e-6)
    }

    @Test
    fun `magnitude clamps`() {
        val v = JoystickMath.compute(2.0, 3.0, 5.0)
        assertTrue(v.magnitude <= 1.0)
    }
}
