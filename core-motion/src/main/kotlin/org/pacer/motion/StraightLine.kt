// Straight-line movement: where the simulated subject is after a given time,
// walking one heading at one speed.
//
// DERIVED, NOT ACCUMULATED. This was previously done by adding one second of travel
// to the stored position on every timer tick, and two things followed from that. The
// position depended on how many ticks had happened rather than on the clock, and
// anything that rewrote the stored position yanked the subject back mid-run - which
// the config channel did every few seconds, because it re-reads the start point the
// app wrote. Same inputs, same answer, whenever they are asked is the property that
// removes both.
package org.pacer.motion

import kotlin.math.cos
import kotlin.math.sin

object StraightLine {

    /** Metres per degree of latitude, the same figure the rest of the engine uses. */
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /**
     * Position after [elapsedSec] from [start], heading [bearingDeg] clockwise from
     * north at [speedMps]. A non-positive speed or elapsed time returns [start]
     * unchanged, so an idle simulation does not drift.
     */
    fun at(start: GeoPoint, bearingDeg: Double, speedMps: Double, elapsedSec: Double): GeoPoint {
        if (speedMps <= 0.0 || elapsedSec <= 0.0) return start
        return byDistance(start, bearingDeg, speedMps * elapsedSec)
    }

    /**
     * Position [meters] along [bearingDeg] from [start]. This is the one formula:
     * [at] is this with the distance written as speed x time, and a caller whose speed
     * varies during the run (an interval plan) supplies its own distance instead of a
     * product, so both paths keep the same geometry.
     */
    fun byDistance(start: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
        if (meters <= 0.0) return start
        val bearing = Math.toRadians(bearingDeg)
        val dLat = meters * cos(bearing) / METERS_PER_DEGREE_LAT
        // Degrees of longitude shrink towards the poles. At a pole the conversion is
        // undefined, so the heading is allowed to move latitude only rather than
        // producing an infinite offset.
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(start.lat))
        if (metersPerDegreeLon == 0.0) return GeoPoint(start.lat + dLat, start.lon)
        val dLon = meters * sin(bearing) / metersPerDegreeLon
        return GeoPoint(start.lat + dLat, start.lon + dLon)
    }
}
