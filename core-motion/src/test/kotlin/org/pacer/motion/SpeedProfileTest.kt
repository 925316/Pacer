// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeedProfileTest {

    @Test
    fun `run starts at zero and reaches target distance`() {
        val run = Humanizer.simulate(MotionConfig.run(), distanceMeters = 1000.0, seed = 1L)
        assertEquals(0.0, run.samples.first().speedMps, 1e-9)
        assertEquals(1000.0, run.totalDistanceMeters, 1e-6)
        assertTrue(run.samples.size > 10)
    }

    @Test
    fun `speeds are never negative`() {
        val run = Humanizer.simulate(MotionConfig.walk(), distanceMeters = 400.0, seed = 2L)
        assertTrue(run.samples.all { it.speedMps >= 0 })
    }

    @Test
    fun `contains acquire ramp and at least cruise`() {
        val run = Humanizer.simulate(MotionConfig.run(), distanceMeters = 2000.0, seed = 3L)
        val phases = run.samples.map { it.phase }.toSet()
        assertTrue(phases.contains(MotionPhase.ACQUIRE))
        assertTrue(phases.contains(MotionPhase.CRUISE))
    }

    @Test
    fun `different seeds diverge`() {
        val a = Humanizer.simulate(MotionConfig.run(), distanceMeters = 1000.0, seed = 11L)
        val b = Humanizer.simulate(MotionConfig.run(), distanceMeters = 1000.0, seed = 22L)
        val diff = a.samples.zip(b.samples).count { (x, y) ->
            kotlin.math.abs(x.speedMps - y.speedMps) > 1e-6
        }
        assertTrue("expected divergent curves, got $diff differing samples", diff > 10)
    }

    @Test
    fun `distance speed steps triangle stays physically plausible`() {
        val config = MotionConfig.run()
        val run = Humanizer.simulate(config, distanceMeters = 3000.0, seed = 7L)
        // Average stride = distance / steps must sit in the human band, and the
        // step series must be monotonic.
        val avgStride = run.totalDistanceMeters / run.totalSteps.coerceAtLeast(1)
        assertTrue("avgStride=$avgStride", avgStride in 0.65..1.35)
        for (i in 1 until run.cumulativeSteps.size) {
            assertTrue(run.cumulativeSteps[i] >= run.cumulativeSteps[i - 1])
        }
    }

    @Test
    fun `stride grows with speed`() {
        assertTrue(StrideLinker.strideForSpeed(1.0) < StrideLinker.strideForSpeed(3.0))
        assertTrue(StrideLinker.strideForSpeed(3.0) < StrideLinker.strideForSpeed(6.0))
        // A 3 m/s run must not clamp at the cadence ceiling anymore.
        val cadence = StrideLinker.cadenceForSpeed(3.0, rng = Random(1L))
        assertTrue("cadence=$cadence", cadence < StrideLinker.MAX_CADENCE_SPM)
    }

    @Test
    fun `cadence stays in human band when moving`() {
        val rng = Random(9L)
        val c = StrideLinker.cadenceForSpeed(3.0, rng = rng)
        assertTrue(c in StrideLinker.MIN_CADENCE_SPM..StrideLinker.MAX_CADENCE_SPM)
        assertEquals(0.0, StrideLinker.cadenceForSpeed(0.0, rng = rng), 0.0)
    }

    @Test
    fun `signal realism warms up and holds bearing when still`() {
        val sim = SignalRealism(seed = 5L)
        val cold = sim.next(0.0, 3.0, 1.0, 90f)
        val warm = sim.next(60.0, 3.0, 1.0, 90f)
        assertTrue(warm.accuracyMeters < cold.accuracyMeters)
        val still = sim.next(61.0, 0.0, 1.0, 180f)
        assertEquals(90f, still.bearingDeg, 1e-6f)
        assertTrue(cold.satellites in 3..14)
    }
}
