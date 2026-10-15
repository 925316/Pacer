package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorWaveGeneratorTest {

    private fun curve(): List<SpeedSample> =
        Humanizer.simulate(MotionConfig.run(), distanceMeters = 800.0, seed = 5L).samples

    @Test
    fun `accel timestamps are monotonic`() {
        val (acc, _) = SensorWaveGenerator(seed = 1L).generate(curve(), baseTimeNanos = 1_000_000_000L)
        assertTrue(acc.size > 100)
        for (i in 1 until acc.size) {
            assertTrue(acc[i].tNanos > acc[i - 1].tNanos)
        }
    }

    @Test
    fun `step events are monotonic and positive count`() {
        val (_, events) = SensorWaveGenerator(seed = 1L).generate(curve())
        assertTrue(events.isNotEmpty())
        for (i in 1 until events.size) {
            assertTrue(events[i].tNanos >= events[i - 1].tNanos)
        }
    }

    @Test
    fun `step count roughly matches distance over stride`() {
        val s = curve()
        val run = Humanizer.simulate(MotionConfig.run(), distanceMeters = 800.0, seed = 5L)
        val (_, events) = SensorWaveGenerator(seed = 1L).generate(s)
        val expected = StrideLinker.stepsForDistance(run.totalDistanceMeters)
        val ratio = events.size.toDouble() / expected.coerceAtLeast(1)
        assertTrue("events=${events.size} expected=$expected ratio=$ratio", ratio in 0.8..1.2)
    }

    @Test
    fun `accel amplitude stays bounded`() {
        val (acc, _) = SensorWaveGenerator(seed = 1L).generate(curve())
        val maxAz = acc.maxOf { kotlin.math.abs(it.az) }
        assertTrue("maxAz=$maxAz", maxAz < 5.5)
        val maxAy = acc.maxOf { kotlin.math.abs(it.ay) }
        assertTrue("maxAy=$maxAy", maxAy < 3.0)
    }

    @Test
    fun `empty curve yields empty outputs`() {
        val (acc, events) = SensorWaveGenerator(seed = 1L).generate(emptyList())
        assertTrue(acc.isEmpty() && events.isEmpty())
    }

    @Test
    fun `sample rate controls output density`() {
        val c = curve()
        val (low, _) = SensorWaveGenerator(seed = 1L).generate(c, sampleHz = 20)
        val (high, _) = SensorWaveGenerator(seed = 1L).generate(c, sampleHz = 50)
        assertTrue(high.size > low.size * 2)
        assertEquals(0L, low.first().tNanos)
    }
}
