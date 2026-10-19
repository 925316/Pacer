package org.pacer.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coordinate-system transforms used in China:
 * - WGS84: GPS native, what the engine outputs
 * - GCJ02: obfuscated coordinates used by AMap/Tencent maps ("Mars coordinates")
 * - BD09:  Baidu's further rotation on top of GCJ02
 *
 * The offset algorithm is the publicly documented GCJ02 scheme; this is an
 * original implementation of that published specification, written from the
 * math, not copied from any library.
 */
object CoordinateTransform {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0 // CGCS2000 semi-major axis used by the spec
    private const val EE = 0.00669342162296594323 // first eccentricity squared

    /** Rounds of correction used by [gcj02ToWgs84]; two are enough for sub-metre. */
    private const val INVERSE_ITERATIONS = 3

    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS84 -> GCJ02. Points outside China are returned unchanged. */
    fun wgs84ToGcj02(p: GeoPoint): GeoPoint {
        if (outOfChina(p.lat, p.lon)) return p
        var dLat = transformLat(p.lon - 105.0, p.lat - 35.0)
        var dLon = transformLon(p.lon - 105.0, p.lat - 35.0)
        val radLat = p.lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return GeoPoint(p.lat + dLat, p.lon + dLon)
    }

    /**
     * GCJ02 -> BD09 (Baidu extra rotation).
     *
     * The rotation's angular perturbation is a function of the LONGITUDE, not the latitude:
     * `cos(p.lon * ...)` here and `cos(x * ...)` in [bd09ToGcj02], where x is the shifted
     * longitude. Using the latitude instead is a small difference in the perturbation and a
     * 60-metre error in Beijing - far too small to notice on a map and far too large to
     * accept in a track, which is why the round trip is pinned by a test with real numbers.
     */
    fun gcj02ToBd09(p: GeoPoint): GeoPoint {
        val z = sqrt(p.lon * p.lon + p.lat * p.lat) + 0.00002 * sin(p.lat * PI * 3000.0 / 180.0)
        val theta = kotlin.math.atan2(p.lat, p.lon) + 0.000003 * cos(p.lon * PI * 3000.0 / 180.0)
        return GeoPoint(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    /**
     * GCJ02 -> WGS84, the inverse of [wgs84ToGcj02].
     *
     * The forward transform is a fixed formula rather than a reversible one, so the
     * inverse is found by iteration: start from the biased point, measure where its
     * GCJ02 image lands, and correct by the difference. Two rounds already agree to well
     * under a metre, and the cost is nothing next to a map tap. Needed because a tap on a
     * GCJ02 map hands back a GCJ02 coordinate, while a route is stored in WGS84.
     */
    fun gcj02ToWgs84(p: GeoPoint): GeoPoint {
        if (outOfChina(p.lat, p.lon)) return p
        var guess = p
        repeat(INVERSE_ITERATIONS) {
            val projected = wgs84ToGcj02(guess)
            guess = GeoPoint(guess.lat + (p.lat - projected.lat), guess.lon + (p.lon - projected.lon))
        }
        return guess
    }

    /** WGS84 -> BD09 (convenience). */
    fun wgs84ToBd09(p: GeoPoint): GeoPoint = gcj02ToBd09(wgs84ToGcj02(p))

    /**
     * BD09 -> GCJ02, the inverse of [gcj02ToBd09].
     *
     * Written out rather than iterated because this rotation IS invertible in closed form:
     * it is a rotation plus a small radial and angular perturbation, and undoing it is the
     * same formula with the signs flipped. BD09 exists only in China, so there is no
     * outside-China branch to worry about.
     */
    fun bd09ToGcj02(p: GeoPoint): GeoPoint {
        val x = p.lon - 0.0065
        val y = p.lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * PI * 3000.0 / 180.0)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * cos(x * PI * 3000.0 / 180.0)
        return GeoPoint(z * sin(theta), z * cos(theta))
    }

    /** BD09 -> WGS84. Baidu hands coordinates out in BD09, a route is stored in WGS84. */
    fun bd09ToWgs84(p: GeoPoint): GeoPoint = gcj02ToWgs84(bd09ToGcj02(p))

    /** Round-trip helper used by tests to bound error. */
    fun offsetMeters(a: GeoPoint, b: GeoPoint): Double = GeoMath.haversine(a, b)

    /** Degrees are compared in tests with this epsilon. */
    val DEG_EPSILON = 1e-6
    fun samePoint(a: GeoPoint, b: GeoPoint, eps: Double = DEG_EPSILON): Boolean =
        abs(a.lat - b.lat) < eps && abs(a.lon - b.lon) < eps

    /** Floor helper kept for potential grid snapping in future. */
    fun snapToGrid(p: GeoPoint, gridDeg: Double): GeoPoint =
        GeoPoint(floor(p.lat / gridDeg) * gridDeg, floor(p.lon / gridDeg) * gridDeg)
}
