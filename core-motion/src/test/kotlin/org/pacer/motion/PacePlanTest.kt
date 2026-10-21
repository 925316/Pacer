package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pace curve is the distance-indexed plan. Its whole point is that a runner describes a
 * session in kilometres, so the tests are written in those terms.
 *
 * The integral and its inverse are checked against each other rather than against
 * hand-computed constants: they are two measurements of the same curve, and a disagreement
 * between them is exactly the fault that would put the map marker and the injected fix in
 * different places.
 */
class PacePlanTest {

    private fun plan(vararg pairs: Pair<Double, Double>) =
        PacePlan(pairs.map { PacePlan.Point(it.first, it.second) })

    @Test
    fun `speed is read at a distance, not a time`() {
        val p = plan(0.0 to 3.0, 1000.0 to 2.0)
        assertEquals(3.0, p.speedAtDistance(0.0), 1e-9)
        assertEquals(2.0, p.speedAtDistance(1000.0), 1e-9)
        // Halfway through the kilometre the speed is halfway between the two breakpoints.
        assertEquals(2.5, p.speedAtDistance(500.0), 1e-9)
        // Past the end the last speed holds, so a longer route does not stop dead.
        assertEquals(2.0, p.speedAtDistance(5000.0), 1e-9)
    }

    @Test
    fun `time and distance are inverses of each other`() {
        val p = plan(0.0 to 3.0, 1000.0 to 2.8, 2000.0 to 2.6)
        for (target in listOf(1.0, 250.0, 1000.0, 1500.0, 2000.0)) {
            val seconds = p.secondsTo(target)
            assertEquals("distance $target", target, p.metersAt(seconds), 1e-6)
        }
    }

    @Test
    fun `a flat curve matches speed times time`() {
        val p = plan(0.0 to 3.0, 1000.0 to 3.0)
        assertEquals(1000.0 / 3.0, p.totalSeconds, 1e-9)
        assertEquals(300.0, p.metersAt(100.0), 1e-9)
    }

    @Test
    fun `the mean of the curve is what an overall target compares against`() {
        val p = plan(0.0 to 3.0, 1000.0 to 2.0)
        // 1000 m at an average of... the integral of 1/v, not the arithmetic mean of v.
        val expected = 1000.0 / p.totalSeconds
        assertEquals(expected, p.averageSpeed(), 1e-9)
        // Linear-in-distance deceleration averages the way the harmonic does, not the
        // arithmetic mean (2.5) - this is the number a "3 km in 15 min" check must use.
        assertTrue("avg ${p.averageSpeed()}", p.averageSpeed() < 2.5)
    }

    @Test
    fun `a kilometre split list covers every kilometre it lists`() {
        // Splits are PACE, in seconds per kilometre: 5:00, 5:20, 5:40.
        val p = PacePlan.fromKilometreSplits(listOf(300.0, 320.0, 340.0))
        // Three splits describe three kilometres. The first version of this stopped at the
        // last breakpoint, so the third kilometre took no time at all.
        assertEquals(3000.0, p.distanceMeters, 1e-9)
        assertEquals(1000.0 / 340.0, p.speedAtDistance(3000.0), 1e-9)
        assertEquals(1000.0 / 340.0, p.speedAtDistance(2500.0), 1e-9)
    }

    @Test
    fun `each kilometre averages the pace that was typed for it`() {
        // This is the whole promise of a per-kilometre plan, and a curve that changes speed
        // across the whole kilometre breaks it: straight-line interpolation made a 300 s
        // split finish in 305 s.
        val splits = listOf(300.0, 310.0, 320.0)
        val p = PacePlan.fromKilometreSplits(splits)
        for (i in splits.indices) {
            val from = p.secondsTo(i * 1000.0)
            val to = p.secondsTo((i + 1) * 1000.0)
            assertEquals("kilometre ${i + 1}", splits[i].toDouble(), to - from, 0.5)
        }
        assertEquals("whole session", splits.sum().toDouble(), p.totalSeconds, 1.0)
    }

