// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertTrue
import org.junit.Test

class ConsistencyCheckerTest {

    private fun fullRun(): Triple<List<FixSample>, List<SensorWaveGenerator.AccelSample>, List<SensorWaveGenerator.StepEvent>> {
        val route = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.008)))
        val fixes = RouteSimulator(route, MotionConfig.run()).simulate(dtSec = 1.0, seed = 7L)
        val curve = Humanizer.simulate(MotionConfig.run(), route.totalLengthMeters, seed = 7L).samples
        val (acc, events) = SensorWaveGenerator(seed = 7L).generate(curve)
        return Triple(fixes, acc, events)
    }

    @Test
    fun `full run passes consistency`() {
        val (fixes, acc, events) = fullRun()
        val report = ConsistencyChecker.check(fixes, acc, events)
        assertTrue("distanceStepError=${report.distanceStepError}", report.distanceStepError <= 0.05)
        assertTrue("violations=${report.movingCadenceViolations}", report.movingCadenceViolations == 0)
        assertTrue("stepEventError=${report.stepEventError}", report.stepEventError <= 0.12)
        assertTrue("monotonic=${report.timestampMonotonic}", report.timestampMonotonic)
        assertTrue(report.ok)
    }

    @Test
    fun `report flags broken monotonicity`() {
        val (fixes, acc, events) = fullRun()
        val tampered = acc.toMutableList()
        tampered.add(1, tampered[0].copy(tNanos = tampered[0].tNanos - 1))
        val report = ConsistencyChecker.check(fixes, tampered, events)
        assertTrue(!report.timestampMonotonic)
        assertTrue(!report.ok)
    }
}
