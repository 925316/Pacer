// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSerializationTest {

    private val points = listOf(
        GeoPoint(39.9, 116.4),
        GeoPoint(39.905, 116.404),
        GeoPoint(39.908, 116.410),
    )

    @Test
    fun `round trip preserves points`() {
        val original = RoutePath(points)
        val json = original.toJson()
        val restored = RoutePath.fromJson(json)
        assertEquals(points, restored.points)
    }

    @Test
    fun `round trip preserves length`() {
        val original = RoutePath(points)
        val restored = RoutePath.fromJson(original.toJson())
        assertEquals(original.totalLengthMeters, restored.totalLengthMeters, 1e-9)
    }

    @Test
    fun `fromPoints factory works`() {
        val route = RoutePath.fromPoints(points)
        assertEquals(points, route.points)
    }

    @Test
    fun `toJson via points list`() {
        val json = RoutePath.toJson(points)
        val restored = RoutePath.fromJson(json)
        assertEquals(points, restored.points)
    }

    @Test
    fun `json is compact non-empty`() {
        val json = RoutePath(points).toJson()
        assertTrue(json.isNotEmpty())
        assertTrue(json.startsWith("["))
    }
}
