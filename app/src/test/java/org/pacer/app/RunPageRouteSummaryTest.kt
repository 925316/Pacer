package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route line at the top of the run page.
 *
 * The page is where the app opens, and it used to say nothing about the route: the only
 * mention lived on the Route tab, one tap away, so a fresh user could press Run and be told
 * to add waypoints without ever being shown where. The three cases below are what the line
 * has to say, kept out of the activity so they need no Android runtime.
 */
class RunPageRouteSummaryTest {

    private val both = listOf(EnglishStrings, ChineseStrings)

    @Test
    fun `an empty draft is asked to draw one, not told what is missing`() {
        for (strings in both) {
            val text = runPageRouteSummary(0, 0.0, complete = false, strings = strings)
            // A count of zero formatted into a summary reads like a route that exists.
            assertEquals(strings.runPageNoRoute, text)
            assertFalse(text, text.contains("%"))
        }
    }

    @Test
    fun `a drawn route states its length and says the line is tappable`() {
        for (strings in both) {
            val text = runPageRouteSummary(3, 1240.0, complete = true, strings = strings)
            assertTrue(text, text.contains("3"))
            assertTrue(text, text.contains("1240"))
            assertTrue(text, text.contains(strings.routeSummaryOpenMapHint))
            assertFalse(text, text.contains(strings.routeSummaryIncomplete))
            assertFalse(text, text.contains("%"))
        }
    }

    @Test
    fun `an incomplete route says what is missing and still says where to go`() {
        for (strings in both) {
            val text = runPageRouteSummary(1, 0.0, complete = false, strings = strings)
            assertTrue(text, text.contains("1"))
            assertTrue(text, text.contains(strings.routeSummaryIncomplete))
            assertTrue(text, text.contains(strings.routeSummaryOpenMapHint))
            assertFalse(text, text.contains("%"))
        }
    }

    /** The line has to look tappable, because a hidden tap target is the bug being fixed. */
    @Test
    fun `the tappable hint is translated and says it is tappable`() {
        assertTrue(
            "\"${EnglishStrings.routeSummaryOpenMapHint}\" does not say it opens the map",
            EnglishStrings.routeSummaryOpenMapHint.contains("open the map"),
        )
        assertTrue(
            "\"${ChineseStrings.routeSummaryOpenMapHint}\" does not mention tapping",
            ChineseStrings.routeSummaryOpenMapHint.contains("点"),
        )
    }

    /** A start refused for want of a route offers the map, and each refusal names its step. */
    @Test
    fun `the notice action and the two start refusals are translated`() {
        assertEquals("Open the map", EnglishStrings.openMapAction)
        assertEquals("打开地图", ChineseStrings.openMapAction)

        // No route at all: it has to say which page the map is on, not just "the map".
        assertTrue(
            "\"${EnglishStrings.startNeedsRouteOnMap}\" names no page",
            EnglishStrings.startNeedsRouteOnMap.contains("Route page"),
        )
        assertTrue(
            "\"${ChineseStrings.startNeedsRouteOnMap}\" names no page",
            ChineseStrings.startNeedsRouteOnMap.contains("路线"),
        )

        // One waypoint: the joystick is the alternative, so it has to be named as one.
        assertTrue(
            "\"${EnglishStrings.startNeedsAnotherWaypoint}\" mentions no joystick",
            EnglishStrings.startNeedsAnotherWaypoint.contains("joystick"),
        )
        assertTrue(
            "\"${ChineseStrings.startNeedsAnotherWaypoint}\" mentions no joystick",
            ChineseStrings.startNeedsAnotherWaypoint.contains("摇杆"),
        )
    }
}
