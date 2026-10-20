package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRecorderTest {

    /** A point [meters] due north of [from]; north makes the arithmetic exact enough. */
    private fun north(from: GeoPoint, meters: Double): GeoPoint =
        GeoPoint(from.lat + meters / 111_320.0, from.lon)

    private fun east(from: GeoPoint, meters: Double): GeoPoint =
        GeoPoint(from.lat, from.lon + meters / (111_320.0 * Math.cos(Math.toRadians(from.lat))))

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
        // Ten metres north in one-metre nudges: only the 5 m marks are kept.
        for (i in 1..10) recorder.offer(north(start, i.toDouble()))
        // 5 m and 10 m, plus the start.
        assertEquals(3, recorder.size)
        assertEquals(10.0, recorder.distanceMeters, 0.2)
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
        // Ten degrees off north is not a corner, and 2 m is under the keep distance.
        val base = north(start, 6.0)
        val slight = GeoPoint(base.lat + 2.0 / 111_320.0, base.lon + 0.35 / (111_320.0 * 0.72))
        assertFalse(recorder.offer(slight))
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
        // North 100 m, then east 100 m. Sampling coarsely still has to preserve the
        // length within a couple of metres, otherwise the simulated run would be short.
        val recorder = RouteRecorder()
        recorder.offer(start)
        for (i in 1..20) recorder.offer(north(start, i * 5.0))
        val corner = north(start, 100.0)
        for (i in 1..20) recorder.offer(east(corner, i * 5.0))
        assertEquals(200.0, recorder.distanceMeters, 2.0)
    }
}
