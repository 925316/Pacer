package org.pacer.motion

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * One sampled point of a speed curve.
 *
 * @param tSec elapsed seconds since run start
 * @param speedMps instantaneous speed, always >= 0
 * @param phase motion phase active at this sample
 * @param distanceMeters cumulative distance up to this sample
 */
data class SpeedSample(
    val tSec: Double,
    val speedMps: Double,
    val phase: MotionPhase,
    val distanceMeters: Double,
)

/**
 * Generates a humanized speed curve for a fixed-distance run.
 *
 * Original Pacer design (not ported from any repo):
 * - ACQUIRE ramp: smooth S-curve 0 -> base over [MotionConfig.acquireDurationSec]
 * - CRUISE: Ornstein-Uhlenbeck noise around a slowly fatiguing base, where the base
 *   comes from [MotionConfig.plan] when an interval session is configured and from
 *   [MotionConfig.baseSpeedMps] otherwise
 * - PAUSE: exponential inter-arrival, uniform duration, DECEL ramp in/out
 * - distance is integrated with the trapezoidal rule so steps stay consistent
 *
 * Owns its own [Random] instance so two runs with different seeds diverge.
 */
class SpeedProfile(
    private val config: MotionConfig,
    seed: Long = Random.nextLong(),
) {
    private val rng = Random(seed)

    /** Current value of the mean-reverting cruise-noise process. */
    private var noise = 0.0

    /**
     * @param speedCapAtDistance optional multiplier in (0, 1] for the cruise speed at a
     *   given travelled distance, used to brake into a turn. It is applied INSIDE the
     *   integration on purpose: scaling the speed afterwards would report a speed the
     *   track's own displacement does not show, which is the kind of internal
     *   disagreement an audit is supposed to find.
     */
    fun generate(
        distanceMeters: Double,
        dtSec: Double = 1.0,
        speedCapAtDistance: ((Double) -> Double)? = null,
    ): List<SpeedSample> {
        require(distanceMeters > 0) { "distanceMeters must be positive" }
        require(dtSec in 0.2..5.0) { "dtSec out of range" }

        val out = ArrayList<SpeedSample>(1024)
        var t = 0.0
        var dist = 0.0
        var phase = MotionPhase.ACQUIRE
        var phaseT = 0.0
        var pauseLeft = 0.0
        var decelFrom = 0.0
        var cruiseBase = config.baseSpeedMps
        var prevSpeed = 0.0
        // Defence in depth for the integration loop: SpeedPlan now rejects a plan
        // whose pass covers no ground, but a cap callback that holds the subject at
        // 0 m/s would still never advance. Give up after longer than the longest
        // legitimate stop rather than hang a target process.
        val stallLimitSec = config.maxPauseSec + config.acquireDurationSec +
            config.decelDurationSec + 30.0
        var stalledSec = 0.0
        var nextPauseAt = drawNextPauseAt()

        out += SpeedSample(0.0, 0.0, MotionPhase.ACQUIRE, 0.0)

        while (dist < distanceMeters) {
            t += dtSec
            phaseT += dtSec
            val remaining = distanceMeters - dist
            // Leave room to stop: never plan to overshoot the finish.
            val stopSpeed = remaining / dtSec
            // Braking into a corner applies whenever the subject is accelerating, not only
            // while cruising: resuming after a pause into a turn is the same situation as
            // reaching it at speed, and leaving the ramp uncapped put full-speed samples on
            // the approach to every turn that followed a stop.
            val speedCap = speedCapAtDistance?.invoke(dist)?.coerceIn(0.0, 1.0) ?: 1.0

            val speed = when (phase) {
                MotionPhase.ACQUIRE -> {
                    val k = min(1.0, phaseT / config.acquireDurationSec)
                    val ramp = k * k * (3 - 2 * k) // smoothstep
                    val target = (targetSpeed(t) * ramp * speedCap).coerceAtLeast(0.0)
                    if (k >= 1.0) transition(MotionPhase.CRUISE).also { phase = it; phaseT = 0.0 }
                    min(target, stopSpeed)
                }
                MotionPhase.CRUISE -> {
                    cruiseBase = fatigued(targetSpeed(t), dist)
                    val noisy = (cruiseBase * (1 + stepNoise(dtSec)) * speedCap)
                        .coerceAtLeast(0.0)
                    if (t >= nextPauseAt) {
                        pauseLeft = config.minPauseSec +
                            rng.nextDouble() * (config.maxPauseSec - config.minPauseSec)
                        decelFrom = prevSpeed
                        transition(MotionPhase.DECEL).also { phase = it; phaseT = 0.0 }
                        min(prevSpeed, stopSpeed)
                    } else {
                        min(noisy, stopSpeed)
                    }
                }
                MotionPhase.DECEL -> {
                    val k = min(1.0, phaseT / config.decelDurationSec)
                    val target = decelFrom * (1 - k * k * (3 - 2 * k))
                    if (k >= 1.0) transition(MotionPhase.PAUSE).also { phase = it; phaseT = 0.0 }
                    min(max(target, 0.0), stopSpeed)
                }
                MotionPhase.PAUSE -> {
                    pauseLeft -= dtSec
                    if (pauseLeft <= 0) {
                        nextPauseAt = t + drawNextPauseAt()
                        transition(MotionPhase.ACQUIRE).also { phase = it; phaseT = 0.0 }
                        min(cruiseBase * 0.1, stopSpeed)
                    } else {
                        0.0
                    }
                }
                MotionPhase.IDLE -> 0.0
            }

            val moved = (prevSpeed + speed) * 0.5 * dtSec
            dist += moved
            if (dist > distanceMeters) dist = distanceMeters
            if (moved > 1e-9) stalledSec = 0.0 else stalledSec += dtSec
            prevSpeed = speed
            out += SpeedSample(t, speed, phase, dist)
            if (stalledSec > stallLimitSec) break
        }
        return out
    }

    private fun transition(to: MotionPhase): MotionPhase = to

    /**
     * The speed the session is aiming for at [tSec]: the interval plan's current step,
     * or the flat base speed when there is no plan.
     */
    private fun targetSpeed(tSec: Double): Double = config.plan?.speedAt(tSec) ?: config.baseSpeedMps

    private fun fatigued(speedMps: Double, distanceMeters: Double): Double {
        val factor = 1.0 - config.fatiguePerKm * (distanceMeters / 1000.0)
        return (speedMps * factor).coerceAtLeast(speedMps * 0.5)
    }

    private fun stepNoise(dtSec: Double): Double {
        // OU step: dx = -theta * x * dt + sigma * sqrt(dt) * N(0,1).
        // Sigma scaled so the stationary std ~= cruiseNoiseFraction.
        val sigma = config.cruiseNoiseFraction * kotlin.math.sqrt(2 * config.noiseMeanReversion)
        noise += -config.noiseMeanReversion * noise * dtSec +
            sigma * kotlin.math.sqrt(dtSec) * rng.nextGaussian()
        return noise.coerceIn(-0.5, 0.5)
    }

    private fun drawNextPauseAt(): Double {
        if (config.pausesPerMinute <= 0) return Double.POSITIVE_INFINITY
        val ratePerSec = config.pausesPerMinute / 60.0
        return -kotlin.math.ln(1 - rng.nextDouble()) / ratePerSec
    }

    private fun Random.nextGaussian(): Double {
        var u: Double
        var v: Double
        do {
            u = nextDouble()
            v = nextDouble()
        } while (u <= Double.MIN_VALUE)
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) *
            kotlin.math.cos(2.0 * kotlin.math.PI * v)
    }
}
