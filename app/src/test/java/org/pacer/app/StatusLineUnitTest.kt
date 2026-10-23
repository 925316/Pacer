package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state line's unit, which is [Strings.unitMps] and not a literal.
 *
 * The line used to hard-code "m/s" while the speed box a few centimetres away showed
 * "米/秒" in Chinese, so one value had two spellings on one screen. The template carries the
 * unit as its last specifier; the order matters because the same call site fills both
 * languages.
 */
class StatusLineUnitTest {

    private val both = listOf(EnglishStrings, ChineseStrings)

    @Test
    fun `the template asks for label, time, position, speed and unit, in that order`() {
        for (strings in both) {
            assertEquals(
                listOf("%s", "%d", "%02d", "%s", "%.1f", "%s"),
                specifiers(strings.statusLine),
            )
        }
    }

    @Test
    fun `each language prints its own unit`() {
        assertEquals("m/s", EnglishStrings.unitMps)
        assertEquals("米/秒", ChineseStrings.unitMps)
    }

    @Test
    fun `the line ends with the unit of the language it is written in`() {
        for (strings in both) {
            val text = statusLineText(
                strings = strings,
                label = strings.statusRunning,
                elapsedSeconds = 102L,
                where = "43.82400, 87.61400",
                speedMps = 3.0,
            )
            assertTrue(text, text.endsWith(strings.unitMps))
            // The elapsed time keeps its two-digit seconds field.
            assertTrue(text, text.contains("1:42"))
            assertTrue(text, text.contains("43.82400, 87.61400"))
            assertTrue(text, text.contains("3.0"))
        }
    }

    @Test
    fun `the two languages differ only in the words around the numbers`() {
        val english = statusLineText(
            EnglishStrings, EnglishStrings.statusRunning, 0L, "-", 0.0,
        )
        val chinese = statusLineText(
            ChineseStrings, ChineseStrings.statusRunning, 0L, "-", 0.0,
        )
        assertTrue(english, english.endsWith(" m/s"))
        assertTrue(chinese, chinese.endsWith(" 米/秒"))
    }

    @Test
    fun `an hour-long run does not wrap its minute count`() {
        // The minute field is the elapsed seconds divided by 60, so past an hour it must keep
        // counting instead of dropping the hours or wrapping the two-digit field.
        val text = statusLineText(EnglishStrings, "Running", 7265L, "-", 1.0)
        assertTrue(text, text.contains("121:05"))
    }

    @Test
    fun `no Chinese interface text spells the speed unit in Latin`() {
        // The bug was one screen showing both spellings. Nothing user-visible in the Chinese
        // table may say "m/s", so every sentence about speed is checked here rather than
        // trusted to have been swept by hand.
        val chinese = listOf(
            ChineseStrings.unitMps,
            ChineseStrings.customSpeedHint,
            ChineseStrings.startedJoystickMode,
            ChineseStrings.startedRoute,
            ChineseStrings.speedMpsLabel,
            ChineseStrings.simulatingAtSpeed,
            ChineseStrings.statusLine,
        )
        for (text in chinese) {
            assertTrue("\"$text\" still says m/s", !text.contains("m/s"))
        }
        assertEquals("米/秒", ChineseStrings.unitMps)
    }

    private fun specifiers(template: String): List<String> =
        Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")
            .findAll(template)
            .map { it.value }
            .toList()
}
