// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateInputTest {

    private fun assertPoint(lat: Double, lon: Double, text: String) {
        val p = CoordinateInput.parse(text)
        assertEquals("parse('$text')", GeoPoint(lat, lon), p)
    }

    @Test
    fun `bare decimal pairs with various separators`() {
        assertPoint(39.9042, 116.4074, "39.9042,116.4074")
        assertPoint(39.9042, 116.4074, "39.9042 116.4074")
        assertPoint(39.9042, 116.4074, "39.9042;116.4074")
        assertPoint(-33.8688, 151.2093, "  -33.8688 ,  151.2093 ")
    }

    @Test
    fun `google maps at-form`() {
        assertPoint(39.9042, 116.4074, "https://www.google.com/maps/@39.9042,116.4074,15z")
    }

    @Test
    fun `google maps place bang form`() {
        assertPoint(
            39.9042,
            116.4074,
            "https://www.google.com/maps/place/Beijing/data=!3d39.9042!4d116.4074",
        )
    }

    @Test
    fun `query parameter form`() {
        assertPoint(39.9042, 116.4074, "https://maps.google.com/?q=39.9042,116.4074")
        assertPoint(39.9042, 116.4074, "https://example.com/?query=39.9042,116.4074")
    }

    @Test
    fun `geo uri form`() {
        assertPoint(39.9042, 116.4074, "geo:39.9042,116.4074?z=15")
    }

    @Test
    fun `geo uri with a q parameter prefers the named point`() {
        assertPoint(39.9042, 116.4074, "geo:0,0?q=39.9042,116.4074")
    }

    @Test
    fun `named lat lon parameters work in either order`() {
        assertPoint(39.9042, 116.4074, "https://example.com/x?lat=39.9042&lon=116.4074")
        assertPoint(39.9042, 116.4074, "https://example.com/x?lon=116.4074&lat=39.9042")
    }

    @Test
    fun `percent encoded comma is decoded`() {
        assertPoint(39.9042, 116.4074, "https://maps.google.com/?q=39.9042%2C116.4074")
    }

    @Test
    fun `rejects out of range and swapped coordinates`() {
        assertNull(CoordinateInput.parse("999,999"))
        // Longitude first is out of latitude range, so it must not be accepted.
        assertNull(CoordinateInput.parse("116.4074,39.9042"))
        assertNull(CoordinateInput.parse("-91,10"))
        assertNull(CoordinateInput.parse("10,181"))
    }

    @Test
    fun `rejects non coordinate text`() {
        assertNull(CoordinateInput.parse(""))
        assertNull(CoordinateInput.parse("   "))
        assertNull(CoordinateInput.parse("hello"))
        assertNull(CoordinateInput.parse("39.9042"))
        assertNull(CoordinateInput.parse("1.2.3,4.5"))
    }

    @Test
    fun `isValid mirrors parse`() {
        assertTrue(CoordinateInput.isValid("39.9042,116.4074"))
        assertFalse(CoordinateInput.isValid("nope"))
    }
}
