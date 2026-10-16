package org.pacer.motion

/**
 * Tuning knobs for one simulated run.
 *
 * All defaults are original choices for Pacer; speed presets map to
 * walk / run / ride bands chosen by the caller.
 */
data class MotionConfig(
    /** Target steady speed in m/s. */
    val baseSpeedMps: Double,
    /** Ramp-up time from standstill to cruise, seconds (typical 3..5). */
    val acquireDurationSec: Double = 4.0,
    /** Ramp-down time before a pause, seconds (typical 1..2). */
    val decelDurationSec: Double = 1.5,
    /** Cruise noise amplitude as fraction of base speed (typical 0.10..0.15). */
    val cruiseNoiseFraction: Double = 0.12,
    /**
     * Mean-reversion rate of the cruise noise process (1/s); the correlation
     * time of the wobble is 1/this. Kept at or below 0.1 so the variation lives
     * on a >= 10 s scale: faster wobble is averaged away by the fused provider
     * and by the consumer's own smoothing, which would flatten the speed into
     * something no real runner ever reports. The stationary standard deviation
     * is [cruiseNoiseFraction] regardless of this value, because the noise
     * amplitude is scaled by sqrt(2 * rate) in SpeedProfile.
     */
    val noiseMeanReversion: Double = 0.1,
    /** Fractional base-speed loss per kilometre (fatigue, typical 0.05..0.10). */
    val fatiguePerKm: Double = 0.07,
    /** Expected pause events per minute of cruising (simulated traffic/rest). */
    val pausesPerMinute: Double = 0.4,
    /** Pause length range in seconds (typical 8..20). */
    val minPauseSec: Double = 8.0,
    val maxPauseSec: Double = 20.0,
) {
    init {
        require(baseSpeedMps > 0) { "baseSpeedMps must be positive" }
        require(acquireDurationSec > 0) { "acquireDurationSec must be positive" }
        require(decelDurationSec > 0) { "decelDurationSec must be positive" }
        require(cruiseNoiseFraction in 0.0..0.5) { "cruiseNoiseFraction out of range" }
        require(maxPauseSec >= minPauseSec) { "pause range inverted" }
    }

    companion object {
        fun walk() = MotionConfig(baseSpeedMps = 1.4)
        fun run() = MotionConfig(baseSpeedMps = 3.0)
        fun ride() = MotionConfig(baseSpeedMps = 6.0)
    }
}
