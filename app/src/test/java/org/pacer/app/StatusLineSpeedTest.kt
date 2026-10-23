package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state line's speed figure, and the label of the control that deletes the tail point.
 *
 * The line used to print the configured speed, so a plan-driven run reported a constant
 * number while the engine walked through the plan's steps, and a paused route still
 * claimed to be moving. Both read as "the plan did nothing".
 *
 * Raised from a private method to [reportedSpeedMps] so this rule is reachable without an
 * Android runtime.
 */
class StatusLineSpeedTest {

    @Test
    fun `a sample's instantaneous speed wins over the configured one`() {
        // A route under a plan: configured 3.0, currently inside a 1.6 - 3.4 step.
        assertEquals(
            1.6,
            reportedSpeedMps(sampleMps = 1.6, configuredMps = 3.0, paused = false),
            1e-9,
        )
        assertEquals(
            3.4,
            reportedSpeedMps(sampleMps = 3.4, configuredMps = 3.0, paused = false),
            1e-9,
        )
    }

    @Test
    fun `a paused route reports zero however fast its sample says it moved`() {
        // The sample of a frozen route still carries the speed of the point it stopped on;
        // a paused run reports no speed, which is what the self-check substitutes too.
        assertEquals(
            0.0,
            reportedSpeedMps(sampleMps = 3.0, configuredMps = 3.0, paused = true),
            1e-9,
        )
    }

    @Test
    fun `without a sample the configured speed is the fallback`() {
        // Only reachable when the stored route cannot be simulated; an approximate number
        // beats blanking the only speed figure on the screen.
        assertEquals(
            2.5,
            reportedSpeedMps(sampleMps = null, configuredMps = 2.5, paused = false),
            1e-9,
        )
        assertEquals(
            0.0,
            reportedSpeedMps(sampleMps = null, configuredMps = 2.5, paused = true),
            1e-9,
        )
    }

    /** The honest label for the tail-point button, in both languages. */
    @Test
    fun `the delete-last-point label is translated`() {
        assertEquals("Delete last point", EnglishStrings.deleteLastPoint)
        assertTrue(
            "\"${ChineseStrings.deleteLastPoint}\" is not Chinese",
            ChineseStrings.deleteLastPoint.any { it in '\u4E00'..'\u9FFF' },
        )
    }
}
