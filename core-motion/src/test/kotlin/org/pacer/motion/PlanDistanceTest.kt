package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A straight run may be driven by either plan shape, and both must be read the way each is
 * indexed: the timed one off the clock, the curve off the distance.
 *
 * Two copies of that decision already existed - in the hook and in the app - and they drifted:
 * both read the timed plan, only one read the curve. A per-kilometre session therefore moved
 * at the flat speed while the editor showed its splits. [PlanDistance] is the single reader
 * they now share, and what is pinned here is what it must answer.
 */
class PlanDistanceTest {

    private val curve = PacePlan.fromKilometreSplits(listOf(300.0, 310.0, 320.0))

    private val timed = SpeedPlan(
        listOf(SpeedPlan.Step(4.0, 60.0), SpeedPlan.Step(2.0, 60.0)),
        repeats = 0,
    )

    @Test
    fun `a pace curve supplies the distance, not the flat speed`() {
        val flat = 3.0
        for (elapsed in listOf(60.0, 300.0, 600.0)) {
            val step = PlanDistance.at(curve, timed = null, elapsedSec = elapsed, flatSpeedMps = flat)
            assertEquals("elapsed $elapsed s", curve.metersAt(elapsed), step.meters, 1e-9)
            // The failure this replaces: the curve was ignored and speed x time was used.
            assertTrue(
                "elapsed $elapsed s must not fall back to the flat speed",
                kotlin.math.abs(step.meters - flat * elapsed) > 1e-6,
            )
        }
    }

    @Test
    fun `a timed plan supplies the distance too`() {
        val step = PlanDistance.at(pace = null, timed = timed, elapsedSec = 90.0, flatSpeedMps = 3.0)
        assertEquals(timed.metersAt(90.0), step.meters, 1e-9)
    }

    @Test
    fun `the curve wins when both shapes were somehow set`() {
        // MotionConfig refuses this pair, so it is only reachable if a caller builds it by
        // hand. The answer must still be one of them rather than an invented third value.
        val step = PlanDistance.at(curve, timed, elapsedSec = 300.0, flatSpeedMps = 3.0)
        assertEquals(curve.metersAt(300.0), step.meters, 1e-9)
    }

    @Test
    fun `no plan falls back to the flat speed`() {
        val step = PlanDistance.at(pace = null, timed = null, elapsedSec = 50.0, flatSpeedMps = 3.0)
        assertEquals(150.0, step.meters, 1e-9)
        assertEquals(3.0, step.speedMps, 1e-9)
    }

    @Test
    fun `the speed belongs to the distance in the same answer`() {
        // One call returns both, so a caller cannot pair a distance from one moment with a
        // speed from another. Reading a distance-indexed curve by time would report the speed
        // of a different metre, which is the disagreement this class exists to prevent.
        for (elapsed in listOf(0.0, 120.0, 300.0, 600.0, 930.0, 1200.0)) {
            val step = PlanDistance.at(curve, null, elapsed, 3.0)
            assertEquals("elapsed $elapsed s", curve.speedAtDistance(step.meters), step.speedMps, 1e-12)
            assertTrue("elapsed $elapsed s", step.speedMps > 0.0)
        }
        // Past the end of the curve it holds its last speed, so a longer route does not stop.
        val far = PlanDistance.at(curve, null, 5000.0, 3.0)
        assertEquals(curve.speedAtDistance(curve.distanceMeters), far.speedMps, 1e-12)
    }

    @Test
    fun `the timed plan's speed is still read off the clock`() {
        val step = PlanDistance.at(null, timed, 90.0, 3.0)
        assertEquals(timed.speedAt(90.0), step.speedMps, 1e-12)
    }

    @Test
    fun `both plan shapes parse from the one stored key`() {
        // Only one shape is ever set, and the prefix is what tells them apart. A parser that
        // accepted the other shape's text would silently run a different session.
        assertNull(SpeedPlan.parse(curve.format()))
        assertNull(PacePlan.parse(timed.format()))
        assertEquals(curve.distanceMeters, PacePlan.parse(curve.format())!!.distanceMeters, 1e-6)
        assertEquals(timed.passSeconds, SpeedPlan.parse(timed.format())!!.passSeconds, 1e-6)
    }
}
