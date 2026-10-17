// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ColorOS 16 specifically.
//
// Why a version-level adapter exists at all: two firmwares on the SAME ColorOS 16
// version disagree about where Bluetooth scanning lives. A merged upstream fix
// recorded le_scan.TransitionalScanHelper failing with NoSuchElementException on
// PLC110 while the dispatch actually sat in le_scan.ScanController, and a sibling
// device on PJX110 still had the old name. "One adapter per vendor UI version" is
// therefore not fine-grained enough, and the answer has to be a candidate list
// with the baseline behind it - which is exactly what classCandidates is for.
package org.pacer.hook.hooks.system.vendor.profiles

import org.pacer.hook.hooks.system.vendor.DeviceProfile
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.hooks.system.vendor.SystemHookVendor
import org.pacer.motion.RomFamily

object ColorOs16Vendor : SystemHookVendor {

    private const val MAJOR_VERSION = 16

    override val id = "coloros16"

    override val family = RomFamily.COLOROS

    override val priority = 20

    override fun appliesTo(profile: DeviceProfile): Boolean =
        profile.family == RomFamily.COLOROS && profile.majorVersion == MAJOR_VERSION

    override fun classCandidates(component: SystemComponent): List<String> = when (component) {
        SystemComponent.BLUETOOTH_SCAN_SERVICE -> listOf(
            "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.le_scan.TransitionalScanHelper",
        )

        else -> emptyList()
    }
}
