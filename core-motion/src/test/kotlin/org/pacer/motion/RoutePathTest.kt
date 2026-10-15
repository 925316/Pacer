package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {

    private fun squareRoute() = RoutePath(
        listOf(
            GeoPoint(39.9, 116.4),
            GeoPoint(39.9, 116.401),
            GeoPoint(39.901, 116.401),
            GeoPoint(39.901, 116.4),
        ),
    )

    @Test
    fun `haversine of identical points is zero`() {
        val p = GeoPoint(30.0, 120.0)
        assertEquals(0.0, GeoMath.haversine(p, p), 1e-9)
    }

    @Test
    fun `haversine matches known distance within 1 percent`() {
        // ~111.2m for 0.001 degree latitude anywhere.
        val d = GeoMath.haversine(GeoPoint(39.0, 116.0), GeoPoint(39.001, 116.0))
        assertEquals(111.2, d, 1.12)
    }

    @Test
    fun `route total length equals sum of segments`() {
        val route = squareRoute()
        assertTrue(route.totalLengthMeters > 0)
        val manual = (1 until route.points.size).sumOf {
            GeoMath.haversine(route.points[it - 1], route.points[it])
        }
        assertEquals(manual, route.totalLengthMeters, 1e-6)
    }

    @Test
    fun `pointAt clamps to start and end`() {
        val route = squareRoute()
        val (start, _) = route.pointAt(-5.0)
        assertEquals(route.points.first(), start)
        val (end, _) = route.pointAt(route.totalLengthMeters + 100.0)
        assertEquals(route.points.last(), end)
    }

    @Test
    fun `midpoint lies on segment`() {
        val route = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)))
        val (mid, brg) = route.pointAt(route.totalLengthMeters / 2)
        assertEquals(0.0, mid.lat, 1e-6)
        assertEquals(0.0005, mid.lon, 1e-6)
        assertEquals(90f, brg, 1.0f)
    }

    @Test
    fun `bearing of east segment is about 90 deg`() {
        val b = GeoMath.bearing(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))
        assertEquals(90f, b, 1.0f)
    }
}
