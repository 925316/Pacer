// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A per-ROM adapter.
//
// Deliberately narrow: it exposes only what a consumer reads today - who it is,
// whether it applies, and which class names it prefers. Upstream carries two
// more extension points (extra hooks and package exemptions) that Pacer will add
// when something actually consumes them, rather than shipping an unused surface.
package org.pacer.hook.hooks.system.vendor

import org.pacer.motion.RomFamily

interface SystemHookVendor {

    val id: String

    val family: RomFamily

    /** Higher wins. Ties are broken by id, so the result never depends on order. */
    val priority: Int

    fun appliesTo(profile: DeviceProfile): Boolean

    /**
     * Vendor class names for [component], most likely first. An empty list means
     * this ROM does not rename it, and the AOSP baseline is used unchanged.
     */
    fun classCandidates(component: SystemComponent): List<String> = emptyList()
}
