package org.pacer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of the permission dialog.
 *
 * A dialog attached to a finishing or destroyed activity is a BadTokenException at best and a
 * permission silently never asked for at worst, so the rule is worth pinning down here rather
 * than only at the one call site.
 */
class AppPermissionsTest {

    @Test
    fun `a live activity can show the dialog`() {
        assertTrue(AppPermissions.canModifyUi(isFinishing = false, isDestroyed = false))
    }

    @Test
    fun `a finishing activity cannot show the dialog`() {
        // finish() is what the skip-startup shortcut does while the root grant is still
        // running; the window is not valid for a new dialog from that point on.
        assertFalse(AppPermissions.canModifyUi(isFinishing = true, isDestroyed = false))
    }

    @Test
    fun `a destroyed activity cannot show the dialog`() {
        assertFalse(AppPermissions.canModifyUi(isFinishing = false, isDestroyed = true))
    }

    @Test
    fun `neither flag needs the other to block the dialog`() {
        assertFalse(AppPermissions.canModifyUi(isFinishing = true, isDestroyed = true))
    }
}
