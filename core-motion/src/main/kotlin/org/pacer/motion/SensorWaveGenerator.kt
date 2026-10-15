package org.pacer.motion

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic accelerometer waveform and step-event stream linked to cadence.
 *
 * Original Pacer design. The physics idea: while running, the body bounces at
 * step frequency, so accelerometer Z axis is dominated by a sine at
 * cadence/60 Hz; Y axis carries a smaller lateral sway at the same frequency;
 * everything gets small random jitter. Step detector fires once per step,
 * step counter accumulates.
 *
 * The numeric bands below are Pacer's own choices:
 * - Z amplitude 3.5 m/s^2, Y amplitude 1.5 m/s^2
 * - jitter +/-0.15 m/s^2
 */
class SensorWaveGenerator(
    private val strideMeters: Double = StrideLinker.DEFAULT_STRIDE_METERS,
    private val zAmplitude: Double = 3.5,
    private val yAmplitude: Double = 1.5,
    private val jitter: Double = 0.15,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)

    /** One accelerometer sample: axis accelerations in m/s^2 plus timestamps. */
    data class AccelSample(
        val tNanos: Long,
        val ax: Double,
        val ay: Double,
        val az: Double,
    )

    /** One step-detector event. */
    data class StepEvent(
        val tNanos: Long,
    )

    /**
     * Generate accelerometer samples and step events for a speed curve.
     *
     * @param samples speed curve from [SpeedProfile]/[Humanizer]
     * @param sampleHz accelerometer sampling rate, typical 20..50 Hz
     * @param baseTimeNanos starting timestamp (e.g. SystemClock.elapsedRealtimeNanos)
     */
    fun generate(
        samples: List<SpeedSample>,
        sampleHz: Int = 25,
        baseTimeNanos: Long = 0L,
    ): Pair<List<AccelSample>, List<StepEvent>> {
        require(sampleHz in 10..200) { "sampleHz out of range" }
        if (samples.isEmpty()) return emptyList<AccelSample>() to emptyList<StepEvent>()

        val step = 1.0 / sampleHz
        val acc = ArrayList<AccelSample>()
        val events = ArrayList<StepEvent>()
        var nextStepAt = 0.0
        var t = 0.0
        val endT = samples.last().tSec

        while (t <= endT) {
            val speed = speedAt(samples, t)
            val cadence = StrideLinker.cadenceForSpeed(speed, strideMeters)
            val phase = 2 * PI * (cadence / 60.0) * t
            val az = zAmplitude * sin(phase) + rng.nextDouble(-jitter, jitter)
            val ay = yAmplitude * sin(phase) + rng.nextDouble(-jitter, jitter)
            val ax = rng.nextDouble(-jitter, jitter)
            val tNanos = baseTimeNanos + (t * 1e9).toLong()
            acc += AccelSample(tNanos, ax, ay, az)

            // Step events: fire when cadence is meaningful (moving).
            if (cadence > 0) {
                val period = 60.0 / cadence
                while (nextStepAt <= t) {
                    events += StepEvent(baseTimeNanos + (nextStepAt * 1e9).toLong())
                    nextStepAt += period
                }
            }
            t += step
        }
        return acc to events
    }

    private fun speedAt(samples: List<SpeedSample>, t: Double): Double {
        // Linear search is fine for typical run sizes; binary search for big inputs.
        if (t <= samples[0].tSec) return samples[0].speedMps
        if (t >= samples.last().tSec) return samples.last().speedMps
        var lo = 0
        var hi = samples.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].tSec <= t) lo = mid else hi = mid
        }
        val span = samples[hi].tSec - samples[lo].tSec
        val f = if (span <= 0) 0.0 else (t - samples[lo].tSec) / span
        return samples[lo].speedMps + (samples[hi].speedMps - samples[lo].speedMps) * f
    }
}
