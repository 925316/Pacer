package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoordinateTransformTest {

    @Test
    fun `points outside china are unchanged by wgs84 to gcj02`() {
        val ny = GeoPoint(40.7128, -74.0060)
        assertEquals(ny, CoordinateTransform.wgs84ToGcj02(ny))
    }

    @Test
    fun `beijing point shifts by hundreds of meters`() {
        val wgs = GeoPoint(39.9042, 116.4074)
        val gcj = CoordinateTransform.wgs84ToGcj02(wgs)
        val shift = GeoMath.haversine(wgs, gcj)
        assertTrue("shift=${shift}m", shift in 100.0..1000.0)
    }

    @Test
    fun `gcj02 offset is deterministic`() {
        val wgs = GeoPoint(39.9042, 116.4074)
        val a = CoordinateTransform.wgs84ToGcj02(wgs)
        val b = CoordinateTransform.wgs84ToGcj02(wgs)
        assertEquals(a, b)
    }

    @Test
    fun `gcj02 to wgs84 undoes the forward transform`() {
        // This is what a tap on a GCJ02 map has to go through before the route can store
        // it, so the two directions have to agree to well under a metre.
        val wgs = GeoPoint(39.9042, 116.4074)
        val back = CoordinateTransform.gcj02ToWgs84(CoordinateTransform.wgs84ToGcj02(wgs))
        val error = GeoMath.haversine(wgs, back)
        assertTrue("round-trip error=${error}m", error < 0.05)
    }

    @Test
    fun `gcj02 to wgs84 leaves points outside china alone`() {
        val ny = GeoPoint(40.7128, -74.0060)
        assertEquals(ny, CoordinateTransform.gcj02ToWgs84(ny))
    }

    @Test
    fun `bd09 is farther from wgs84 than gcj02`() {
        val wgs = GeoPoint(39.9042, 116.4074)
        val gcj = CoordinateTransform.wgs84ToGcj02(wgs)
        val bd = CoordinateTransform.wgs84ToBd09(wgs)
        val dGcj = GeoMath.haversine(wgs, gcj)
        val dBd = GeoMath.haversine(wgs, bd)
        assertTrue(dBd > dGcj)
    }

    @Test
    fun `bd09 for Beijing has the published value`() {
        // A FIXED NUMBER, because every other test in this file only checks that the
        // transform is nonzero, deterministic or self-inverse - and a transform can be all
        // three and still wrong. This one was: the BD09 rotation perturbed the angle by
        // cos(latitude) instead of cos(longitude), which shifted Beijing by 66 m. Nothing
        // caught it until a tile grid was drawn from these coordinates and compared with a
        // tile the map service actually served.
        val wgs = GeoPoint(39.9042, 116.4074)
        val bd = CoordinateTransform.wgs84ToBd09(wgs)
        assertEquals(39.9118653, bd.lat, 1e-6)
        assertEquals(116.4200463, bd.lon, 1e-6)
    }

    @Test
    fun `bd09 to gcj02 inverts the forward rotation`() {
        // The pair has to agree; the forward being right does not make the inverse right, and
        // a tap on a Baidu map is an inverse.
        for (wgs in listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(31.2304, 121.4737),
            GeoPoint(23.1291, 113.2644),
            GeoPoint(43.8256, 87.6168),
        )) {
            val bd = CoordinateTransform.wgs84ToBd09(wgs)
            val back = CoordinateTransform.bd09ToWgs84(bd)
            val error = GeoMath.haversine(wgs, back)
            assertTrue("wgs=$wgs bd=$bd back=$back error=${error}m", error < 0.5)
        }
    }

    @Test
    fun `gcj02 shift grows away from origin pattern stays stable across cities`() {
        val cities = listOf(
            GeoPoint(39.9042, 116.4074), // Beijing
            GeoPoint(31.2304, 121.4737), // Shanghai
            GeoPoint(23.1291, 113.2644), // Guangzhou
        )
        for (c in cities) {
            val shift = GeoMath.haversine(c, CoordinateTransform.wgs84ToGcj02(c))
            assertTrue("city=$c shift=$shift", shift in 100.0..1000.0)
        }
    }

    @Test
    fun `out of china boundary point stays put`() {
        // Sydney: clearly outside the China bounding box.
        val syd = GeoPoint(-33.8688, 151.2093)
        assertEquals(syd, CoordinateTransform.wgs84ToGcj02(syd))
    }

    @Test
    fun `offsetMeters helper is symmetric`() {
        val a = GeoPoint(39.9, 116.4)
        val b = GeoPoint(39.901, 116.401)
        val ab = CoordinateTransform.offsetMeters(a, b)
        val ba = CoordinateTransform.offsetMeters(b, a)
        assertTrue(abs(ab - ba) < 1e-6)
    }

    @Test
    fun `snapToGrid aligns to requested precision`() {
        val p = GeoPoint(39.904213, 116.407421)
        val snapped = CoordinateTransform.snapToGrid(p, 0.001)
        assertTrue(abs(snapped.lat - 39.904) < 1e-9)
        assertTrue(abs(snapped.lon - 116.407) < 1e-9)
    }
}
