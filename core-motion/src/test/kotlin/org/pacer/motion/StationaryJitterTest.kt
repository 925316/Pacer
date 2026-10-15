// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StationaryJitterTest {

    @Test
    fun `starts near origin`() {
        val sj = StationaryJitter(seed = 1L)
        val o = sj.next(1.0)
        assertTrue(hypot(o.eastMeters, o.northMeters) < 5.0)
    }

    @Test
    fun `radius stays bounded over long run`() {
        val sj = StationaryJitter(seed = 2L)
        var maxR = 0.0
        repeat(2000) {
            val o = sj.next(1.0)
            val r = hypot(o.eastMeters, o.northMeters)
            if (r > maxR) maxR = r
        }
        assertTrue("maxR=$maxR", maxR < 4.0)
    }

    @Test
    fun `different seeds diverge`() {
        val a = StationaryJitter(seed = 3L)
        val b = StationaryJitter(seed = 4L)
        var diff = 0.0
        repeat(50) { diff += kotlin.math.abs(a.next(1.0).eastMeters - b.next(1.0).eastMeters) }
        assertTrue(diff > 1e-6)
    }

    @Test
    fun `zero dt keeps state reasonable`() {
        val sj = StationaryJitter(seed = 5L)
        sj.next(0.1)
        val before = sj.next(0.0)
        assertTrue(before.eastMeters.isFinite())
    }
}
