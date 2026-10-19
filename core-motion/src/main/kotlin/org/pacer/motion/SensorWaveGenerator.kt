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
    private val zAmplitude: Double = Z_AMPLITUDE,
    private val yAmplitude: Double = Y_AMPLITUDE,
    private val jitter: Double = JITTER,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)

    companion object {

        /** Z amplitude in m/s^2: the vertical bounce of a running body. */
        const val Z_AMPLITUDE = 3.5

        /** Y amplitude in m/s^2: the smaller lateral sway at the same frequency. */
        const val Y_AMPLITUDE = 1.5

        /** Uniform jitter added to every axis, in m/s^2. */
        const val JITTER = 0.15

        /**
         * The three axis values at [phase] radians, in m/s^2, as (ax, ay, az).
         *
         * [generate] below and the live hook both come through here, so the waveform an
         * app receives IS the one the tests describe. Two separate implementations of one
         * formula would leave nothing to keep them in step.
         */
        fun axes(
            phase: Double,
            random: Random,
            zAmplitude: Double = Z_AMPLITUDE,
            yAmplitude: Double = Y_AMPLITUDE,
            jitter: Double = JITTER,
        ): Triple<Double, Double, Double> = Triple(
            random.nextDouble(-jitter, jitter),
            yAmplitude * sin(phase) + random.nextDouble(-jitter, jitter),
            zAmplitude * sin(phase) + random.nextDouble(-jitter, jitter),
        )
    }

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
        var phase = 0.0
        val endT = samples.last().tSec

        while (t <= endT) {
            val speed = speedAt(samples, t)
            // The generator's own stream, so the same seed produces the same samples:
            // cadence jitter drawn from the process-wide Random made this function
            // non-reproducible despite taking a seed.
            val cadence = StrideLinker.cadenceForSpeed(speed, rng = rng)
            // Phase accumulates so cadence changes do not jump the waveform.
            val (ax, ay, az) = axes(phase, rng, zAmplitude, yAmplitude, jitter)
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
            phase += 2 * PI * (cadence / 60.0) * step
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

    /**
     * The same waveform advanced by wall clock instead of precomputed over a speed
     * curve, because the hook is asked for one sample at a time as the platform
     * dispatches sensor events and has no curve to generate from.
     *
     * Stateful on purpose: the phase has to accumulate across calls, or a cadence that
     * changes mid-run would jump the waveform instead of bending it.
     */
    class Live(
        seed: Long = Random.nextLong(),
        private val zAmplitude: Double = Z_AMPLITUDE,
        private val yAmplitude: Double = Y_AMPLITUDE,
        private val jitter: Double = JITTER,
    ) {
        private val rng = Random(seed)
        private var phase = 0.0
        private var lastMillis = 0L

        /**
         * Axis accelerations at [nowMillis], with the phase advanced by [cadenceSpm]
         * since the previous call. The first call only anchors the clock.
         */
        fun at(nowMillis: Long, cadenceSpm: Double): Triple<Double, Double, Double> {
            if (lastMillis != 0L) {
                val dtSec = ((nowMillis - lastMillis).coerceAtLeast(0L)) / 1000.0
                phase += 2 * PI * (cadenceSpm / 60.0) * dtSec
            }
            lastMillis = nowMillis
            return axes(phase, rng, zAmplitude, yAmplitude, jitter)
        }
    }
}
