package org.pacer.motion

/**
 * The seed a simulated run's samples are built from.
 *
 * WHY IT IS NOT RANDOM PER BUILD: two processes derive the subject's position from the
 * same stored run - the module that injects it, and the app that draws the live marker.
 * A seed drawn with `Random.nextLong()` on each build made those two builds different
 * tracks: the same inputs, a different speed curve and therefore a different distance at
 * the same second. The marker then showed a position the target app was never given,
 * which makes it useless for judging a route. Worse, any config change mid-run (accuracy,
 * a realism switch) rebuilt the samples from scratch and moved the subject somewhere it
 * had never been.
 *
 * A seed derived from the run's start timestamp has both properties that matter: the two
 * sides agree because they read the same stored timestamp, and consecutive runs differ
 * because a millisecond-precision timestamp is not predictable. Each lap of a repeating
 * run gets its own seed as well, so two laps are still different tracks.
 *
 * The mixing function is the SplitMix64 finaliser, chosen because it is small and spreads
 * consecutive inputs (start timestamps one millisecond apart) across the whole 64-bit
 * range instead of producing nearby seeds.
 */
object MotionSeed {

    /**
     * Seed for [lap] of the run that started at [startTimestampMs].
     *
     * Deterministic by contract: the app and the module call this with the same two
     * numbers and must get the same value on every device.
     */
    fun forRun(startTimestampMs: Long, lap: Long = 0L): Long {
        var z = startTimestampMs * 0x9E3779B97F4A7C15uL.toLong() xor
            (lap + 1L) * 0xC2B2AE3D27D4EB4FuL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
}
