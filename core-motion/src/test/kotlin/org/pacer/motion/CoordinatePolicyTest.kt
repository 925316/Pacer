package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatePolicyTest {

    @Test
    fun `without an override the API default wins`() {
        assertEquals(
            CoordinateSystem.GCJ02,
            CoordinatePolicy.resolve("com.any.app", emptyList(), CoordinateSystem.GCJ02),
        )
    }

    @Test
    fun `an override replaces the API default`() {
        val overrides = listOf(CoordinateOverride("com.example.map", CoordinateSystem.WGS84))
        assertEquals(
            CoordinateSystem.WGS84,
            CoordinatePolicy.resolve("com.example.map", overrides, CoordinateSystem.GCJ02),
        )
    }

    @Test
    fun `a sub-process inherits its package's override`() {
        // "com.example.app:remote" is the same app; a user choosing a datum for an app
        // does not know which process it runs in.
        val overrides = listOf(CoordinateOverride("com.example.app", CoordinateSystem.BD09))
        assertEquals(
            CoordinateSystem.BD09,
            CoordinatePolicy.resolve("com.example.app:remote", overrides, CoordinateSystem.WGS84),
        )
    }

    @Test
    fun `a package name that merely starts the same is not a match`() {
        // "com.example.app2" must not inherit "com.example.app".
        val overrides = listOf(CoordinateOverride("com.example.app", CoordinateSystem.BD09))
        assertEquals(
            CoordinateSystem.WGS84,
            CoordinatePolicy.resolve("com.example.app2", overrides, CoordinateSystem.WGS84),
        )
    }

    @Test
    fun `an AUTO override falls back to the API default`() {
        val overrides = listOf(CoordinateOverride("com.example.app", CoordinateSystem.AUTO))
        assertEquals(
            CoordinateSystem.GCJ02,
            CoordinatePolicy.resolve("com.example.app", overrides, CoordinateSystem.GCJ02),
        )
    }

    @Test
    fun `converting to the engine's own datum changes nothing`() {
        val point = GeoPoint(39.9042, 116.4074)
        assertEquals(point, CoordinatePolicy.convert(point, CoordinateSystem.WGS84))
        assertEquals(point, CoordinatePolicy.convert(point, CoordinateSystem.AUTO))
    }

    @Test
    fun `a Chinese datum shifts a Beijing point by hundreds of metres`() {
        val point = GeoPoint(39.9042, 116.4074)
        val gcj = CoordinatePolicy.convert(point, CoordinateSystem.GCJ02)
        val moved = GeoMath.haversine(point, gcj)
        assertTrue("GCJ-02 shift was $moved m", moved in 100.0..800.0)

        val bd = CoordinatePolicy.convert(point, CoordinateSystem.BD09)
        assertTrue("BD-09 shift was ${GeoMath.haversine(point, bd)} m", GeoMath.haversine(point, bd) > moved)
    }

    @Test
    fun `a datum applied outside China leaves the point alone`() {
        // The GCJ-02 algorithm is an identity outside its boundary polygon. Applying an
        // offset there would invent a shift no client applies.
        val london = GeoPoint(51.5074, -0.1278)
        val converted = CoordinatePolicy.convert(london, CoordinateSystem.GCJ02)
        assertEquals(london.lat, converted.lat, 1e-9)
        assertEquals(london.lon, converted.lon, 1e-9)
    }

    @Test
    fun `the override table round-trips through its text form`() {
        val text = "com.a.app=GCJ02;com.b.app=WGS84;com.c=BD09"
        val parsed = CoordinatePolicy.parse(text)
        assertEquals(3, parsed.size)
        assertEquals(CoordinateSystem.GCJ02, parsed[0].system)
        assertEquals("com.c", parsed[2].packageName)
        assertEquals(text, CoordinatePolicy.format(parsed))
    }

    @Test
    fun `a malformed table entry is skipped without losing the rest`() {
        // Hand-typed field: one bad entry must not discard the others.
        val parsed = CoordinatePolicy.parse(
            "com.good.app=GCJ02; ; broken ;=WGS84;com.missing=;com.bad.datum=GCJ03;com.also.good=BD09",
        )
        assertEquals(listOf("com.good.app", "com.also.good"), parsed.map { it.packageName })
    }

    @Test
    fun `the first entry for a package wins`() {
        val parsed = CoordinatePolicy.parse("com.a=GCJ02;com.a=WGS84")
        assertEquals(1, parsed.size)
        assertEquals(CoordinateSystem.GCJ02, parsed[0].system)
    }

    @Test
    fun `datum names are case insensitive`() {
        assertEquals(CoordinateSystem.BD09, CoordinateSystem.byName("bd09"))
        assertEquals(CoordinateSystem.WGS84, CoordinateSystem.byName(" Wgs84 "))
        assertEquals(CoordinateSystem.AUTO, CoordinateSystem.byName("nonsense"))
        assertEquals(CoordinateSystem.AUTO, CoordinateSystem.byName(null))
    }

    @Test
    fun `package names are checked conservatively`() {
        assertTrue(CoordinatePolicy.isPackageName("com.example.app"))
        assertTrue(CoordinatePolicy.isPackageName("org.pacer.app"))
        assertTrue(CoordinatePolicy.isPackageName("a_b.c1"))
        assertFalse(CoordinatePolicy.isPackageName(""))
        assertFalse(CoordinatePolicy.isPackageName(".com.example"))
        assertFalse(CoordinatePolicy.isPackageName("com..example"))
        assertFalse(CoordinatePolicy.isPackageName("com.example."))
        assertFalse(CoordinatePolicy.isPackageName("com example"))
        assertFalse(CoordinatePolicy.isPackageName("com-example"))
    }
}
