// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The device's own description of itself: which vendor UI it runs and which
// version of it. Adapter selection reads this and nothing else, so a wrong
// answer can be traced to one place.
package org.pacer.hook.hooks.system.vendor

import org.pacer.hook.utils.HookUtils
import org.pacer.motion.RomFamily
import org.pacer.motion.RomRules

data class DeviceProfile(
    val family: RomFamily,
    val majorVersion: Int?,
    val properties: Map<String, String>,
)

object DeviceProfileReader {

    /** Properties of the running system cannot change, so one read is enough. */
    @Volatile private var cached: DeviceProfile? = null

    private val FALLBACK = DeviceProfile(RomFamily.AOSP, null, emptyMap())

    fun read(): DeviceProfile {
        cached?.let { return it }
        val profile = try {
            readFresh()
        } catch (_: Throwable) {
            FALLBACK
        }
        cached = profile
        return profile
    }

    private fun readFresh(): DeviceProfile {
        val properties = readProperties()
        val manufacturer = safeString { android.os.Build.MANUFACTURER }
        val brand = safeString { android.os.Build.BRAND }
        val family = RomRules.detect(properties, manufacturer, brand)
        return DeviceProfile(family, RomRules.majorVersion(properties, family), properties)
    }

    private fun readProperties(): Map<String, String> {
        val clazz = HookUtils.findClassIfExists("android.os.SystemProperties", null) ?: return emptyMap()
        val get = try {
            HookUtils.findMethodExact(clazz, "get", String::class.java)
        } catch (_: Throwable) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (key in RomRules.PROPERTY_KEYS) {
            val value = try {
                get.invoke(null, key) as? String
            } catch (_: Throwable) {
                null
            }
            if (!value.isNullOrBlank()) out[key] = value
        }
        return out
    }

    private fun safeString(read: () -> String?): String =
        try {
            read().orEmpty()
        } catch (_: Throwable) {
            ""
        }
}
