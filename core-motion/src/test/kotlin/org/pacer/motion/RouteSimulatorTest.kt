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
        assertTrue(minLat in -0.0001..0.0001)
        assertTrue(maxLat in -0.0001..0.0001)
    }

    @Test
    fun `different seeds give different curves`() {
        val a = sim(1L)
        val b = sim(2L)
        val diff = a.zip(b).count { (x, y) -> kotlin.math.abs(x.speedMps - y.speedMps) > 1e-6 }
        assertTrue(diff > 0)
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
        // Strictly before the corner: at the corner itself the cap is already off again,
        // because the point being looked at from there is past the turn.
        val approach = fixes.filter { it.distanceMeters >= cornerAt - 20.0 && it.distanceMeters < cornerAt }
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
}
