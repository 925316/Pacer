package org.pacer.motion

import java.util.Collections

/**
 * A route being edited in the UI, which is allowed to be incomplete.
 *
 * [RoutePath] requires at least two points, so it cannot represent the state
 * "user has added one waypoint" or "user just cleared the list". This draft
 * holds any number of points, exposes the small editing vocabulary a list UI
 * needs (append, insert, remove, reorder), and only converts to a [RoutePath]
 * once it is valid. All geometry goes through [GeoMath], so a draft and the
 * route it produces agree on length.
 *
 * Pure Kotlin and unit-testable; no Android types.
 */
class RouteDraft(points: List<GeoPoint> = emptyList()) {

    private val items = ArrayList<GeoPoint>(points)

    /** Read-only live view of the current waypoints. */
    val points: List<GeoPoint> get() = Collections.unmodifiableList(items)

    val size: Int get() = items.size

    /** A route needs two distinct-ish points to have any geometry. */
    val isValid: Boolean get() = items.size >= 2

    fun add(point: GeoPoint) {
        items.add(point)
    }

    /** Inserts at [index] (0..size). Returns false when the index is out of range. */
    fun insertAt(index: Int, point: GeoPoint): Boolean {
        if (index < 0 || index > items.size) return false
        items.add(index, point)
        return true
    }

    /** Removes the point at [index]; returns it, or null when out of range. */
    fun removeAt(index: Int): GeoPoint? {
        if (index < 0 || index >= items.size) return null
        return items.removeAt(index)
    }

    fun replaceAt(index: Int, point: GeoPoint): Boolean {
        if (index < 0 || index >= items.size) return false
        items[index] = point
        return true
    }

    /** Moves the point at [from] to sit at [to]. False when either index is invalid. */
    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        val moved = items.removeAt(from)
        items.add(to, moved)
        return true
    }

    fun moveUp(index: Int): Boolean = move(index, index - 1)

    fun moveDown(index: Int): Boolean = move(index, index + 1)

    fun clear() {
        items.clear()
    }

    /** Total path length in meters, or 0.0 while there are fewer than two points. */
    fun totalLengthMeters(): Double {
        if (items.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until items.size) {
            total += GeoMath.haversine(items[i - 1], items[i])
        }
        return total
    }

    /** The route this draft describes, or null while it is still incomplete. */
    fun toRoutePath(): RoutePath? = if (isValid) RoutePath(items.toList()) else null

    /** Serializes with the shared [RouteJson] wire format. */
    fun toJson(): String = RouteJson.encode(items)

    companion object {
        /** Parses the [RouteJson] format; malformed text yields an empty draft. */
        fun parse(json: String): RouteDraft =
            RouteDraft(RouteJson.decodeOrNull(json) ?: emptyList())
    }
}
