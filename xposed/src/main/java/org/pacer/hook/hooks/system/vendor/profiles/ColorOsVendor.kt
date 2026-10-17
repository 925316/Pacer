// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ColorOS / OxygenOS / realme UI, any version. Adds no candidates: the known
// ColorOS quirks are version specific and live in ColorOs16Vendor. This adapter
// pins the family so a ColorOS device does not fall through to the AOSP baseline
// with no identity at all.
package org.pacer.hook.hooks.system.vendor.profiles

import org.pacer.hook.hooks.system.vendor.DeviceProfile
import org.pacer.hook.hooks.system.vendor.SystemHookVendor
import org.pacer.motion.RomFamily

object ColorOsVendor : SystemHookVendor {

    override val id = "coloros"

    override val family = RomFamily.COLOROS

    override val priority = 10

    override fun appliesTo(profile: DeviceProfile): Boolean =
        profile.family == RomFamily.COLOROS
}
