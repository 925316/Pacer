// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Immutable geographic point.
 */
@Serializable
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

    fun toJson(): String = RoutePath.toJson(this)

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

    /**
     * Closest point on the route to [target], as distance-along-route plus the
     * perpendicular offset in meters. Linear scan over segments: fine for
     * typical campus routes (tens to low hundreds of waypoints).
     *
     * Used by UI snapping: tap a position on the map, get the nearest spot on
     * the route and how far off it was.
     */
    fun nearestPoint(target: GeoPoint): NearestOnRoute {
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestPoint = points.first()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // Approximate the projection in a local flat frame (meters).
            val lonScale = metersPerDegLon(target.lat)
            val ax = (a.lon - target.lon) * lonScale
            val ay = (a.lat - target.lat) * METERS_PER_DEG_LAT
            val bx = (b.lon - target.lon) * lonScale
            val by = (b.lat - target.lat) * METERS_PER_DEG_LAT
            val abx = bx - ax
            val aby = by - ay
            val abLen2 = abx * abx + aby * aby
            val t = if (abLen2 <= 1e-12) 0.0 else (-(ax * abx + ay * aby) / abLen2).coerceIn(0.0, 1.0)
            val px = ax + abx * t
            val py = ay + aby * t
            val d = kotlin.math.sqrt(px * px + py * py)
            if (d < bestDist) {
                bestDist = d
                bestAlong = cumLen[i] + (cumLen[i + 1] - cumLen[i]) * t
                bestPoint = GeoMath.interpolate(a, b, t)
            }
        }
        return NearestOnRoute(bestPoint, bestAlong, bestDist)
    }

    data class NearestOnRoute(
        val point: GeoPoint,
        val distanceAlongMeters: Double,
        val offsetMeters: Double,
    )

    companion object {
        private val json = Json { prettyPrint = false }

        fun fromPoints(points: List<GeoPoint>): RoutePath = RoutePath(points)

        fun fromJson(text: String): RoutePath =
            RoutePath(Json.decodeFromString(text))

        fun toJson(route: RoutePath): String =
            Json.encodeToString(route.points)

        fun toJson(points: List<GeoPoint>): String = Json.encodeToString(points)
    }
}
