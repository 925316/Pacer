package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteStatusTest {

    @Test
    fun `used in fix is clamped to the satellites in view`() {
        val sky = SatelliteStatus.of(inView = 9, usedInFix = 14)
        assertEquals(9, sky.inView)
        assertEquals(9, sky.usedInFix)
    }

    @Test
    fun `a reported fix keeps at least four satellites`() {
        val sky = SatelliteStatus.withFix(inView = 3, usedInFix = 1)
        assertTrue("inView=${sky.inView}", sky.inView >= SatelliteStatus.MIN_USED_FOR_FIX)
        assertTrue("used=${sky.usedInFix}", sky.usedInFix >= SatelliteStatus.MIN_USED_FOR_FIX)
        assertTrue("used=${sky.usedInFix} inView=${sky.inView}", sky.usedInFix <= sky.inView)
    }

    @Test
    fun `counts above the ceiling are clamped instead of reported`() {
        val sky = SatelliteStatus.of(inView = 90, usedInFix = 90)
        assertEquals(SatelliteStatus.MAX_REPORTED, sky.inView)
        assertEquals(SatelliteStatus.MAX_REPORTED, sky.usedInFix)
    }

    @Test
    fun `no visible satellites means none used`() {
        assertEquals(0, SatelliteStatus.of(inView = 0, usedInFix = 5).usedInFix)
    }
}
