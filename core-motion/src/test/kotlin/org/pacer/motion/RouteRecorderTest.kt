package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRecorderTest {

    /**
     * A point [meters] due north of [from], using the same sphere [GeoMath.haversine]
     * measures on.
     *
     * The first version of this helper used 111320 m/degree, which is the local
     * approximation the environment model uses, not the sphere the distance function
     * uses. A "5 m" step was really 4.97 m, so a threshold test that ought to keep the
     * 5 m point dropped it - a test that fails because its own ruler is wrong.
     */
    private fun north(from: GeoPoint, meters: Double): GeoPoint =
        GeoPoint(from.lat + Math.toDegrees(meters / GeoMath.EARTH_RADIUS_METERS), from.lon)

    private fun east(from: GeoPoint, meters: Double): GeoPoint {
        val lonScale = Math.cos(Math.toRadians(from.lat))
        return GeoPoint(from.lat, from.lon + Math.toDegrees(meters / (GeoMath.EARTH_RADIUS_METERS * lonScale)))
    }

    private val start = GeoPoint(43.8256, 87.6168)

    @Test
    fun `the first point is always kept`() {
        val recorder = RouteRecorder()
        assertTrue(recorder.offer(start))
        assertEquals(1, recorder.size)
        assertFalse("one point is not a route", recorder.isValid)
    }

    @Test
    fun `a straight walk keeps one point per step threshold`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        // Twelve metres north in 1.5 m nudges: the 6 m and 12 m marks are kept, and
        // nothing in between, because no nudge after a kept point reaches 5 m until then.
        // Deliberately not a multiple of the threshold landing exactly on 5.0: a test
        // that depends on a floating-point comparison landing on the boundary is a test
        // of the FPU, not of the rule.
        for (i in 1..8) recorder.offer(north(start, i * 1.5))
        assertEquals(3, recorder.size)
        assertEquals(12.0, recorder.distanceMeters, 0.05)
    }

    @Test
    fun `a sharp corner is kept even though it is a short step`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        recorder.offer(north(start, 6.0))
        // Turn east after only 2 m: a 90 degree corner must not be rounded away.
        val corner = north(start, 8.0)
        val after = east(corner, 3.0)
        assertTrue(recorder.offer(after))
        assertEquals(3, recorder.size)
    }

    @Test
    fun `a gentle bend at a short step is dropped`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        recorder.offer(north(start, 6.0))
        // A 2 m step that turns only ~10 degrees is neither far enough nor a corner.
        val base = north(start, 6.0)
        val tenDegrees = Math.toRadians(10.0)
        val nudged = GeoPoint(
            base.lat + Math.toDegrees(2.0 * Math.cos(tenDegrees) / GeoMath.EARTH_RADIUS_METERS),
            base.lon + Math.toDegrees(
                2.0 * Math.sin(tenDegrees) /
                    (GeoMath.EARTH_RADIUS_METERS * Math.cos(Math.toRadians(base.lat))),
            ),
        )
        assertFalse(recorder.offer(nudged))
        assertEquals(2, recorder.size)
    }

    @Test
    fun `a forced flush keeps a short final move`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        recorder.offer(north(start, 10.0))
        // The subject stopped 0.8 m after the last kept point: without the forced flush
        // the route would end where it last crossed a threshold, not where it stopped.
        assertTrue(recorder.offer(north(start, 10.8), force = true))
        assertEquals(3, recorder.size)
    }

    @Test
    fun `repeating the same point is ignored`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        assertFalse(recorder.offer(start))
        assertEquals(1, recorder.size)
        assertEquals(0.0, recorder.distanceMeters, 1e-12)
    }

    @Test
    fun `a finished recording becomes a route and a draft`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        recorder.offer(north(start, 10.0))
        assertTrue(recorder.finish(north(start, 20.0)))
        assertNotNull(recorder.toRoutePath())
        assertEquals(3, recorder.toDraft().size)
        // Length is the sum of the kept segments, not of every offered sample.
        assertEquals(20.0, recorder.distanceMeters, 0.3)
    }

    @Test
    fun `a recording that never moved is not a route`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        assertFalse(recorder.finish(start))
        assertNull(recorder.toRoutePath())
    }

    @Test
    fun `clearing starts over`() {
        val recorder = RouteRecorder()
        recorder.offer(start)
        recorder.offer(north(start, 10.0))
        recorder.clear()
        assertEquals(0, recorder.size)
        assertEquals(0.0, recorder.distanceMeters, 1e-12)
        assertFalse(recorder.isValid)
    }

    @Test
    fun `the kept corner keeps the route length close to the walked path`() {
        // North 120 m, then east 120 m, sampled every 6 m so every sample is over the
        // keep distance and none of them is dropped. The length must come out as the
        // walked length, not short by the segments that were skipped.
        val recorder = RouteRecorder()
        recorder.offer(start)
        for (i in 1..20) recorder.offer(north(start, i * 6.0))
        val corner = north(start, 120.0)
        for (i in 1..20) recorder.offer(east(corner, i * 6.0))
        assertEquals(240.0, recorder.distanceMeters, 1.0)
    }
}
