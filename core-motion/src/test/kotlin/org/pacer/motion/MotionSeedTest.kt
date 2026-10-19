package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed is the contract that makes the app's live marker and the injected fix the
 * same track, so the properties pinned here are the ones a detector - or a user
 * comparing two screens - would notice if they broke.
 */
class MotionSeedTest {

    @Test
    fun `the same run and lap always give the same seed`() {
        val start = 1_700_000_000_123L
        assertEquals(MotionSeed.forRun(start, 2L), MotionSeed.forRun(start, 2L))
    }

    @Test
    fun `consecutive runs do not share a seed`() {
        val start = 1_700_000_000_123L
        assertNotEquals(MotionSeed.forRun(start), MotionSeed.forRun(start + 1))
    }

    @Test
    fun `each lap of one run gets its own seed`() {
        val start = 1_700_000_000_123L
        val seeds = (0L..20L).map { MotionSeed.forRun(start, it) }
        assertEquals("laps must not repeat a seed", seeds.size, seeds.toSet().size)
    }

    @Test
    fun `adjacent runs produce unrelated seeds`() {
        // SplitMix64's point: the low bits of the input must not survive into the output,
        // or two runs a millisecond apart would start from almost the same noise.
        val a = MotionSeed.forRun(1_700_000_000_000L)
        val b = MotionSeed.forRun(1_700_000_000_001L)
        val differingBits = java.lang.Long.bitCount(a xor b)
        assertTrue("differing bits=$differingBits", differingBits >= 20)
    }

    @Test
    fun `an unset start timestamp still yields a usable seed`() {
        // A run is only played back once a timestamp exists, but the function must not be
        // a special case that two callers could implement differently.
        assertNotEquals(0L, MotionSeed.forRun(0L, 0L))
    }
}
