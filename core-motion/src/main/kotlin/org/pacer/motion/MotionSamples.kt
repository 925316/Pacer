package org.pacer.motion

import kotlin.math.floor

/**
 * Lookup into a simulated run's sample list, and the clock arithmetic of a run that
 * repeats itself.
 *
 * Separate from [RouteSimulator], which produces the list, because the hook side asks
 * this question on a hot path: a sensor callback fires tens of times a second, and a
 * linear scan over a run that is minutes long (one sample per second) is the difference
 * between a lookup and a stall on a device.
 */
object MotionSamples {

    /**
     * The last sample at or before [tSec], or null when [samples] is empty.
     *
     * Relies on the list being in time order, which is how [RouteSimulator] builds it:
     * a run reports the most recent fix it has, so a moment between samples answers with
     * the sample that preceded it.
     */
    fun at(samples: List<FixSample>, tSec: Double): FixSample? {
        if (samples.isEmpty()) return null
        if (tSec <= samples.first().tSec) return samples.first()
        var lo = 0
        var hi = samples.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (samples[mid].tSec <= tSec) lo = mid else hi = mid - 1
        }
        return samples[lo]
    }

    /**
     * Where a repeating run's clock is: which lap, and how far into it.
     *
     * A pure function of the clock and one lap's length, and separate from [at] because
     * the caller has to know the lap BEFORE it can build that lap's samples: each lap is
     * humanized with its own noise, so no two laps of one run are the same track. Two
     * laps that repeated exactly would be the easiest thing a detector could find, and
     * the reason this feature exists at all is to look like a person going round again.
     *
     * [lapSeconds] of 0 (or less) means "not repeating": lap 0, offset [tSec].
     */
    fun lapAt(tSec: Double, lapSeconds: Double): Lap {
        val elapsed = tSec.coerceAtLeast(0.0)
        if (lapSeconds <= 0.0) return Lap(0L, elapsed)
        val index = floor(elapsed / lapSeconds).toLong()
        return Lap(index, elapsed - index * lapSeconds)
    }

    /** Which lap a moment belongs to, and its offset inside that lap. */
    data class Lap(val index: Long, val offsetSec: Double)

    /**
     * The sample a moment gets in a repeating run: one lap's own answer, with every
     * completed lap added to the distance and the step count.
     *
     * Those two have to keep growing rather than reset with the lap: someone who has run
     * three laps has covered all three, and a step counter that went back to zero at
     * every lap boundary is a signature no real device produces.
     */
    fun afterLaps(
        sample: FixSample,
        laps: Long,
        lapMeters: Double,
        lapSteps: Long,
        tSec: Double,
    ): FixSample {
        if (laps <= 0L) return sample
        return sample.copy(
            tSec = tSec,
            distanceMeters = sample.distanceMeters + lapMeters * laps,
            cumulativeSteps = sample.cumulativeSteps + lapSteps * laps,
        )
    }
}
