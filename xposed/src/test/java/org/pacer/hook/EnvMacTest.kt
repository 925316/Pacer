package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.hook.hooks.env.EnvGeo

/**
 * The address format is a one-bit test, so it is worth pinning: a scan list in
 * which every BSSID carries the locally administered bit is a fabricator's
 * signature, and the three addresses detection code blacklists must not appear
 * either.
 */
class EnvMacTest {

    @Test
    fun `access point addresses look vendor assigned and unicast`() {
        for (seed in 0L until 200L) {
            val mac = EnvGeo.apMacForSeed(seed * 7919L)
            val first = mac.substring(0, 2).toInt(16)
            assertTrue("local bit set in $mac", (first and 0x02) == 0)
            assertTrue("multicast bit set in $mac", (first and 0x01) == 0)
            assertFalse("zero address $mac", mac == "00:00:00:00:00:00")
            assertFalse("broadcast address $mac", mac == "ff:ff:ff:ff:ff:ff")
            assertFalse("placeholder address $mac", mac == "02:00:00:00:00:00")
        }
    }

    @Test
    fun `personal area addresses stay locally administered and unicast`() {
        for (seed in 0L until 50L) {
            val first = EnvGeo.macForSeed(seed).substring(0, 2).toInt(16)
            assertTrue("local bit clear in ${EnvGeo.macForSeed(seed)}", (first and 0x02) != 0)
            assertTrue("multicast bit set in ${EnvGeo.macForSeed(seed)}", (first and 0x01) == 0)
        }
    }

    @Test
    fun `the same seed produces the same access point address`() {
        assertEquals(EnvGeo.apMacForSeed(42L), EnvGeo.apMacForSeed(42L))
    }
}
