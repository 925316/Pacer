package org.pacer.motion

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable geographic point.
 */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * Minimal route geometry, all original math:
 * haversine distance, point-at-distance interpolation, and polyline sampling
 * with bearings. Pure Kotlin, no Android or map SDK dependency.
 */
object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s1 = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(s1), sqrt(1 - s1))
    }

    fun bearing(a: GeoPoint, b: GeoPoint): Float {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return Math.toDegrees(atan2(y, x)).toFloat().let { if (it < 0) (it + 360f) else it }
    }

    /**
     * Interpolate a point [fraction] of the way from [a] to [b].
     * Uses linear interpolation on lat/lon, accurate enough for short segments.
     */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val f = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }
}

/**
 * A polyline of waypoints with cached cumulative segment lengths.
 */
class RoutePath(points: List<GeoPoint>) {
    val points: List<GeoPoint> = points.toList()
    private val cumLen: List<Double>

    init {
        require(this.points.size >= 2) { "route needs at least 2 points" }
        val acc = ArrayList<Double>(this.points.size)
        var total = 0.0
        acc.add(0.0)
        for (i in 1 until this.points.size) {
            total += GeoMath.haversine(this.points[i - 1], this.points[i])
            acc.add(total)
        }
        cumLen = acc
    }

    val totalLengthMeters: Double get() = cumLen.last()

    /**
     * Point and bearing at [distanceMeters] along the route; clamps to endpoints.
     */
    fun pointAt(distanceMeters: Double): Pair<GeoPoint, Float> {
        val d = distanceMeters.coerceIn(0.0, totalLengthMeters)
        // Binary search for the segment containing d.
        var lo = 0
        var hi = cumLen.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cumLen[mid] <= d) lo = mid else hi = mid
        }
        val segLen = cumLen[lo + 1] - cumLen[lo]
        val frac = if (segLen <= 1e-9) 0.0 else (d - cumLen[lo]) / segLen
        val p = GeoMath.interpolate(points[lo], points[lo + 1], frac)
        val brg = GeoMath.bearing(points[lo], points[lo + 1])
        return p to brg
    }
}
