package org.pacer.motion

/**
 * Turns a hand-steered walk into a polyline worth keeping.
 *
 * A recorder could simply append every sample it sees, and the route editor would then
 * hold a point per second - thousands of them for a ten minute walk, with a waypoint
 * handle on each. This keeps the shape and drops the redundancy: a point is kept when
 * the subject has moved far enough for it to matter, or has turned sharply enough that
 * dropping it would round the corner away.
 *
 * The rule is pure code so it can be tested without a phone: keep at 5 m, keep at 2 m
 * when the heading changed by 25 degrees or
 * more, and on a forced flush (stop) keep at 0.5 m so the last point lands where the
 * subject actually stopped. The last two thresholds are what make a corner survive while
 * a straight stretch collapses to a handful of points.
 */
class RouteRecorder(private val minStepMeters: Double = DEFAULT_MIN_STEP_METERS) {

    private val items = ArrayList<GeoPoint>()

    /** Read-only view of what has been recorded so far. */
    val points: List<GeoPoint> get() = items

    val size: Int get() = items.size

    /** Total length of the recorded polyline; 0 with fewer than two points. */
    var distanceMeters: Double = 0.0
        private set

    /** True once the recording holds enough to be a route. */
    val isValid: Boolean get() = items.size >= 2 && distanceMeters > 0.0

    /**
     * Offers a sample. Returns true when it was kept.
     *
     * [force] is for the end of a recording: it lowers the movement threshold so the
     * final position is not dropped just because the subject stopped between samples.
     */
    fun offer(point: GeoPoint, force: Boolean = false): Boolean {
        val last = items.lastOrNull()
        if (last == null) {
            items.add(point)
            return true
        }
        val moved = GeoMath.haversine(last, point)
        if (moved <= 0.0) return false
        val turned = items.size >= 2 && bearingChange(items[items.size - 2], last, point) >= TURN_DEGREES
        val keep = moved >= minStepMeters ||
            (moved >= TURN_STEP_METERS && turned) ||
            (force && moved >= FLUSH_STEP_METERS)
        if (!keep) return false
        items.add(point)
        distanceMeters += moved
        return true
    }

    /** Forces the final point in, then reports whether the result is a usable route. */
    fun finish(point: GeoPoint?): Boolean {
        if (point != null) offer(point, force = true)
        return isValid
    }

    fun clear() {
        items.clear()
        distanceMeters = 0.0
    }

    /** The recorded geometry, or null when it is not a route yet. */
    fun toRoutePath(): RoutePath? = if (isValid) RoutePath(items.toList()) else null

    /** The draft form, so a recording can be edited exactly like a tapped route. */
    fun toDraft(): RouteDraft = RouteDraft(items.toList())

    private fun bearingChange(a: GeoPoint, b: GeoPoint, c: GeoPoint): Float {
        val first = GeoMath.bearing(a, b)
        val second = GeoMath.bearing(b, c)
        val raw = kotlin.math.abs(first - second) % 360f
        return if (raw > 180f) 360f - raw else raw
    }

    companion object {
        /** A straight stretch keeps one point per this many metres. */
        const val DEFAULT_MIN_STEP_METERS = 5.0

        /** A corner is kept from this distance, if the heading really changed. */
        const val TURN_STEP_METERS = 2.0

        /** Heading change that makes a short step worth keeping. */
        const val TURN_DEGREES = 25f

        /** The forced flush at the end of a recording. */
        const val FLUSH_STEP_METERS = 0.5
    }
}
