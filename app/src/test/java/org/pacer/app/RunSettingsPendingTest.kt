package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count behind the Run button.
 *
 * The Settings page writes nothing until Run is tapped, so the only honest way to say that
 * is to compare the controls with what the last run stored. That comparison is a pure
 * function on purpose: it decides whether the button claims work is pending, and a wrong
 * answer is either a silent loss (nothing said while edits wait) or a permanent lie (a count
 * that never clears).
 */
class RunSettingsPendingTest {

    private val saved = RunSettings(
        speed = "3.0",
        accuracy = "5.0",
        altitude = "0.0",
        bearing = "45.0",
        plan = "",
        loopRoute = false,
        stepSimulation = true,
        mockBluetooth = true,
        bearingHold = true,
        altitudeDrift = true,
        accuracyWarmup = true,
        satelliteJitter = true,
        signalDropout = true,
        positionNoise = true,
    )

    @Test
    fun `nothing pending when the controls match the stored run`() {
        assertEquals(0, saved.pendingChanges(saved))
    }

    @Test
    fun `retyping a number is not a change`() {
        // The fields format what they restore ("5.0"), and a user may well type "5". Text
        // comparison would report a change that Run would not actually make.
        assertFalse(numberDiffers("5", "5.0"))
        assertFalse(numberDiffers(" 5.0 ", "5.0"))
        assertEquals(0, saved.copy(accuracy = "5").pendingChanges(saved))
    }

    @Test
    fun `a typed number counts once`() {
        assertEquals(1, saved.copy(accuracy = "8.0").pendingChanges(saved))
        assertEquals(2, saved.copy(accuracy = "8.0", bearing = "90").pendingChanges(saved))
    }

    @Test
    fun `an unreadable box counts as pending`() {
        // Run replaces a blank box with a documented fallback, which is not the value the
        // last run used; hiding that would make the button look like there is nothing to do.
        assertTrue(numberDiffers("", "5.0"))
        assertEquals(1, saved.copy(accuracy = "").pendingChanges(saved))
    }

    @Test
    fun `two unreadable boxes are still equal`() {
        // A stored value that cannot be read is compared with the same text; without this a
        // blank box could never leave the pending state.
        assertFalse(numberDiffers("", ""))
    }

    @Test
    fun `every switch and the plan are counted`() {
        assertEquals(1, saved.copy(loopRoute = true).pendingChanges(saved))
        assertEquals(1, saved.copy(stepSimulation = false).pendingChanges(saved))
        assertEquals(1, saved.copy(plan = "3.0@180").pendingChanges(saved))
        assertEquals(
            3,
            saved.copy(loopRoute = true, positionNoise = false, plan = "3.0@180")
                .pendingChanges(saved),
        )
    }

    @Test
    fun `the run button only carries a count when there is one`() {
        for (strings in listOf(EnglishStrings, ChineseStrings)) {
            assertEquals(strings.startRouteShort, runButtonLabel(0, strings))
            assertEquals(strings.startRouteShort, runButtonLabel(-1, strings))
            val changed = runButtonLabel(3, strings)
            assertTrue(changed, changed.contains("3"))
            assertFalse(changed, changed == strings.startRouteShort)
        }
    }

    @Test
    fun `both pending templates take exactly one number`() {
        for (strings in listOf(EnglishStrings, ChineseStrings)) {
            val line = String.format(java.util.Locale.US, strings.settingsUnsavedCount, 2)
            assertTrue(line, line.contains("2"))
            val label = String.format(java.util.Locale.US, strings.runButtonWithUnsaved, 2)
            assertTrue(label, label.contains("2"))
        }
    }
}
