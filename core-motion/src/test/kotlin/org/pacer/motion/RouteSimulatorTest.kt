// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {

    private fun sim(seed: Long = 42L) = RouteSimulator(
        route = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01))),
        config = MotionConfig.run(),
    ).simulate(dtSec = 1.0, seed = seed)

    @Test
    fun `samples are ordered in time`() {
        val out = sim()
        assertTrue(out.size > 2)
        for (i in 1 until out.size) {
            assertTrue(out[i].tSec > out[i - 1].tSec)
        }
    }

    @Test
    fun `cumulative distance reaches route end`() {
        val out = sim()
        assertEquals(0.0, out.first().distanceMeters, 1e-9)
        val last = out.last().distanceMeters
        assertTrue(last in 0.99..1.01)
    }

    @Test
    fun `cumulative steps are monotonic`() {
        val out = sim()
        for (i in 1 until out.size) {
            assertTrue(out[i].cumulativeSteps >= out[i - 1].cumulativeSteps)
        }
        assertTrue(out.last().cumulativeSteps > 0)
    }

    @Test
    fun `points stay on route bounds`() {
        val out = sim()
        val minLat = out.minOf { it.point.lat }
        val maxLat = out.maxOf { it.point.lat }
        assertTrue(minLat in -0.0001..0.0001)
        assertTrue(maxLat in -0.0001..0.0001)
    }

    @Test
    fun `different seeds give different curves`() {
        val a = sim(1L)
        val b = sim(2L)
        val diff = a.zip(b).count { (x, y) -> kotlin.math.abs(x.speedMps - y.speedMps) > 1e-6 }
        assertTrue(diff > 0)
    }

    @Test
    fun `cadence is zero when paused`() {
        val out = sim()
        val paused = out.filter { it.phase == MotionPhase.PAUSE }
        paused.forEach { assertEquals(0.0, it.cadenceSpm, 1e-9) }
    }
}
