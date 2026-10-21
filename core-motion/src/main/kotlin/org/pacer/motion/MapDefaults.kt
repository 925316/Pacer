package org.pacer.motion

/**
 * Where the map opens, in one place.
 *
 * The map view, the map activity and the self-check all need the same coordinate, and the
 * self-check needs it before any run exists. Three copies would be three chances for one
 * of them to drift, and the failure would be silent: the map would open somewhere valid,
 * just not where anything else assumed. Kept in the engine because it is data, not a view
 * concern.
 */
object MapDefaults {

    /**
     * Urumqi: deliberately nowhere near most users, and inside China on purpose, because
     * AMap and Baidu serve blank tiles outside the offset's coverage.
     */
    val CENTER = GeoPoint(43.8256, 87.6168)

    /** Close enough to read street names without being so close the first pan overshoots. */
    const val ZOOM = 13
}