    @Test
    fun `a partial final kilometre ends the curve where the route does`() {
        val p = PacePlan.fromKilometreSplits(listOf(300.0, 400.0), lastPartialMeters = 400.0)
        assertEquals(1400.0, p.distanceMeters, 1e-9)
        assertEquals(1000.0 / 400.0, p.speedAtDistance(1400.0), 1e-9)
    }

    @Test
    fun `the text form survives a round trip`() {
        val original = plan(0.0 to 3.0, 1000.0 to 2.75, 2000.0 to 2.5)
        val parsed = PacePlan.parse(original.format())
        assertNotNull(parsed)
        assertEquals(original.points.size, parsed!!.points.size)
        for (i in original.points.indices) {
            assertEquals(original.points[i].distanceMeters, parsed.points[i].distanceMeters, 1e-9)
            assertEquals(original.points[i].speedMps, parsed.points[i].speedMps, 1e-9)
        }
    }

    @Test
    fun `a timed plan is not mistaken for a pace curve`() {
        // SpeedPlan's own text form has no prefix; the two must never parse into each other,
        // or a stored run would come back as a different kind of session.
        assertNull(PacePlan.parse("3.0@180;1.4@60#5"))
        assertNull(SpeedPlan.parse(PacePlan.fromKilometreSplits(listOf(300.0, 320.0)).format()))
    }

    @Test
    fun `malformed input is no plan rather than an invented one`() {
        assertNull(PacePlan.parse(""))
        assertNull(PacePlan.parse("pace:"))
        assertNull(PacePlan.parse("pace:0=3.0"))
        assertNull(PacePlan.parse("pace:0=3.0;1000=0"))
        assertNull(PacePlan.parse("pace:0=3.0;1000=2.0;500=1.0"))
        assertNull(PacePlan.parse("pace:100=3.0;1000=2.0"))
    }

    @Test
    fun `a run refuses to carry both speed sources`() {
        val plan = SpeedPlan(listOf(SpeedPlan.Step(3.0, 60.0)), repeats = 1)
        val pace = PacePlan.fromKilometreSplits(listOf(300.0, 320.0))
        val both = runCatching { MotionConfig(baseSpeedMps = 3.0, plan = plan, pacePlan = pace) }
        assertTrue("both sources must be rejected", both.isFailure)
    }

    @Test
    fun `the generated track follows the curve and still reaches the finish`() {
        // The engine must consume the curve, not ignore it: the second kilometre has to be
        // slower than the first, and the track has to cover the whole route.
        val pace = PacePlan.fromKilometreSplits(listOf(280.0, 380.0))
        val config = MotionConfig(baseSpeedMps = 3.0, pacePlan = pace, pausesPerMinute = 0.0)
        val route = RoutePath(
            listOf(
                GeoPoint(43.8256, 87.6168),
                GeoPoint(43.8256, 87.6168 + 0.025),
            ),
        )
        val samples = RouteSimulator(
            route,
            config,
            RealismToggles(),
            steadyAccuracyMeters = 5f,
        ).simulate(dtSec = 1.0, seed = 7L)
        assertTrue("track must not be empty", samples.isNotEmpty())

        fun meanSpeed(fromM: Double, toM: Double): Double {
            val slice = samples.filter { it.distanceMeters in fromM..toM }
            return if (slice.isEmpty()) 0.0 else slice.map { it.speedMps }.average()
        }

        val first = meanSpeed(300.0, 900.0)
        val second = meanSpeed(1100.0, 1700.0)
        assertTrue("first kilometre $first should be faster than second $second", first > second)
        assertTrue(
            "expected roughly 2000 m, got ${samples.last().distanceMeters}",
            abs(samples.last().distanceMeters - 2000.0) < 60.0,
        )
    }
}
