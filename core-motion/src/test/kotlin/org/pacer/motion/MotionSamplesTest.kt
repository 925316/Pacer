package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lookup replaced a linear scan on a hot path, so it is pinned at the edges: the
 * empty list, before the first sample, between samples, exactly on a sample, and after
 * the last one.
 */
class MotionSamplesTest {

    private fun sample(tSec: Double) = FixSample(
        tSec = tSec,
        point = GeoPoint(1.0, 2.0),
        speedMps = 3.0,
        bearingDeg = 0f,
        distanceMeters = tSec * 3.0,
        phase = MotionPhase.CRUISE,
        accuracyMeters = 5f,
        altitudeMeters = 0.0,
        satellites = 9,
        signalDropout = false,
        cumulativeSteps = (tSec * 2).toLong(),
        cadenceSpm = 120.0,
    )

    private val samples = listOf(sample(0.0), sample(1.0), sample(2.0), sample(5.0))

    @Test
    fun `an empty run has no sample`() {
        assertNull(MotionSamples.at(emptyList(), 3.0))
    }

    @Test
    fun `before the first sample the first one answers`() {
        assertEquals(0.0, MotionSamples.at(samples, -10.0)!!.tSec, 1e-9)
    }

    @Test
    fun `a moment between samples answers with the one before it`() {
        assertEquals(2.0, MotionSamples.at(samples, 4.9)!!.tSec, 1e-9)
        assertEquals(1.0, MotionSamples.at(samples, 1.0)!!.tSec, 1e-9)
    }

    @Test
    fun `after the last sample the last one answers`() {
        assertEquals(5.0, MotionSamples.at(samples, 600.0)!!.tSec, 1e-9)
    }
}
