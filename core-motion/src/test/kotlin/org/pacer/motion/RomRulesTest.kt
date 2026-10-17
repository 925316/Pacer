package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomRulesTest {

    @Test
    fun `property identifies the family even when the brand suggests another`() {
        val properties = mapOf("ro.build.version.oplusrom.display" to "V16.0.1")
        assertEquals(RomFamily.COLOROS, RomRules.detect(properties, "samsung", "samsung"))
    }

    @Test
    fun `hyperos property is preferred over a coloros brand string`() {
        val properties = mapOf("ro.mi.os.version.name" to "OS2.0.1.0")
        assertEquals(RomFamily.HYPEROS, RomRules.detect(properties, "OPPO", "realme"))
    }

    @Test
    fun `blank property does not identify anything`() {
        val properties = mapOf("ro.mi.os.version.name" to "  ")
        assertEquals(RomFamily.AOSP, RomRules.detect(properties, "unknown", "unknown"))
    }

    @Test
    fun `brand heuristics are case insensitive`() {
        assertEquals(RomFamily.HYPEROS, RomRules.detect(emptyMap(), "Xiaomi", "Redmi"))
        assertEquals(RomFamily.COLOROS, RomRules.detect(emptyMap(), "OnePlus", "OnePlus"))
        assertEquals(RomFamily.ONEUI, RomRules.detect(emptyMap(), "SAMSUNG", "samsung"))
    }

    @Test
    fun `an unknown device falls back to the aosp baseline`() {
        assertEquals(RomFamily.AOSP, RomRules.detect(emptyMap(), "Gigaset", "gigaset"))
    }

    @Test
    fun `major version reads through the vendor prefix`() {
        assertEquals(
            16,
            RomRules.majorVersion(mapOf("ro.build.version.oplusrom.display" to "V16.0.1"), RomFamily.COLOROS),
        )
        assertEquals(
            2,
            RomRules.majorVersion(mapOf("ro.mi.os.version.name" to "OS2.0.1.0"), RomFamily.HYPEROS),
        )
    }

    @Test
    fun `major version is null when the family has no version property`() {
        assertNull(RomRules.versionText(emptyMap(), RomFamily.AOSP))
        assertNull(RomRules.majorVersion(emptyMap(), RomFamily.AOSP))
        assertNull(RomRules.majorVersion(mapOf("ro.mi.os.version.name" to "OS"), RomFamily.HYPEROS))
    }

    @Test
    fun `leading version parses the first digit run`() {
        assertEquals(16, RomRules.leadingVersion("V16.0.1"))
        assertEquals(2, RomRules.leadingVersion("OS2.0.1.0"))
        assertEquals(15, RomRules.leadingVersion("15.0.0"))
        assertEquals(7, RomRules.leadingVersion("  7.1"))
        assertNull(RomRules.leadingVersion(null))
        assertNull(RomRules.leadingVersion(""))
        assertNull(RomRules.leadingVersion("no digits here"))
    }

    @Test
    fun `oneui version is not parsed as a leading digit run`() {
        // 60100 means One UI 6.1: a leading-digit read would claim version 60100.
        val properties = mapOf("ro.build.version.oneui" to "60100")
        assertEquals(RomFamily.ONEUI, RomRules.detect(properties, "samsung", "samsung"))
        assertNull(RomRules.majorVersion(properties, RomFamily.ONEUI))
    }
}
