package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `a repeating run counts laps and knows where it is inside one`() {
        val first = MotionSamples.lapAt(7.0, lapSeconds = 5.0)
        assertEquals(1L, first.index)
        assertEquals(2.0, first.offsetSec, 1e-9)

        val atBoundary = MotionSamples.lapAt(10.0, lapSeconds = 5.0)
        assertEquals(2L, atBoundary.index)
        assertEquals(0.0, atBoundary.offsetSec, 1e-9)

        val noRepeat = MotionSamples.lapAt(7.0, lapSeconds = 0.0)
        assertEquals(0L, noRepeat.index)
        assertEquals(7.0, noRepeat.offsetSec, 1e-9)
    }

    @Test
    fun `distance and steps grow across laps instead of resetting`() {
        val lapMeters = samples.last().distanceMeters
        val lapSteps = samples.last().cumulativeSteps

        val oneLap = MotionSamples.afterLaps(samples[2], laps = 1L, lapMeters, lapSteps, tSec = 7.0)
        assertEquals(7.0, oneLap.tSec, 1e-9)
        assertEquals(samples[2].distanceMeters + lapMeters, oneLap.distanceMeters, 1e-9)
        assertEquals(samples[2].cumulativeSteps + lapSteps, oneLap.cumulativeSteps)
        assertEquals(samples[2].point, oneLap.point)

        // No laps completed means the sample is untouched.
        assertEquals(samples[2], MotionSamples.afterLaps(samples[2], laps = 0L, lapMeters, lapSteps, tSec = 2.0))
    }

    @Test
    fun `steps never go backwards across a lap boundary`() {
        val lapSeconds = 5.0
        val lapMeters = samples.last().distanceMeters
        val lapSteps = samples.last().cumulativeSteps
        var previous = 0L
        var t = 0.0
        while (t <= 25.0) {
            val lap = MotionSamples.lapAt(t, lapSeconds)
            val within = MotionSamples.at(samples, lap.offsetSec)!!
            val steps = MotionSamples.afterLaps(within, lap.index, lapMeters, lapSteps, t).cumulativeSteps
            assertTrue("steps dropped at t=$t: $previous -> $steps", steps >= previous)
            previous = steps
            t += 0.5
        }
    }
}
