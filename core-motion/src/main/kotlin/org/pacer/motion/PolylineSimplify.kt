// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

/**
 * Douglas-Peucker polyline simplification. Removes intermediate points whose
 * perpendicular distance to a straight chord is below [epsilonMeters], keeping
 * corners and endpoints. Pure Kotlin, unit-testable.
 */
object PolylineSimplify {

    fun simplify(points: List<GeoPoint>, epsilonMeters: Double): List<GeoPoint> {
        if (points.size <= 2) return points.toList()
        // Find the point farthest from the chord [first, last].
        var maxDist = 0.0
        var maxIdx = 0
        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], points[0], points.last())
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        return if (maxDist <= epsilonMeters) {
            listOf(points.first(), points.last())
        } else {
            val left = simplify(points.subList(0, maxIdx + 1), epsilonMeters)
            val right = simplify(points.subList(maxIdx, points.size), epsilonMeters)
            left.dropLast(1) + right
        }
    }

    private fun perpendicularDistance(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        // Local flat metres.
        val cosLat = kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        val metPerDegLat = 111320.0
        val metPerDegLon = metPerDegLat * cosLat
        val ax = (a.lon - p.lon) * metPerDegLon
        val ay = (a.lat - p.lat) * metPerDegLat
        val bx = (b.lon - p.lon) * metPerDegLon
        val by = (b.lat - p.lat) * metPerDegLat
        val abx = bx - ax
        val aby = by - ay
        val area = kotlin.math.abs(ax * by - ay * bx)
        return area / kotlin.math.sqrt(abx * abx + aby * aby) // not perfect but scale-invariant enough for threshold
    }
}
