// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNearestPointTest {

    private val route = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)))

    @Test
    fun `point on route snaps with zero offset`() {
        val onRoute = route.pointAt(route.totalLengthMeters / 2).first
        val near = route.nearestPoint(onRoute)
        assertTrue("offset=${near.offsetMeters}", near.offsetMeters < 1.0)
    }

    @Test
    fun `off-route point reports positive offset`() {
        val off = GeoPoint(0.0005, 0.0005)
        val near = route.nearestPoint(off)
        assertTrue(near.offsetMeters > 10.0)
    }

    @Test
    fun `nearest point along distance stays within route bounds`() {
        val target = GeoPoint(0.0003, 0.0003)
        val near = route.nearestPoint(target)
        assertTrue(near.distanceAlongMeters in 0.0..route.totalLengthMeters)
    }

    @Test
    fun `target beyond route end clamps to end`() {
        val beyond = GeoPoint(0.0, 0.002)
        val near = route.nearestPoint(beyond)
        assertEquals(route.totalLengthMeters, near.distanceAlongMeters, 1.0)
    }

    @Test
    fun `target before route start clamps to start`() {
        val before = GeoPoint(0.0, -0.001)
        val near = route.nearestPoint(before)
        assertEquals(0.0, near.distanceAlongMeters, 1.0)
    }
}
