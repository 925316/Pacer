// The system layer resolves every component class from a STRING candidate list,
// and it fails SILENTLY. A component whose candidates are empty, misspelled, or
// simply wrong for the firmware does not throw: SystemClassLocator returns null,
// HookStatus records the miss, and every hook built on that component becomes a
// no-op that on a real device looks exactly like "the feature does not work".
// The process mapping fails the same silent way: a component declared to live in
// the wrong process installs its layer where the class is not present.
//
// So this test asserts the two things the compiler cannot see - the shape and
// completeness of the candidate lists, and which process serves each component -
// directly against the vendor adapters, so a dropped or mistyped name fails the
// build instead of quietly disabling a layer on a device.
package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.hooks.system.vendor.SystemHookVendor
import org.pacer.hook.hooks.system.vendor.SystemProcess
import org.pacer.hook.hooks.system.vendor.profiles.AospVendor
import org.pacer.hook.hooks.system.vendor.profiles.ColorOs16Vendor
import org.pacer.hook.hooks.system.vendor.profiles.ColorOsVendor
import org.pacer.hook.hooks.system.vendor.profiles.HyperOsVendor

class SystemComponentContractTest {

    @Test
    fun everyComponentHasANonEmptyAospBaseline() {
        val empty = SystemComponent.values()
            .filter { AospVendor.classCandidates(it).isEmpty() }
            .map { it.name }

        if (empty.isNotEmpty()) {
            // Printed before failing so the list always reaches the CI log.
            println("COMPONENTS WITH NO AOSP BASELINE:\n" + empty.joinToString("\n"))
        }

        assertTrue(
            "Components with an empty AOSP baseline. The baseline is the fallback " +
                "that keeps an unadapted firmware working, so an empty list makes " +
                "every dependent hook a silent no-op there:\n" + empty.joinToString("\n"),
            empty.isEmpty(),
        )
    }

    @Test
    fun everyCandidateNameIsAPlausibleFullyQualifiedClassName() {
        val malformed = mutableListOf<String>()

        for (profile in PROFILES) {
            for (component in SystemComponent.values()) {
                for (name in profile.classCandidates(component)) {
                    if (!isPlausibleClassName(name)) {
                        malformed += "${profile.id}/${component.name}: \"$name\""
                    }
                }
            }
        }

        if (malformed.isNotEmpty()) {
            println("MALFORMED CLASS CANDIDATES:\n" + malformed.joinToString("\n"))
        }

        assertTrue(
            "Class candidates that are not plausible fully qualified class names. " +
                "Each needs a '.', no whitespace, and a letter/digit/underscore at " +
                "each end. A typo or an empty string here compiles and silently " +
                "disables a layer:\n" + malformed.joinToString("\n"),
            malformed.isEmpty(),
        )
    }

    @Test
    fun noProfileListsTheSameClassNameTwice() {
        val duplicated = mutableListOf<String>()

        for (profile in PROFILES) {
            for (component in SystemComponent.values()) {
                val seen = mutableSetOf<String>()
                for (name in profile.classCandidates(component)) {
                    if (!seen.add(name)) {
                        duplicated += "${profile.id}/${component.name}: $name"
                    }
                }
            }
        }

        if (duplicated.isNotEmpty()) {
            println("DUPLICATE CLASS CANDIDATES:\n" + duplicated.joinToString("\n"))
        }

        assertTrue(
            "A profile lists the same class name more than once for one component. " +
                "The list is ordered most-likely-first, so a duplicate is always a " +
                "mistake and hides whichever candidate it displaced:\n" +
                duplicated.joinToString("\n"),
            duplicated.isEmpty(),
        )
    }

    @Test
    fun eachComponentIsServedByTheProcessThatActuallyHostsIt() {
        assertEquals(
            "Bluetooth scanning runs in its own process, so the scan service " +
                "component must be declared for it. The wrong process installs the " +
                "layer where the class does not exist, silently.",
            SystemProcess.BLUETOOTH,
            SystemComponent.BLUETOOTH_SCAN_SERVICE.process,
        )
        assertEquals(
            "PhoneInterfaceManager lives in the phone process, so the phone manager " +
                "component must be declared for it. The wrong process installs the " +
                "layer where the class does not exist, silently.",
            SystemProcess.PHONE,
            SystemComponent.TELEPHONY_PHONE_MANAGER.process,
        )

        val unknown = SystemComponent.values()
            .filter { it.process == SystemProcess.UNKNOWN }
            .map { it.name }

        assertTrue(
            "Components mapped to SystemProcess.UNKNOWN. UNKNOWN means the process " +
                "could not be identified, which is what an ordinary app sees, so a " +
                "component declared this way would install the system layer into a " +
                "normal app:\n" + unknown.joinToString("\n"),
            unknown.isEmpty(),
        )
    }

    @Test
    fun bluetoothScanBaselineKeepsEveryHistoricSpelling() {
        // Simple names on purpose: the package moved between releases too, so an
        // assertion on the full name would fail on a legitimate package rename
        // while a dropped candidate must not.
        val required = listOf(
            "ScanBinder",
            "ScanController",
            "TransitionalScanHelper",
            "GattService",
            "AdapterService",
        )
        val simpleNames = AospVendor.classCandidates(SystemComponent.BLUETOOTH_SCAN_SERVICE)
            .map { it.substringAfterLast('.') }
        val missing = required.filterNot { it in simpleNames }

        if (missing.isNotEmpty()) {
            println("MISSING BLUETOOTH SCAN BASELINE SPELLINGS:\n" + missing.joinToString("\n"))
        }

        assertTrue(
            "The Bluetooth scan baseline dropped a historic simple name. Scanning " +
                "moved between releases AND between firmwares on the same vendor " +
                "version, so a dropped spelling silently disables BLE scanning on " +
                "the firmware that still uses it:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    private fun isPlausibleClassName(name: String): Boolean {
        if (name.isEmpty() || !name.contains('.')) return false
        if (name.any { it.isWhitespace() }) return false
        fun isBoundary(c: Char) = c.isLetterOrDigit() || c == '_'
        return isBoundary(name.first()) && isBoundary(name.last())
    }

    private companion object {

        /** Every adapter the locator can walk, AOSP included. */
        val PROFILES: List<SystemHookVendor> = listOf(
            AospVendor,
            ColorOsVendor,
            ColorOs16Vendor,
            HyperOsVendor,
        )
    }
}
