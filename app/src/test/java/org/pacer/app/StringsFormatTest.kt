package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Format templates are only checked by the compiler as strings, so a template that asks for
 * four numbers and a call that supplies two compiles fine and throws when drawn.
 *
 * That is exactly what shipped: `planTotals` had four specifiers and got two arguments, and
 * the plan editor took the activity down on every start. These tests format the templates
 * the way the app formats them, so the same mistake fails the build instead of the device.
 */
class StringsFormatTest {

    private val both = listOf(EnglishStrings, ChineseStrings)

    private fun specifiers(template: String): List<String> =
        Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")
            .findAll(template)
            .map { it.value }
            .toList()

    @Test
    fun `plan totals takes one pass and one total, in that order`() {
        for (strings in both) {
            assertEquals(4, specifiers(strings.planTotals).size)
            assertEquals(2, specifiers(strings.planTotalsForever).size)

            val text = formatPlanTotals(
                passSeconds = 240.0,
                passMeters = 624.0,
                totalSeconds = 1200.0,
                totalMeters = 3120.0,
                totals = strings.planTotals,
                totalsForever = strings.planTotalsForever,
            )
            // Both passes and the totals have to be in the line: formatting only the
            // first two numbers throws.
            assertTrue(text, text.contains("240"))
            assertTrue(text, text.contains("624"))
            assertTrue(text, text.contains("20"))
            assertTrue(text, text.contains("3.12"))
        }
    }

    @Test
    fun `an endless plan reports one pass and no total`() {
        for (strings in both) {
            val text = formatPlanTotals(
                passSeconds = 240.0,
                passMeters = 624.0,
                totalSeconds = null,
                totalMeters = null,
                totals = strings.planTotals,
                totalsForever = strings.planTotalsForever,
            )
            assertTrue(text, text.contains("240"))
            assertTrue(text, text.contains("624"))
        }
    }

    /**
     * Values by position: `%d` rejects a double, so the type has to follow the specifier.
     * Chinese templates happen to list the speed before the count, which is why this is
     * checked per template rather than assumed.
     */
    private fun fill(template: String): String {
        val specs = specifiers(template)
        val args = specs.map { spec ->
            when (spec.last()) {
                'd' -> 1
                'f', 'e', 'g' -> 1.0
                else -> "x"
            }
        }.toTypedArray()
        return String.format(Locale.US, template, *args)
    }

    @Test
    fun `every template the app formats accepts its arguments`() {
        for (strings in both) {
            val templates = listOf(
                strings.planTotals,
                strings.planTotalsForever,
                strings.planSummary,
                strings.waypointSelected,
                strings.centredOnSubject,
                strings.movedTo,
                strings.simulatingAtSpeed,
                strings.simulatingWithPlan,
                strings.clearRouteMessage,
                strings.noTilesForLayer,
                strings.startedJoystickMode,
                strings.startedJoystickModeWithPlan,
                strings.startedRoute,
                strings.startedRouteWithPlan,
                strings.planPassSummary,
                strings.planTotalSummary,
                strings.mapRouteSummary,
                strings.recordedSummary,
                strings.unexpectedFailure,
                strings.selfCheckCrashed,
                strings.selfCheckDone,
                strings.overridesSaved,
                strings.overridesSavedPartial,
                strings.routeReplaced,
                strings.routeSavedAs,
                strings.routeLoadedAs,
                strings.nameRequiredWithLimit,
            )
            for (template in templates) {
                assertTrue("no specifiers in \"$template\"", specifiers(template).isNotEmpty())
                fill(template)
            }
        }
    }
}
