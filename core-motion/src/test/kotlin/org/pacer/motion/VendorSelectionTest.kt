// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorSelectionTest {

    @Test
    fun `the applicable adapter with the highest priority wins`() {
        val chosen = selectVendor(
            listOf(
                VendorCandidate("aosp", Int.MIN_VALUE, applies = true),
                VendorCandidate("coloros16", 20, applies = true),
                VendorCandidate("coloros", 10, applies = true),
            ),
        )
        assertEquals("coloros16", chosen?.id)
    }

    @Test
    fun `an inapplicable adapter is ignored however important it is`() {
        val chosen = selectVendor(
            listOf(
                VendorCandidate("coloros16", 20, applies = false),
                VendorCandidate("aosp", Int.MIN_VALUE, applies = true),
            ),
        )
        assertEquals("aosp", chosen?.id)
    }

    @Test
    fun `nothing applicable selects nothing`() {
        assertNull(
            selectVendor(
                listOf(
                    VendorCandidate("coloros16", 20, applies = false),
                    VendorCandidate("hyperos", 10, applies = false),
                ),
            ),
        )
    }

    @Test
    fun `a priority tie does not depend on list order`() {
        val forward = selectVendor(
            listOf(VendorCandidate("alpha", 5, true), VendorCandidate("beta", 5, true)),
        )
        val reversed = selectVendor(
            listOf(VendorCandidate("beta", 5, true), VendorCandidate("alpha", 5, true)),
        )
        assertEquals(forward?.id, reversed?.id)
        assertEquals("beta", forward?.id)
    }

    @Test
    fun `vendor class names come first and duplicates collapse`() {
        val merged = mergeClassCandidates(
            baseline = listOf("com.android.server.wifi.WifiServiceImpl", "com.android.server.SystemService"),
            vendor = listOf("com.oplus.wifi.WifiServiceImpl", "com.android.server.SystemService"),
        )
        assertEquals(
            listOf(
                "com.oplus.wifi.WifiServiceImpl",
                "com.android.server.SystemService",
                "com.android.server.wifi.WifiServiceImpl",
            ),
            merged,
        )
    }

    @Test
    fun `an empty vendor list leaves the baseline untouched`() {
        val baseline = listOf("a.B", "c.D")
        assertEquals(baseline, mergeClassCandidates(baseline, emptyList()))
    }
}
