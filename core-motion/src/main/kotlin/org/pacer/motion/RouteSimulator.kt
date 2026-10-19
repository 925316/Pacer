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
    private val steadyAccuracyMeters: Float = 5f,
) {
    /**
     * One run of the route, built entirely from [seed].
     *
     * The signal model is created here rather than injected so that it is seeded from the
     * same value as the speed curve. A random seed of its own would make two builds from
     * the same inputs differ in the reported accuracy, altitude, satellite count and
     * bearing - values the app's live marker and the module both derive. Passing the run's
     * seed through is what makes "same run, same samples" true for the whole fix rather
     * than only for its position.
     */
    fun simulate(dtSec: Double = 1.0, seed: Long = Random.nextLong()): List<FixSample> {
        // A route whose points all coincide has no distance to cover, and the speed curve
        // refuses to generate one. That state is reachable - two taps on the same spot - and
        // it must produce a standing subject, not an exception on every sensor callback.
        if (route.totalLengthMeters <= 0.0) return stationary(dtSec, seed)
        val run = Humanizer.simulate(
            config,
            route.totalLengthMeters,
            dtSec,
            seed,
            speedCapAtDistance = ::turnBraking,
        )
        val realism = SignalRealism(
            toggles = toggles,
            steadyAccuracyMeters = steadyAccuracyMeters,
            seed = seed xor SKY_SEED_SALT,
        )
        // Cadence needs its own stream for the same reason the sky does: a jitter drawn
        // from the process-wide Random made two builds of one run differ in a field this
        // sample carries, so "the same run" was not the same fix.
        val cadenceRng = Random(seed xor STRIDE_SEED_SALT)
        val out = ArrayList<FixSample>(run.samples.size)
        for (i in run.samples.indices) {
            val s = run.samples[i]
            val (p, brg) = route.pointAt(s.distanceMeters)
            val fx = realism.next(s.tSec, s.speedMps, dtSec, brg)
            val cadence = StrideLinker.cadenceForSpeed(s.speedMps, rng = cadenceRng)
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

    /**
     * A run that stays at the route's only point.
     *
     * Built directly rather than through the speed curve because that curve is defined on a
     * positive distance. The signal model still runs on the clock, so accuracy converges and
     * the satellite count still varies: a subject standing still is reporting a real fix,
     * not a broken one, and only its position is trivial.
     *
     * The list covers the warmup window and then ends; [MotionSamples.at] answers every
     * later moment with the last sample, which is exactly right for a stationary run.
     */
    private fun stationary(dtSec: Double, seed: Long): List<FixSample> {
        val point = route.pointAt(0.0).first
        val realism = SignalRealism(
            toggles = toggles,
            steadyAccuracyMeters = steadyAccuracyMeters,
            seed = seed xor SKY_SEED_SALT,
        )
        val out = ArrayList<FixSample>(STATIONARY_SAMPLES)
        var t = 0.0
        while (t <= STATIONARY_SECONDS) {
            val fx = realism.next(t, 0.0, dtSec, 0f)
            out += FixSample(
                tSec = t,
                point = point,
                speedMps = 0.0,
                bearingDeg = fx.bearingDeg,
                distanceMeters = 0.0,
                phase = MotionPhase.IDLE,
                accuracyMeters = fx.accuracyMeters,
                altitudeMeters = fx.altitudeMeters,
                satellites = fx.satellites,
                signalDropout = fx.dropout,
                cumulativeSteps = 0L,
                cadenceSpm = 0.0,
            )
            t += dtSec
        }
        return out
    }

    /**
     * Braking into the next turn: the heading change between the point here and the point
     * a short distance ahead gives the factor the speed curve is scaled by.
     *
     * A step rather than a ramp, deliberately: the cruise noise is wider than the step, so
     * a smoother approach would not be visible in the samples, and the look-ahead already
     * means braking begins before the corner rather than at it. This is what makes a route
     * run look like someone who has to slow for corners instead of a constant-speed line.
     */
    private fun turnBraking(distanceMeters: Double): Double {
        val ahead = (distanceMeters + TURN_LOOKAHEAD_METERS).coerceAtMost(route.totalLengthMeters)
        val change = TurnModel.headingChangeDegrees(
            route.pointAt(distanceMeters).second.toDouble(),
            route.pointAt(ahead).second.toDouble(),
        )
        return TurnModel.brakingFactor(change)
    }

    private companion object {
        /** How far ahead a corner starts slowing the subject down. */
        const val TURN_LOOKAHEAD_METERS = 20.0

        /**
         * Keeps the sky model's random stream from being the speed model's. A salt rather
         * than a second seed because both must stay a pure function of the run.
         */
        const val SKY_SEED_SALT = 0x534B595F53454544L

        /**
         * Same idea for the cadence jitter: drawing it from the process-wide Random would
         * make two builds of one run report a different cadence for the same second, and
         * the app's marker could not describe the fix the module was sending.
         */
        const val STRIDE_SEED_SALT = 0x5354524944455F4AL

        /** How long a stationary run's sample list covers; later moments reuse its last. */
        const val STATIONARY_SECONDS = 60.0

        const val STATIONARY_SAMPLES = 64
    }
}
