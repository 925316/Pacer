package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {

    private val route = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)))

    private fun sim(seed: Long = 42L) = RouteSimulator(
        route = route,
        config = MotionConfig.run(),
    ).simulate(dtSec = 1.0, seed = seed)

    @Test
    fun `samples are ordered in time`() {
        val out = sim()
        assertTrue(out.size > 2)
        for (i in 1 until out.size) {
            assertTrue(out[i].tSec > out[i - 1].tSec)
        }
    }

    @Test
    fun `cumulative distance reaches route end`() {
        val out = sim()
        assertEquals(0.0, out.first().distanceMeters, 1e-9)
        val total = route.totalLengthMeters
        assertTrue(total > 0)
        assertEquals(total, out.last().distanceMeters, total * 0.02)
    }

    @Test
    fun `cumulative steps are monotonic`() {
        val out = sim()
        for (i in 1 until out.size) {
            assertTrue(out[i].cumulativeSteps >= out[i - 1].cumulativeSteps)
        }
        assertTrue(out.last().cumulativeSteps > 0)
    }

    @Test
    fun `points stay on route bounds`() {
        val out = sim()
        val minLat = out.minOf { it.point.lat }
        val maxLat = out.maxOf { it.point.lat }
        // The route is the equator segment and the reported point now carries up to a few
        // metres of receiver noise, so the bound is that noise rather than exact zero.
        assertTrue("minLat=$minLat", minLat in -0.0001..0.0001)
        assertTrue("maxLat=$maxLat", maxLat in -0.0001..0.0001)
    }

    @Test
    fun `different seeds give different curves`() {
        val a = sim(1L)
        val b = sim(2L)
        val diff = a.zip(b).count { (x, y) -> kotlin.math.abs(x.speedMps - y.speedMps) > 1e-6 }
        assertTrue(diff > 0)
    }

    @Test
    fun `the same seed rebuilds the same run`() {
        // This is the property the app's live marker relies on: it rebuilds the run from
        // the same seed as the module instead of reading the module's state. The whole
        // sample has to match, not only the position, or the two screens would describe
        // different fixes for the same second.
        assertEquals(sim(1234L), sim(1234L))
    }

    @Test
    fun `the sky belongs to the run rather than to the process`() {
        val a = sim(1L)
        val b = sim(2L)
        val differs = a.zip(b).any { (x, y) ->
            x.satellites != y.satellites || x.accuracyMeters != y.accuracyMeters
        }
        assertTrue("signal traits were seeded independently of the run", differs)
    }

    @Test
    fun `cadence jitter still varies between runs`() {
        // Pinning the jitter to the run's seed must not turn it off: two runs have to
        // report different cadences for the same speed, or the step stream becomes as
        // machine-made as the fixed seed was.
        val a = sim(3L).map { it.cadenceSpm }
        val b = sim(4L).map { it.cadenceSpm }
        assertTrue("cadence was identical across two runs", a.zip(b).any { (x, y) -> x != y })
    }

    @Test
    fun `cadence is zero when paused`() {
        val out = sim()
        val paused = out.filter { it.phase == MotionPhase.PAUSE }
        paused.forEach { assertEquals(0.0, it.cadenceSpm, 1e-9) }
    }

    /** Two legs at a right angle: a 90 degree turn, the case the turn model brakes hardest. */
    private val cornerRoute = RoutePath(
        listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.05), GeoPoint(0.05, 0.05)),
    )

    @Test
    fun `speed is capped on the approach to a corner`() {
        val base = 3.0
        // Pauses are switched off for this test: a pause's own deceleration puts fast
        // samples in the window for a reason that has nothing to do with the corner, and a
        // runner stopping for a light does not also have to brake for the turn.
        val config = MotionConfig(baseSpeedMps = base, pausesPerMinute = 0.0)
        val fixes = RouteSimulator(route = cornerRoute, config = config)
            .simulate(dtSec = 1.0, seed = 6L)
        val cornerAt = cornerRoute.totalLengthMeters / 2
        // Strictly inside the approach, and not right at either edge. A sample's speed is
        // computed at the distance it had BEFORE that step, while its recorded distance is
        // the one after, so the first sample whose recorded distance falls in the window was
        // still computed just outside it - legitimately uncapped - and the corner itself is
        // already past the turn.
        val approach = fixes.filter { it.distanceMeters >= cornerAt - 15.0 && it.distanceMeters < cornerAt }
        assertTrue(approach.isNotEmpty())
        // The turn model's floor times the cruise noise stays well under the uncapped
        // speed, so a missing cap shows up here as a sample at full speed.
        val fastest = approach.maxOf { it.speedMps }
        assertTrue("fastest=$fastest", fastest < base * 0.6)
    }

    @Test
    fun `braking into turns still completes the route`() {
        val fixes = RouteSimulator(route = cornerRoute, config = MotionConfig(baseSpeedMps = 3.0))
            .simulate(dtSec = 1.0, seed = 7L)
        val total = cornerRoute.totalLengthMeters
        assertEquals(total, fixes.last().distanceMeters, total * 0.02)
    }

    @Test
    fun `a route of one repeated point produces a standing run`() {
        // The editor can produce this with two taps on the same spot. The speed curve is
        // defined on a positive distance and would throw, so the simulator has to answer
        // with a subject that is standing still instead of an exception on every callback.
        val nowhere = RoutePath(listOf(GeoPoint(1.0, 2.0), GeoPoint(1.0, 2.0)))
        val fixes = RouteSimulator(nowhere, MotionConfig.run()).simulate(dtSec = 1.0, seed = 3L)
        assertTrue(fixes.isNotEmpty())
        assertTrue(fixes.all { it.speedMps == 0.0 })
        assertTrue(fixes.all { it.phase == MotionPhase.IDLE })
        assertTrue(fixes.all { it.cumulativeSteps == 0L })
        // Standing still is not the same as reporting the identical double every second:
        // the fix wanders within a receiver's accuracy, and never teleports away from the
        // spot it is standing on. A repeated coordinate is the shape a module makes.
        assertTrue("the standing fix never moved", fixes.map { it.point }.distinct().size > 1)
        assertTrue(
            "the standing fix left the spot",
            fixes.all { GeoMath.haversine(it.point, GeoPoint(1.0, 2.0)) < 5.0 },
        )
        // The signal side still varies with the clock: a standing subject reports a real
        // fix, and accuracy warming up is what a receiver does.
        assertTrue(fixes.last().accuracyMeters <= fixes.first().accuracyMeters)
    }

    @Test
    fun `a standing run is still a pure function of its seed`() {
        val nowhere = RoutePath(listOf(GeoPoint(1.0, 2.0), GeoPoint(1.0, 2.0)))
        fun run(seed: Long) = RouteSimulator(nowhere, MotionConfig.run()).simulate(dtSec = 1.0, seed = seed)
        assertEquals(run(9L), run(9L))
    }

    // --- position noise -----------------------------------------------------

    private fun offLineMeters(p: GeoPoint): Double {
        // Distance from the equator segment `route` runs along.
        return GeoMath.haversine(p, GeoPoint(0.0, p.lon))
    }

    @Test
    fun `position noise puts the reported point off the line`() {
        val fixes = RouteSimulator(route = route, config = MotionConfig.run())
            .simulate(dtSec = 1.0, seed = 11L)
        val moving = fixes.filter { it.distanceMeters > 50.0 }
        assertTrue(moving.isNotEmpty())
        // Every sample deviating is what a band means; a single lucky sample proves nothing.
        val off = moving.map { offLineMeters(it.point) }
        assertTrue("no sample left the line: max=${off.maxOrNull()}", off.count { it > 1e-6 } > moving.size / 2)
        // And it stays a plausible receiver error, not a coordinate bug.
        assertTrue("max offset=${off.maxOrNull()}", off.maxOrNull()!! < 5.0)
    }

    @Test
    fun `position noise does not change the distance or the speed`() {
        // The run's own numbers describe the route it claims to have covered; the noise is
        // where it said it was, not how far it went. Turning it off must not move them.
        fun run(noise: Boolean) = RouteSimulator(
            route = route,
            config = MotionConfig.run(),
            toggles = RealismToggles(positionNoise = noise),
        ).simulate(dtSec = 1.0, seed = 12L)

        val noisy = run(true)
        val clean = run(false)
        assertEquals(clean.size, noisy.size)
        for (i in clean.indices) {
            assertEquals(clean[i].distanceMeters, noisy[i].distanceMeters, 1e-9)
            assertEquals(clean[i].speedMps, noisy[i].speedMps, 1e-9)
            assertEquals(clean[i].cumulativeSteps, noisy[i].cumulativeSteps)
            assertEquals(clean[i].bearingDeg, noisy[i].bearingDeg, 1e-6f)
        }
    }

    @Test
    fun `position noise can be switched off for an exact track`() {
        val fixes = RouteSimulator(
            route = route,
            config = MotionConfig.run(),
            toggles = RealismToggles(positionNoise = false),
        ).simulate(dtSec = 1.0, seed = 13L)
        assertTrue(fixes.all { it.point.lat == 0.0 })
    }

    @Test
    fun `position noise is a pure function of the seed`() {
        // The app's live marker rebuilds this run from the same seed as the module and
        // expects the same positions; a noise stream that ignored the seed would break it.
        fun run(seed: Long) = RouteSimulator(route = route, config = MotionConfig.run())
            .simulate(dtSec = 1.0, seed = seed)
        assertEquals(run(14L), run(14L))
        assertTrue(run(14L).map { it.point } != run(15L).map { it.point })
    }
}
