package org.pacer.motion

import kotlin.random.Random

/**
 * Combines [RoutePath] geometry with [SpeedProfile] speed and [SignalRealism]
 * signal traits to produce an interleaved stream of simulated fixes along a
 * real-world route.
 *
 * One sample carries position, motion and signal context together so callers
 * (mock provider, sensor synthesis, UI preview) all consume the same data.
 */
data class FixSample(
    val tSec: Double,
    val point: GeoPoint,
    val speedMps: Double,
    val bearingDeg: Float,
    val distanceMeters: Double,
    val phase: MotionPhase,
    val accuracyMeters: Float,
    val altitudeMeters: Double,
    val satellites: Int,
    val signalDropout: Boolean,
    val cumulativeSteps: Long,
    val cadenceSpm: Double,
)

class RouteSimulator(
    private val route: RoutePath,
    private val config: MotionConfig,
    private val toggles: RealismToggles = RealismToggles(),
    steadyAccuracyMeters: Float = 5f,
    private val realism: SignalRealism = SignalRealism(
        toggles = toggles,
        steadyAccuracyMeters = steadyAccuracyMeters,
    ),
) {
    fun simulate(dtSec: Double = 1.0, seed: Long = Random.nextLong()): List<FixSample> {
        val run = Humanizer.simulate(config, route.totalLengthMeters, dtSec, seed)
        val out = ArrayList<FixSample>(run.samples.size)
        for (i in run.samples.indices) {
            val s = run.samples[i]
            val (p, brg) = route.pointAt(s.distanceMeters)
            val fx = realism.next(s.tSec, s.speedMps, dtSec, brg)
            val cadence = StrideLinker.cadenceForSpeed(s.speedMps)
            out += FixSample(
                tSec = s.tSec,
                point = p,
                speedMps = s.speedMps,
                bearingDeg = fx.bearingDeg,
                distanceMeters = s.distanceMeters,
                phase = s.phase,
                accuracyMeters = fx.accuracyMeters,
                altitudeMeters = fx.altitudeMeters,
                satellites = fx.satellites,
                signalDropout = fx.dropout,
                cumulativeSteps = run.cumulativeSteps[i],
                cadenceSpm = cadence,
            )
        }
        return out
    }
}
