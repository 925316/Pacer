package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PositionJitterTest {

    @Test
    fun `starts near origin`() {
        val jitter = PositionJitter(seed = 1L)
        val o = jitter.next(1.0)
        assertTrue(hypot(o.eastMeters, o.northMeters) < 5.0)
    }

    @Test
    fun `radius stays bounded over long run`() {
        val jitter = PositionJitter(seed = 2L)
        var maxR = 0.0
        repeat(2000) {
            val o = jitter.next(1.0)
            val r = hypot(o.eastMeters, o.northMeters)
            if (r > maxR) maxR = r
        }
        assertTrue("maxR=$maxR", maxR <= 1.5 + 1e-9)
    }

    @Test
    fun `different seeds diverge`() {
        val a = PositionJitter(seed = 3L)
        val b = PositionJitter(seed = 4L)
        var diff = 0.0
        repeat(50) { diff += kotlin.math.abs(a.next(1.0).eastMeters - b.next(1.0).eastMeters) }
        assertTrue(diff > 1e-6)
    }

    @Test
    fun `zero dt keeps state reasonable`() {
        val jitter = PositionJitter(seed = 5L)
        jitter.next(0.1)
        val before = jitter.next(0.0)
        assertTrue(before.eastMeters.isFinite())
    }

    @Test
    fun `a smaller radius confines the walk`() {
        // The cap is the parameter, not a constant: a caller that wants a tight fix has to
        // get one, otherwise the radius argument is decoration.
        val jitter = PositionJitter(radiusMeters = 0.3, seed = 6L)
        var maxR = 0.0
        repeat(500) {
            val o = jitter.next(1.0)
            maxR = maxOf(maxR, hypot(o.eastMeters, o.northMeters))
        }
        assertTrue("maxR=$maxR", maxR <= 0.3 + 1e-9)
    }

    @Test
    fun `a per-sample radius confines the walk too`() {
        // The cold-start window passes a wider radius for the first seconds and then the
        // steady one. The override has to actually take effect, or a fix claiming 30 m
        // accuracy keeps sitting on the line.
        val jitter = PositionJitter(seed = 7L)
        var maxWide = 0.0
        repeat(300) {
            val o = jitter.next(1.0, radiusMeters = 6.0)
            maxWide = maxOf(maxWide, hypot(o.eastMeters, o.northMeters))
        }
        assertTrue("maxWide=$maxWide", maxWide > 1.5)
        assertTrue("maxWide=$maxWide", maxWide <= 6.0 + 1e-9)
    }

    @Test
    fun `radius follows the advertised accuracy`() {
        // A settled fix keeps the cap the run always had; a cold fix widens, but only up to
        // the multiplier. The cap is NOT set to the claim: a 5 m claim would then widen every
        // fix to 5 m and stretch a measured 3 km path by about 3.3 percent.
        assertEquals(1.5, PositionJitter.radiusForAccuracy(5f, 5f), 1e-9)
        assertEquals(3.0, PositionJitter.radiusForAccuracy(30f, 5f), 1e-9)
        // Clamped at the multiplier however cold the receiver claims to be: 300 m over a 5 m
        // steady value is a factor of 60, and the walk still stops at 3.0.
        assertEquals(
            1.5 * PositionJitter.COLD_START_MULTIPLIER,
            PositionJitter.radiusForAccuracy(300f, 5f),
            1e-9,
        )
        // Accuracy at or under the settled value never widens the walk.
        assertEquals(1.5, PositionJitter.radiusForAccuracy(3f, 5f), 1e-9)
        // Warmup switched off: the claim equals the steady value, so nothing widens.
        assertEquals(1.5, PositionJitter.radiusForAccuracy(5f, 5f), 1e-9)
    }

    @Test
    fun `steady state is unchanged by the accuracy rule`() {
        // The whole point of keying on the difference from steady: a run that has settled
        // must look exactly as it did before, or the change would widen every ordinary
        // track and inflate the distance the user reads.
        val radius = PositionJitter.radiusForAccuracy(5f, 5f)
        assertEquals(PositionJitter.STEADY_CAP_METERS, radius, 1e-9)
        val jitter = PositionJitter(seed = 9L)
        var maxR = 0.0
        repeat(2000) {
            val o = jitter.next(1.0, radius)
            maxR = maxOf(maxR, hypot(o.eastMeters, o.northMeters))
        }
        // The walk must still reach the cap, not merely stay under it: a sigma that
        // collapsed would also satisfy "maxR <= 1.5" while making the fix pixel-still.
        assertTrue("walk never reached the cap: maxR=$maxR", maxR > 1.0)
        assertTrue("maxR=$maxR", maxR <= 1.5 + 1e-9)
    }
}
