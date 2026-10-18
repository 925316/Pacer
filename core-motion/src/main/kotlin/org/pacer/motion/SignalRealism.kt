package org.pacer.motion

import kotlin.math.max
import kotlin.random.Random

/**
 * GPS signal-realism model: accuracy warmup, altitude drift, satellite count,
 * bearing hold while stationary, and random dropouts.
 *
 * Original Pacer implementation. Parameter names echo the *concepts* found in
 * public "GPS realism" discussions (warmup, drift, dropout); every formula
 * below is written from scratch for this project.
 *
 * Which of those behaviours are simulated is a user decision, so each one is gated on
 * [toggles]. A disabled behaviour reports the steady value it would have converged to,
 * not a zero: a device with a warm almanac and a recent fix is a real device too, and
 * zeroes would be a different lie rather than no lie.
 */
class SignalRealism(
    private val toggles: RealismToggles = RealismToggles(),
    private val warmupSec: Double = 30.0,
    private val warmAccuracyMeters: Float = 30f,
    private val steadyAccuracyMeters: Float = 5f,
    private val dropoutPerMinute: Double = 0.3,
    private val dropoutMaxSec: Double = 3.0,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)
    private var altitude = 0.0
    private var dropoutLeft = 0.0
    private var nextDropoutAt = drawNextDropout()
    private var lastBearing = 0f

    data class Fix(
        val accuracyMeters: Float,
        val altitudeMeters: Double,
        val satellites: Int,
        val bearingDeg: Float,
        val dropout: Boolean,
    )

    fun next(tSec: Double, speedMps: Double, dtSec: Double, bearingDeg: Float): Fix {
        // Accuracy warmup: exponential converge warm -> steady.
        val accuracy = if (toggles.accuracyWarmup) {
            val k = (tSec / warmupSec).coerceIn(0.0, 1.0)
            (steadyAccuracyMeters + (warmAccuracyMeters - steadyAccuracyMeters) * (1 - k) * (1 - k)).toFloat()
        } else {
            steadyAccuracyMeters
        }

        // Altitude: slow random walk, ~cm per second.
        if (toggles.altitudeDrift) altitude += rng.nextDouble(-1.0, 1.0) * 0.05 * dtSec

        // Satellites: uniform 7..14 while moving, dips during dropout. With the jitter
        // off the count is reported steady, which is what a device with a solid fix does.
        var satellites = if (toggles.satelliteJitter) rng.nextInt(7, 15) else STEADY_SATELLITES
        var dropout = false
        if (dropoutLeft > 0) {
            dropoutLeft -= dtSec
            dropout = true
            satellites = max(3, satellites - 4)
        } else if (tSec >= nextDropoutAt) {
            dropoutLeft = rng.nextDouble(1.0, dropoutMaxSec)
            nextDropoutAt = tSec + drawNextDropout()
            dropout = true
        }

        // Bearing hold: keep last bearing when (near-)stationary.
        val bearing = if (toggles.bearingHold && speedMps < 0.3) {
            lastBearing
        } else {
            bearingDeg.also { lastBearing = it }
        }

        return Fix(accuracy, altitude, satellites, bearing, dropout)
    }

    private fun drawNextDropout(): Double {
        if (!toggles.signalDropout || dropoutPerMinute <= 0) return Double.POSITIVE_INFINITY
        val rate = dropoutPerMinute / 60.0
        return -kotlin.math.ln(1 - rng.nextDouble()) / rate
    }

    private companion object {
        /** Mid-band of the jittered satellite count, reported when jitter is off. */
        const val STEADY_SATELLITES = 11
    }
}
