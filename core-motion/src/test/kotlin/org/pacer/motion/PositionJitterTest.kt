package org.pacer.motion

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
}
