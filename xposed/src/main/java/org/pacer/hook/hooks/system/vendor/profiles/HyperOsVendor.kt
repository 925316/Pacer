// HyperOS / MIUI.
//
// The only vendor UI with third-party real-device confirmation (a reporter
// confirmed the global system layer working on HyperOS 3; HyperOS 4 follows the
// same layout), and its service class names are the AOSP ones - so this adapter
// adds no candidates. It exists to pin the family and to give version-specific
// overrides somewhere to live later.
package org.pacer.hook.hooks.system.vendor.profiles

import org.pacer.hook.hooks.system.vendor.DeviceProfile
import org.pacer.hook.hooks.system.vendor.SystemHookVendor
import org.pacer.motion.RomFamily

object HyperOsVendor : SystemHookVendor {

    override val id = "hyperos"

    override val family = RomFamily.HYPEROS

    override val priority = 10

    override fun appliesTo(profile: DeviceProfile): Boolean =
        profile.family == RomFamily.HYPEROS
}
