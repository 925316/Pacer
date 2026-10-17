// Vendor UI identification, kept in the pure engine on purpose.
//
// The hook module could detect the ROM itself, but this is exactly the kind of
// logic whose mistakes are INVISIBLE: picking the wrong family does not throw,
// it selects class names that do not exist on the device, the class lookup fails
// quietly, and every dependent hook silently does nothing. Living here means it
// is covered by the unit tests that actually run in CI.
//
// Field evidence for why this must not be API-level guesswork: the same ColorOS
// 16 line ships BLE scan dispatch in le_scan.ScanController on one firmware and
// in le_scan.TransitionalScanHelper on another, so "one adapter per Android
// version" is not fine-grained enough - the adapter has to be selected from the
// device's own reported identity, and every candidate list has to keep a
// baseline behind it.
package org.pacer.motion

/** Vendor UI families Pacer has an adapter for. AOSP is the baseline. */
enum class RomFamily {
    AOSP,
    HYPEROS,
    COLOROS,
    ONEUI,
    OTHER,
}

object RomRules {

    /**
     * Properties that identify the vendor UI. Several spellings are listed
     * because OEMs have moved them between releases, and a miss here degrades to
     * the brand heuristic below rather than to a wrong answer.
     */
    private val HYPEROS_PROPERTIES = listOf(
        "ro.mi.os.version.name",
        "ro.mi.os.version.incremental",
    )

    private val COLOROS_PROPERTIES = listOf(
        "ro.build.version.oplusrom.display",
        "ro.build.version.oplusrom",
        "ro.oplus.version",
    )

    private val ONEUI_PROPERTIES = listOf(
        "ro.build.version.oneui",
        "ro.build.version.sep_lite",
    )

    /** Checked only when no property identifies the UI. */
    private val BRAND_HINTS = mapOf(
        RomFamily.HYPEROS to listOf("xiaomi", "redmi", "poco"),
        RomFamily.COLOROS to listOf("oppo", "realme", "oneplus", "oplus"),
        RomFamily.ONEUI to listOf("samsung"),
    )

    /**
     * Decides the family from system properties first, then from the reported
     * manufacturer and brand. Properties win because a brand string can be
     * re-used across a transition (a Xiaomi device may still report the MIUI
     * brand after moving to HyperOS).
     */
    fun detect(properties: Map<String, String>, manufacturer: String, brand: String): RomFamily {
        if (hasAny(properties, HYPEROS_PROPERTIES)) return RomFamily.HYPEROS
        if (hasAny(properties, COLOROS_PROPERTIES)) return RomFamily.COLOROS
        if (hasAny(properties, ONEUI_PROPERTIES)) return RomFamily.ONEUI

        val haystack = "$manufacturer $brand".lowercase()
        for ((family, hints) in BRAND_HINTS) {
            if (hints.any { it in haystack }) return family
        }
        return RomFamily.AOSP
    }

    /**
     * The version string the family reports, or null when the family has none.
     */
    fun versionText(properties: Map<String, String>, family: RomFamily): String? {
        val keys = when (family) {
            RomFamily.HYPEROS -> HYPEROS_PROPERTIES
            RomFamily.COLOROS -> COLOROS_PROPERTIES
            else -> return null
        }
        return keys.firstNotNullOfOrNull { key -> properties[key]?.takeIf { it.isNotBlank() } }
    }

    /** Major version of the vendor UI, or null when it cannot be read. */
    fun majorVersion(properties: Map<String, String>, family: RomFamily): Int? =
        leadingVersion(versionText(properties, family))

    /**
     * First run of digits in [text], ignoring a letter prefix such as "OS" or
     * "V": "V16.0.1" -> 16, "OS2.0.1.0" -> 2, "15.0.0" -> 15.
     *
     * Deliberately not applied to OneUI, whose version property encodes a minor
     * part into the same run of digits ("60100" meaning 6.1), so a leading-digit
     * read would invent a version that does not exist.
     */
    fun leadingVersion(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOfFirst { it.isDigit() }
        if (start < 0) return null
        return text.substring(start).takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun hasAny(properties: Map<String, String>, keys: List<String>): Boolean =
        keys.any { !properties[it].isNullOrBlank() }
}
