// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineSimplifyTest {
    @Test
    fun `keeps endpoints`() {
        val pts = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001))
        val out = PolylineSimplify.simplify(pts, 1000.0)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }


    @Test
    fun `small epsilon keeps all points`() {
        val pts = (0..5).map { GeoPoint(it * 0.0001, 0.0) } // straight line in latitude
        val out = PolylineSimplify.simplify(pts, 0.0)
        assertTrue(out.size >= pts.size - 1)
    }

    @Test
    fun `corner preserved`() {
        val pts = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.0005),
            GeoPoint(0.0005, 0.0005),
        )
        val out = PolylineSimplify.simplify(pts, 1.0)
        assertTrue(out.size >= 3)
    }
}
