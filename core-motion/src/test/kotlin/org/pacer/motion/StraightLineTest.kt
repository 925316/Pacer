package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightLineTest {

    private val start = GeoPoint(39.9042, 116.4074)

    @Test
    fun `no movement without speed`() {
        assertEquals(start, StraightLine.at(start, bearingDeg = 90.0, speedMps = 0.0, elapsedSec = 600.0))
    }

    @Test
    fun `no movement without elapsed time`() {
        assertEquals(start, StraightLine.at(start, bearingDeg = 90.0, speedMps = 3.0, elapsedSec = 0.0))
    }

    @Test
    fun `negative elapsed time does not move backwards`() {
        // The hook can be asked before the run's start timestamp is set, and a negative
        // offset must leave the subject at the start rather than behind it.
        assertEquals(start, StraightLine.at(start, bearingDeg = 0.0, speedMps = 3.0, elapsedSec = -5.0))
    }

    @Test
    fun `north heading moves latitude only`() {
        val moved = StraightLine.at(start, bearingDeg = 0.0, speedMps = 3.0, elapsedSec = 10.0)
        assertEquals(start.lon, moved.lon, 1e-12)
        assertTrue(moved.lat > start.lat)
        assertEquals(30.0, (moved.lat - start.lat) * 111_320.0, 0.01)
    }

    @Test
    fun `east heading moves longitude only`() {
        val moved = StraightLine.at(start, bearingDeg = 90.0, speedMps = 3.0, elapsedSec = 10.0)
        assertEquals(start.lat, moved.lat, 1e-12)
        assertTrue(moved.lon > start.lon)
        val meters = (moved.lon - start.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(start.lat))
        assertEquals(30.0, meters, 0.05)
    }

    @Test
    fun `the same inputs always give the same position`() {
        // The property the previous tick-based accumulation did not have: asking twice,
        // or asking after something else rewrote the stored position, cannot change the
        // answer. That is what stops a straight-line run being yanked back to its start.
        val first = StraightLine.at(start, bearingDeg = 45.0, speedMps = 2.5, elapsedSec = 120.0)
        val second = StraightLine.at(start, bearingDeg = 45.0, speedMps = 2.5, elapsedSec = 120.0)
        assertEquals(first, second)
    }

    @Test
    fun `distance grows linearly with elapsed time`() {
        val oneMinute = StraightLine.at(start, bearingDeg = 45.0, speedMps = 2.0, elapsedSec = 60.0)
        val twoMinutes = StraightLine.at(start, bearingDeg = 45.0, speedMps = 2.0, elapsedSec = 120.0)
        val d1 = distanceMeters(start, oneMinute)
        val d2 = distanceMeters(start, twoMinutes)
        assertEquals(120.0, d1, 0.1)
        assertEquals(2.0, d2 / d1, 1e-6)
    }

    @Test
    fun `byDistance agrees with speed times time`() {
        // One formula behind both paths: a caller that has a distance (an interval plan
        // integrates its own) and a caller that has a speed and a clock must land on the
        // same place, or the two would report different tracks for one run.
        val bySpeed = StraightLine.at(start, bearingDeg = 137.0, speedMps = 2.5, elapsedSec = 240.0)
        val byMeters = StraightLine.byDistance(start, bearingDeg = 137.0, meters = 2.5 * 240.0)
        assertEquals(bySpeed, byMeters)
        assertEquals(start, StraightLine.byDistance(start, bearingDeg = 137.0, meters = 0.0))
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * 111_320.0
        val dLon = (b.lon - a.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(a.lat))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }
}
