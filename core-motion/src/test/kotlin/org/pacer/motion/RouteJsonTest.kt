// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteJsonTest {

    private fun assertDecodeFails(text: String) {
        try {
            RouteJson.decode(text)
            throw AssertionError("expected decode to fail for: $text")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `round trip preserves coordinates exactly`() {
        val points = listOf(
            GeoPoint(39.9042, 116.4074123),
            GeoPoint(-33.8688, 151.2093),
            GeoPoint(0.0, 0.0),
        )
        assertEquals(points, RouteJson.decode(RouteJson.encode(points)))
    }

    @Test
    fun `encodes the compact array form`() {
        val text = RouteJson.encode(listOf(GeoPoint(1.5, 2.5)))
        assertEquals("[[1.5,2.5]]", text)
    }

    @Test
    fun `empty route encodes to empty array`() {
        assertEquals("[]", RouteJson.encode(emptyList()))
        assertTrue(RouteJson.decode("[]").isEmpty())
    }

    @Test
    fun `decode tolerates whitespace`() {
        val text = " [ [ 1.0 , 2.0 ] ,\n  [3.0,4.0] ] "
        assertEquals(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)), RouteJson.decode(text))
    }

    @Test
    fun `decode accepts negative and exponent numbers`() {
        assertEquals(listOf(GeoPoint(-1.25, -2.0)), RouteJson.decode("[[-1.25,-2.0]]"))
        assertEquals(listOf(GeoPoint(1.0E-5, 2.5E3)), RouteJson.decode("[[1.0E-5,2.5E3]]"))
    }

    @Test
    fun `decode rejects malformed text`() {
        assertDecodeFails("")
        assertDecodeFails("[1.0,2.0]")
        assertDecodeFails("[[1.0]]")
        assertDecodeFails("[[1.0,2.0]")
        assertDecodeFails("[[1.0,2.0]]trailing")
        assertDecodeFails("[[a,b]]")
        assertDecodeFails("[[1.0;2.0]]")
    }

    @Test
    fun `decodeOrNull returns null instead of throwing`() {
        assertNull(RouteJson.decodeOrNull("nonsense"))
        assertEquals(listOf(GeoPoint(1.0, 2.0)), RouteJson.decodeOrNull("[[1.0,2.0]]"))
    }

    @Test
    fun `encode rejects non-finite coordinates`() {
        try {
            RouteJson.encode(listOf(GeoPoint(Double.NaN, 0.0)))
            throw AssertionError("expected encode to reject NaN")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            RouteJson.encode(listOf(GeoPoint(0.0, Double.POSITIVE_INFINITY)))
            throw AssertionError("expected encode to reject infinity")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `route path joiner uses the same format as the hook`() {
        val route = RoutePath(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)))
        assertEquals("[[1.0,2.0],[3.0,4.0]]", route.toJson())
        assertEquals(route.points, RoutePath.fromJson("[[1.0,2.0],[3.0,4.0]]").points)
    }
}
