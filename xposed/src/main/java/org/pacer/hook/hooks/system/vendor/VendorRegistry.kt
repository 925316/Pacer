// Chooses the adapter for this device and hands out the class candidates the
// locator walks. Selection itself is a pure function in the engine
// (selectVendor/mergeClassCandidates) so the ordering rules are unit tested;
// this file is only the Android wiring around it.
package org.pacer.hook.hooks.system.vendor

import org.pacer.hook.hooks.system.vendor.profiles.AospVendor
import org.pacer.hook.hooks.system.vendor.profiles.ColorOs16Vendor
import org.pacer.hook.hooks.system.vendor.profiles.ColorOsVendor
import org.pacer.hook.hooks.system.vendor.profiles.HyperOsVendor
import org.pacer.motion.VendorCandidate
import org.pacer.motion.mergeClassCandidates
import org.pacer.motion.selectVendor

object VendorRegistry {

    /** List order is irrelevant: selection is by priority, ties by id. */
    private val ALL: List<SystemHookVendor> = listOf(
        ColorOs16Vendor,
        ColorOsVendor,
        HyperOsVendor,
        AospVendor,
    )

    private val byId: Map<String, SystemHookVendor> = ALL.associateBy { it.id }

    val profile: DeviceProfile by lazy { DeviceProfileReader.read() }

    val active: SystemHookVendor by lazy {
        val chosen = selectVendor(ALL.map { VendorCandidate(it.id, it.priority, it.appliesTo(profile)) })
        chosen?.let { byId[it.id] } ?: AospVendor
    }

    /**
     * Candidates the locator walks: the active adapter's names first, then the
     * AOSP baseline, deduplicated. The baseline is always present, so a firmware
     * nobody has adapted to yet still resolves the standard names.
     */
    fun classCandidates(component: SystemComponent): List<String> =
        mergeClassCandidates(
            baseline = AospVendor.classCandidates(component),
            vendor = active.classCandidates(component),
        )
}
