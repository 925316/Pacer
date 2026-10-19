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
    fun `step count roughly matches reported run steps`() {
        val s = curve()
        val run = Humanizer.simulate(MotionConfig.run(), distanceMeters = 800.0, seed = 5L)
        val (_, events) = SensorWaveGenerator(seed = 1L).generate(s)
        val expected = run.totalSteps
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
    fun `the same seed generates the same waveform`() {
        // The class takes a seed, so a rebuild from it must be reproducible. The step
        // schedule is where that can leak: cadence jitter taken from the process-wide
        // Random makes two generators with one seed produce different step times.
        val c = curve()
        val (accA, eventsA) = SensorWaveGenerator(seed = 7L).generate(c)
        val (accB, eventsB) = SensorWaveGenerator(seed = 7L).generate(c)
        assertEquals(eventsA, eventsB)
        assertEquals(accA, accB)
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

    // The live waveform is what the hook actually ships, so these assert the property
    // the app relies on rather than the internal shape.

    @Test
    fun `live waveform stays inside its amplitude plus jitter`() {
        val live = SensorWaveGenerator.Live(seed = 11L)
        var maxZ = 0.0
        var maxY = 0.0
        for (i in 0 until 2000) {
            val (_, ay, az) = live.at(i * 20L, cadenceSpm = 180.0)
            maxZ = maxOf(maxZ, kotlin.math.abs(az))
            maxY = maxOf(maxY, kotlin.math.abs(ay))
        }
        assertTrue("maxZ=$maxZ", maxZ <= SensorWaveGenerator.Z_AMPLITUDE + SensorWaveGenerator.JITTER + 1e-9)
        assertTrue("maxY=$maxY", maxY <= SensorWaveGenerator.Y_AMPLITUDE + SensorWaveGenerator.JITTER + 1e-9)
    }

    @Test
    fun `live waveform advances smoothly instead of jumping`() {
        val live = SensorWaveGenerator.Live(seed = 3L)
        // The first call only anchors the clock; the second is one millisecond later at
        // a running cadence, so it must land near the same phase.
        val first = live.at(0L, cadenceSpm = 170.0)
        val next = live.at(1L, cadenceSpm = 170.0)
        assertTrue(kotlin.math.abs(next.third - first.third) < 1.0)
    }

    @Test
    fun `live waveform is frozen without cadence`() {
        val live = SensorWaveGenerator.Live(seed = 3L)
        live.at(0L, cadenceSpm = 0.0)
        val still = (1..5).map { live.at(it * 1000L, cadenceSpm = 0.0) }
        // With no cadence the sine term stays at phase zero, so only jitter is left.
        assertTrue(still.all { kotlin.math.abs(it.third) <= SensorWaveGenerator.JITTER })
    }

    @Test
    fun `live waveform skips backwards time without corrupting the phase`() {
        val live = SensorWaveGenerator.Live(seed = 4L)
        live.at(10_000L, cadenceSpm = 170.0)
        val afterBackwards = live.at(9_000L, cadenceSpm = 170.0)
        assertTrue(kotlin.math.abs(afterBackwards.third) <= SensorWaveGenerator.Z_AMPLITUDE + SensorWaveGenerator.JITTER)
    }
}
