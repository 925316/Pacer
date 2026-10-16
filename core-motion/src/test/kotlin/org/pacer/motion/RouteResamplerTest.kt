package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResamplerTest {

    /** A straight north-south line: haversine is linear in latitude here. */
    private fun meridianRoute(latEnd: Double): List<GeoPoint> =
        listOf(GeoPoint(0.0, 0.0), GeoPoint(latEnd, 0.0))

    private fun consecutiveDistances(points: List<GeoPoint>): List<Double> =
        (1 until points.size).map { GeoMath.haversine(points[it - 1], points[it]) }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(RouteResampler.resample(emptyList(), 5.0).isEmpty())
    }

    @Test
    fun `single point is returned unchanged`() {
        val out = RouteResampler.resample(listOf(GeoPoint(1.0, 2.0)), 5.0)
        assertEquals(1, out.size)
        assertEquals(GeoPoint(1.0, 2.0), out[0])
    }

    @Test
    fun `all duplicate points collapse to one`() {
        val out = RouteResampler.resample(
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0)),
            10.0,
        )
        assertEquals(listOf(GeoPoint(0.0, 0.0)), out)
    }

    @Test
    fun `straight line count matches total length over spacing`() {
        val points = meridianRoute(0.1)
        val total = GeoMath.haversine(points[0], points[1])
        val spacing = 1000.0
        val out = RouteResampler.resample(points, spacing)
        val expectedCount = kotlin.math.ceil(total / spacing).toInt() + 1
        assertEquals(expectedCount, out.size)
        // Every step but the final short one is exactly the requested spacing.
        val dists = consecutiveDistances(out)
        for (i in 0 until dists.size - 1) {
            assertEquals(spacing, dists[i], 0.01)
        }
        assertTrue(dists.last() <= spacing + 1e-6)
    }

    @Test
    fun `uneven vertices still give uniform spacing`() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.05, 0.0))
        val out = RouteResampler.resample(points, 100.0)
        val dists = consecutiveDistances(out)
        for (i in 0 until dists.size - 1) {
            assertEquals(100.0, dists[i], 0.1)
        }
    }

    @Test
    fun `endpoints are preserved exactly`() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
            GeoPoint(0.001, 0.002),
        )
        val out = RouteResampler.resample(points, 40.0)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
    }

    @Test
    fun `duplicate consecutive points do not create zero-length steps`() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.01),
            GeoPoint(0.0, 0.01),
        )
        val out = RouteResampler.resample(points, 100.0)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
        val dists = consecutiveDistances(out)
        assertTrue("all steps must be positive: $dists", dists.all { it > 1e-6 })
    }

    @Test
    fun `route shorter than one spacing returns endpoints only`() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        val out = RouteResampler.resample(points, 1000.0)
        assertEquals(2, out.size)
        assertEquals(points.first(), out.first())
        assertEquals(points.last(), out.last())
    }

    @Test
    fun `arc spacing stays uniform across corners`() {
        val route = RoutePath(
            listOf(
                GeoPoint(0.0, 0.0),
                GeoPoint(0.0, 0.001),
                GeoPoint(0.001, 0.001),
                GeoPoint(0.001, 0.002),
            ),
        )
        val out = RouteResampler.resample(route.points, 25.0)
        val along = out.map { route.nearestPoint(it).distanceAlongMeters }
        for (i in 1 until along.size - 1) {
            assertEquals(25.0, along[i] - along[i - 1], 1.0)
        }
    }

    @Test
    fun `cumulativeDistances is a prefix sum`() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001))
        val cum = RouteResampler.cumulativeDistances(points)
        assertEquals(3, cum.size)
        assertEquals(0.0, cum[0], 1e-12)
        assertEquals(GeoMath.haversine(points[0], points[1]), cum[1], 1e-9)
        assertEquals(cum[1] + GeoMath.haversine(points[1], points[2]), cum[2], 1e-9)
    }

    @Test
    fun `cumulativeDistances handles empty and single inputs`() {
        assertEquals(0, RouteResampler.cumulativeDistances(emptyList()).size)
        val single = RouteResampler.cumulativeDistances(listOf(GeoPoint(3.0, 4.0)))
        assertEquals(1, single.size)
        assertEquals(0.0, single[0], 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero spacing is rejected`() {
        RouteResampler.resample(meridianRoute(0.01), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative spacing is rejected`() {
        RouteResampler.resample(meridianRoute(0.01), -1.0)
    }
}
