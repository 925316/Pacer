package org.pacer.motion

/**
 * Lookup into a simulated run's sample list.
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
}
