// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FrequencyEstimatorTest {

    @Test
    fun `detects 2Hz sine`() {
        val n = 500
        val hz = 2.0
        val vals = DoubleArray(n) { i -> sin(2 * PI * hz * i / 25.0) }
        val spm = FrequencyEstimator.zeroCrossingsPerMinute(25, vals)
        assertTrue("spm=$spm", spm in 110.0..130.0)
    }

    @Test
    fun `correlation peaks near carrier frequency`() {
        val n = 500
        val hz = 2.0
        val vals = DoubleArray(n) { i -> sin(2 * PI * hz * i / 25.0) }
        val stronger = FrequencyEstimator.correlationHz(25, vals, 2.0)
        val weaker = FrequencyEstimator.correlationHz(25, vals, 7.0)
        assertTrue(stronger > weaker)
    }

    @Test
    fun `sensor wave contains low-frequency component`() {
        val curve = Humanizer.simulate(MotionConfig.run(), distanceMeters = 500.0, seed = 9L).samples
        val (acc, _) = SensorWaveGenerator(seed = 9L).generate(curve, sampleHz = 25)
        val vals = acc.map { it.az }.toDoubleArray()
        val spm = FrequencyEstimator.zeroCrossingsPerMinute(25, vals)
        // 3 m/s run ~= 220 SPM cap -> ~3.67 Hz. Just ensure non-trivial (not dc, not noise).
        assertTrue("spm=$spm", spm in 40.0..320.0)
    }

    @Test
    fun `empty input yields zero`() {
        assertEquals(0.0, FrequencyEstimator.zeroCrossingsPerMinute(25, doubleArrayOf()), 0.0)
    }
}
