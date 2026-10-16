package org.pacer.motion

/**
 * Resamples a polyline to (almost) uniform arc-length spacing.
 *
 * Original Pacer implementation. The point is to trade a one-off resampling
 * pass for a cheaper inner loop later: positions along a densely resampled
 * route are found by advancing an index instead of repeating segment
 * projection maths on every step.
 *
 * All distances use [GeoMath.haversine]; interpolation between measured
 * vertices reuses [GeoMath.interpolate], so the output is consistent with
 * [RoutePath].
 */
object RouteResampler {

    /** Distances at or below this are treated as a zero-length step. */
    private const val EPS_METERS = 1e-9

    /**
     * Closed-form prefix distances of [points]: result[0] == 0 and result[i]
     * is the summed haversine length of points[0..i]. Returns an empty array
     * for an empty input and a single [0.0] for a one-point input.
     */
    fun cumulativeDistances(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += GeoMath.haversine(points[i - 1], points[i])
            out[i] = acc
        }
        return out
    }

    /**
     * Returns points along the polyline spaced about [spacingMeters] apart.
     *
     * Guarantees:
     * - the first input point is always present and the exact last input point
     *   is always present as the final element;
     * - consecutive output points are never identical (no zero-length steps);
     * - single-point, empty, duplicate-only and shorter-than-one-spacing routes
     *   are handled without error.
     *
     * @throws IllegalArgumentException when [spacingMeters] is not positive.
     */
    fun resample(points: List<GeoPoint>, spacingMeters: Double): List<GeoPoint> {
        require(spacingMeters > 0.0) { "spacingMeters must be positive" }
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points.first())

        // Drop consecutive duplicates so zero-length segments cannot stall the walk.
        val cleaned = ArrayList<GeoPoint>(points.size)
        for (p in points) {
            if (cleaned.isEmpty() || GeoMath.haversine(cleaned.last(), p) > EPS_METERS) {
                cleaned.add(p)
            }
        }
        if (cleaned.size == 1) return listOf(cleaned.first())

        val cum = cumulativeDistances(cleaned)
        val total = cum.last()
        if (total <= EPS_METERS) return listOf(cleaned.first())

        val estimate = (total / spacingMeters).toInt()
        val capacity = if (estimate in 2..1_000_000) estimate + 2 else 16
        val out = ArrayList<GeoPoint>(capacity)
        out.add(cleaned.first())

        var target = spacingMeters
        while (target < total - EPS_METERS) {
            val p = pointAtDistance(cleaned, cum, target)
            if (p != out.last()) out.add(p)
            target += spacingMeters
        }

        val last = cleaned.last()
        if (last != out.last()) out.add(last)
        return out
    }

    /** Point at [distance] along the polyline, clamped to the endpoints. */
    private fun pointAtDistance(
        points: List<GeoPoint>,
        cum: DoubleArray,
        distance: Double,
    ): GeoPoint {
        val d = distance.coerceIn(0.0, cum.last())
        var lo = 0
        var hi = cum.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val segLen = cum[lo + 1] - cum[lo]
        val frac = if (segLen <= EPS_METERS) 0.0 else (d - cum[lo]) / segLen
        return GeoMath.interpolate(points[lo], points[lo + 1], frac)
    }
}
