package org.pacer.motion

/**
 * High-level facade: one call produces a self-consistent run
 * (speed curve + cumulative steps). Keeps the physics triangle
 * distance <-> steps <-> cadence in a single place so callers
 * (UI, hook, sensor synthesis) cannot drift apart.
 */
object Humanizer {
    data class RunResult(
        val samples: List<SpeedSample>,
        val cumulativeSteps: List<Long>,
        val totalDistanceMeters: Double,
        val totalSteps: Long,
    )

    fun simulate(
        config: MotionConfig,
        distanceMeters: Double,
        dtSec: Double = 1.0,
        seed: Long = kotlin.random.Random.nextLong(),
    ): RunResult {
        val profile = SpeedProfile(config, seed)
        val samples = profile.generate(distanceMeters, dtSec)
        val steps = StrideLinker.cumulativeSteps(samples, config.strideMeters)
        return RunResult(
            samples = samples,
            cumulativeSteps = steps,
            totalDistanceMeters = samples.lastOrNull()?.distanceMeters ?: 0.0,
            totalSteps = steps.lastOrNull() ?: 0L,
        )
    }
}
