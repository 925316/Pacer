package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The known-answer sample is the one published in the format's original
 * documentation, so a decoder that passes here is not merely self-consistent with
 * Pacer's encoder.
 */
class PolylineCodecTest {

    @Test
    fun `the documented sample decodes`() {
        val points = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].lat, 1e-5)
        assertEquals(-120.2, points[0].lon, 1e-5)
        assertEquals(40.7, points[1].lat, 1e-5)
        assertEquals(-120.95, points[1].lon, 1e-5)
        assertEquals(43.252, points[2].lat, 1e-5)
        assertEquals(-126.453, points[2].lon, 1e-5)
    }

    @Test
    fun `encoding round trips within the format's precision`() {
        val route = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9052, 116.4090),
            GeoPoint(39.9081, 116.4104),
        )
        val decoded = PolylineCodec.decode(PolylineCodec.encode(route))
        assertEquals(route.size, decoded.size)
        for (i in route.indices) {
            assertEquals(route[i].lat, decoded[i].lat, 1e-5)
            assertEquals(route[i].lon, decoded[i].lon, 1e-5)
        }
    }

    @Test
    fun `southern and western coordinates keep their sign`() {
        val route = listOf(GeoPoint(-33.8688, -70.6693), GeoPoint(-34.0, -70.5))
        val decoded = PolylineCodec.decode(PolylineCodec.encode(route))
        assertEquals(-33.8688, decoded[0].lat, 1e-5)
        assertEquals(-70.6693, decoded[0].lon, 1e-5)
    }

    @Test
    fun `an empty string is an empty route`() {
        assertTrue(PolylineCodec.decode("").isEmpty())
    }

    @Test
    fun `a truncated string is rejected whole`() {
        // Half a route drawn across the map is worse than none: the caller can ask the
        // routing service again, but it cannot tell a half route from a real one.
        assertTrue(PolylineCodec.decode("_p~iF~ps|U_ulLnnq").isEmpty())
    }

    @Test
    fun `an unprintable character is rejected rather than shifted`() {
        assertTrue(PolylineCodec.decode("_p~iF\u0001ps|U").isEmpty())
    }
}
