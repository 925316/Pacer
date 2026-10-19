package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.hook.utils.HookStatus

/**
 * The install report is the only evidence a device run gives about whether a
 * hooker attached, so the arithmetic behind it is pinned here: a helper has to
 * move the total, and recording the installer's result must not move it again.
 */
class HookStatusTest {

    @Test
    fun `recording an installer does not double count its hooks`() {
        val before = HookStatus.countHooked()
        HookStatus.recordHooked(3)
        HookStatus.recordOk("test-installer", 3)
        assertEquals(before + 3, HookStatus.countHooked())
        val summary = HookStatus.summary()
        assertTrue(summary, summary.contains("total=${before + 3}"))
        assertTrue(summary, summary.contains("test-installer=3"))
    }

    @Test
    fun `a failed installer appears in the summary`() {
        HookStatus.recordFailure("test-failure", IllegalStateException("boom"))
        assertTrue(HookStatus.summary().contains("test-failure(IllegalStateException: boom)"))
    }
}
