package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.motion.GeoPoint
import org.pacer.motion.PacePlan
import org.pacer.motion.PlanDistance
import org.pacer.motion.SpeedPlan
import org.pacer.motion.StraightLine

/**
 * The straight-line path must treat both plan shapes the same way.
 *
 * A pace curve is stored in the same key as a timed plan but is indexed by distance, and two
 * copies of the "which plan applies" decision - one in the hook, one in the app - already
 * drifted: both read the timed plan, only one read the curve, so a per-kilometre session ran
 * at the flat speed. [PlanDistance] is the single implementation they now delegate to, and
 * these tests pin what it must answer.
 *
 * This lives in the module rather than in core-motion because the hook is where the drift was
 * observed, but everything under test is core-motion: it needs no device.
 */
class PlanSourceParityTest {

    private val curve = PacePlan.fromKilometreSplits(listOf(300.0, 310.0, 320.0))

    private val timed = SpeedPlan(listOf(SpeedPlan.Step(4.0, 60.0), SpeedPlan.Step(2.0, 60.0)), repeats = 0)

    @Test
    fun `a pace curve supplies the distance, not the flat speed`() {
        val flat = 3.0
        for (elapsed in listOf(60.0, 300.0, 600.0)) {
            val meters = PlanDistance.metersAt(curve, timed = null, elapsedSec = elapsed, speedMps = flat)
            assertEquals("elapsed $elapsed s", curve.metersAt(elapsed), meters, 1e-9)
            // The failure this replaces: the curve was ignored and speed x time was used.
            assertTrue(
                "elapsed $elapsed s must not fall back to the flat speed",
                kotlin.math.abs(meters - flat * elapsed) > 1e-6,
            )
        }
    }

    @Test
    fun `a timed plan supplies the distance too`() {
        val meters = PlanDistance.metersAt(pace = null, timed = timed, elapsedSec = 90.0, speedMps = 3.0)
        assertEquals(timed.metersAt(90.0), meters, 1e-9)
    }

    @Test
    fun `the curve wins when both shapes were somehow set`() {
        // MotionConfig refuses this pair, so it is only reachable if a caller passes both by
        // hand. The answer must still be one of them rather than an invented third value.
        val meters = PlanDistance.metersAt(curve, timed, elapsedSec = 300.0, speedMps = 3.0)
        assertEquals(curve.metersAt(300.0), meters, 1e-9)
    }

    @Test
    fun `no plan falls back to the flat speed`() {
        assertEquals(
            150.0,
            PlanDistance.metersAt(pace = null, timed = null, elapsedSec = 50.0, speedMps = 3.0),
            1e-9,
        )
    }

    @Test
    fun `speed is read at the distance the same moment reaches`() {
        // The pair that must describe one motion. Reading the distance-indexed curve by
        // clock instead would report the speed of a different metre.
        for (elapsed in listOf(0.0, 120.0, 300.0, 600.0, 930.0, 1200.0)) {
            val meters = PlanDistance.metersAt(curve, null, elapsed, 3.0)
            val speed = PlanDistance.speedAt(curve, null, elapsed, meters, 3.0)
            assertEquals("elapsed $elapsed s", curve.speedAtDistance(meters), speed, 1e-12)
            assertTrue("elapsed $elapsed s", speed > 0.0)
        }
        // Past the end of the curve it holds its last speed, so a longer route does not stop.
        assertEquals(
            curve.speedAtDistance(curve.distanceMeters),
            PlanDistance.speedAt(curve, null, 5000.0, PlanDistance.metersAt(curve, null, 5000.0, 3.0), 3.0),
            1e-12,
        )
    }

    @Test
    fun `the timed plan's speed is still read off the clock`() {
        val meters = PlanDistance.metersAt(null, timed, 90.0, 3.0)
        assertEquals(timed.speedAt(90.0), PlanDistance.speedAt(null, timed, 90.0, meters, 3.0), 1e-12)
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

    @Test
    fun `a kilometre of curve is a kilometre of ground`() {
        // StraightLine is what both sides turn a distance into a point with, so the curve and
        // the geometry have to agree on the unit.
        val start = GeoPoint(43.8256, 87.6168)
        val oneKm = PlanDistance.metersAt(curve, null, curve.secondsTo(1000.0), 3.0)
        val here = StraightLine.byDistance(start, bearingDeg = 0.0, meters = oneKm)
        assertEquals(1000.0, distanceMetres(start, here), 1.0)
    }

    private fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
        val north = (b.lat - a.lat) * 111_320.0
        val east = (b.lon - a.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.sqrt(north * north + east * east)
    }
}
