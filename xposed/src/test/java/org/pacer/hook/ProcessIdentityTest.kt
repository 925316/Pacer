package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pacer.hook.hooks.system.vendor.ProcessIdentity
import org.pacer.hook.hooks.system.vendor.SystemProcess

/**
 * The process decides which layer is installed, so a misclassification is not a
 * performance detail: it installs the app hooks into a system process, or leaves a
 * system layer with no hooks at all.
 */
class ProcessIdentityTest {

    @Test
    fun `the settings provider is its own process`() {
        assertEquals(
            SystemProcess.SETTINGS,
            ProcessIdentity.classify("com.android.providers.settings"),
        )
        assertEquals(
            SystemProcess.SETTINGS,
            ProcessIdentity.classify("com.android.providers.settings\n"),
        )
    }

    @Test
    fun `the system processes keep their own layers`() {
        assertEquals(SystemProcess.SYSTEM_SERVER, ProcessIdentity.classify("system_server"))
        assertEquals(SystemProcess.SYSTEM_SERVER, ProcessIdentity.classify("system"))
        assertEquals(SystemProcess.PHONE, ProcessIdentity.classify("com.android.phone"))
        assertEquals(SystemProcess.BLUETOOTH, ProcessIdentity.classify("com.android.bluetooth"))
        assertEquals(SystemProcess.UNKNOWN, ProcessIdentity.classify("com.example.app"))
        assertEquals(SystemProcess.UNKNOWN, ProcessIdentity.classify(null))
        assertEquals(SystemProcess.UNKNOWN, ProcessIdentity.classify(""))
    }
}
