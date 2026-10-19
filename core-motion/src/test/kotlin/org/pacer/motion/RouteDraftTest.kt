package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDraftTest {

    private val a = GeoPoint(39.900, 116.400)
    private val b = GeoPoint(39.905, 116.404)
    private val c = GeoPoint(39.908, 116.410)

    @Test
    fun `empty draft is invalid and has zero length`() {
        val d = RouteDraft()
        assertEquals(0, d.size)
        assertFalse(d.isValid)
        assertEquals(0.0, d.totalLengthMeters(), 0.0)
        assertNull(d.toRoutePath())
    }

    @Test
    fun `single point is still invalid`() {
        val d = RouteDraft(listOf(a))
        assertFalse(d.isValid)
        assertNull(d.toRoutePath())
    }

    @Test
    fun `two points become a valid route`() {
        val d = RouteDraft(listOf(a, b))
        assertTrue(d.isValid)
        assertEquals(listOf(a, b), d.toRoutePath()!!.points)
    }

    @Test
    fun `two identical points are not a route`() {
        // Tapping the same spot twice is one careless tap. A zero-length route makes the
        // speed curve throw on every rebuild, so it must not count as a route at all.
        val d = RouteDraft(listOf(a, a))
        assertFalse(d.isValid)
        assertNull(d.toRoutePath())
    }

    @Test
    fun `a duplicate in the middle still leaves a route if it has length`() {
        val d = RouteDraft(listOf(a, a, b))
        assertTrue(d.isValid)
        assertEquals(GeoMath.haversine(a, b), d.totalLengthMeters(), 1e-6)
    }

    @Test
    fun `add grows the draft`() {
        val d = RouteDraft()
        d.add(a)
        d.add(b)
        assertEquals(listOf(a, b), d.points)
    }

    @Test
    fun `insertAt handles both ends and rejects out of range`() {
        val d = RouteDraft(listOf(a, c))
        assertTrue(d.insertAt(1, b))
        assertEquals(listOf(a, b, c), d.points)
        assertTrue(d.insertAt(0, GeoPoint(0.0, 0.0)))
        assertEquals(4, d.size)
        assertFalse(d.insertAt(-1, b))
        assertFalse(d.insertAt(99, b))
    }

    @Test
    fun `removeAt returns the removed point and rejects out of range`() {
        val d = RouteDraft(listOf(a, b, c))
        assertEquals(b, d.removeAt(1))
        assertEquals(listOf(a, c), d.points)
        assertNull(d.removeAt(9))
        assertNull(d.removeAt(-1))
    }

    @Test
    fun `replaceAt swaps in place`() {
        val d = RouteDraft(listOf(a, b))
        assertTrue(d.replaceAt(1, c))
        assertEquals(listOf(a, c), d.points)
        assertFalse(d.replaceAt(5, c))
    }

    @Test
    fun `move reorders the waypoints`() {
        val d = RouteDraft(listOf(a, b, c))
        assertTrue(d.move(0, 2))
        assertEquals(listOf(b, c, a), d.points)
        assertFalse(d.move(0, 0))
        assertFalse(d.move(0, 9))
    }

    @Test
    fun `moveUp and moveDown respect the ends`() {
        val d = RouteDraft(listOf(a, b, c))
        assertTrue(d.moveUp(2))
        assertEquals(listOf(a, c, b), d.points)
        assertTrue(d.moveDown(0))
        assertEquals(listOf(c, a, b), d.points)
        assertFalse(d.moveUp(0))
        assertFalse(d.moveDown(2))
    }

    @Test
    fun `clear empties the draft`() {
        val d = RouteDraft(listOf(a, b, c))
        d.clear()
        assertEquals(0, d.size)
        assertFalse(d.isValid)
    }

    @Test
    fun `length matches the RoutePath it produces`() {
        val d = RouteDraft(listOf(a, b, c))
        val route = d.toRoutePath()!!
        assertEquals(route.totalLengthMeters, d.totalLengthMeters(), 1e-6)
    }

    @Test
    fun `json round trip survives the shared wire format`() {
        val d = RouteDraft(listOf(a, b, c))
        val restored = RouteDraft.parse(d.toJson())
        assertEquals(d.points, restored.points)
    }

    @Test
    fun `parse of malformed text yields an empty draft`() {
        assertEquals(0, RouteDraft.parse("not json").size)
        assertEquals(0, RouteDraft.parse("").size)
    }

    @Test
    fun `exposed points view cannot be mutated`() {
        val d = RouteDraft(listOf(a, b))
        try {
            @Suppress("UNCHECKED_CAST")
            (d.points as MutableList<GeoPoint>).add(c)
            throw AssertionError("expected the exposed view to be read-only")
        } catch (_: UnsupportedOperationException) {
            // expected
        }
    }
}
