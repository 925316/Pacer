package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.motion.PaceDraft
import org.pacer.motion.PlanDraft

/**
 * The one rule the plan editor exists to enforce: a run has one speed source.
 *
 * Both flags the editor used to hold independently being true at once - the curve enabled and
 * a non-empty step list - is the state in which the rows on screen are not the plan being
 * run. Adopting a preset across that state is what made the preset appear and then be
 * silently replaced by the curve, so it is checked here rather than only on a device.
 */
class PlanEditorModeTest {

    private fun neverBoth(state: PlanSourceState, what: String) {
        assertFalse(
            "$what left the curve and the steps both in effect",
            state.paceEnabled && state.draft.steps.isNotEmpty(),
        )
    }

    @Test
    fun `adopting a step preset while the curve is on makes the steps the source`() {
        val state = PlanSourceState()
        state.usePace()
        assertEquals("the curve should be in effect first", PlanSource.PACE, state.source)

        val preset = PlanDraft.PRESETS.first { it.id == "human" }
        state.adoptSteps(preset)

        neverBoth(state, "adoptSteps")
        assertFalse("the curve must be displaced", state.paceEnabled)
        assertEquals(PlanSource.STEPS, state.source)
        assertEquals(preset.intoDraft().toWireFormat(), state.toWireFormat())
        assertTrue(state.isValid)
    }

    @Test
    fun `every transition keeps at most one shape in effect`() {
        val state = PlanSourceState()
        state.usePace()
        neverBoth(state, "usePace")

        state.adoptSteps(PlanDraft.PRESETS.first())
        neverBoth(state, "adoptSteps")

        state.adoptPace(PaceDraft.PRESETS.first())
        neverBoth(state, "adoptPace")
        assertEquals(PlanSource.PACE, state.source)

        state.addStep()
        neverBoth(state, "addStep")
        assertEquals("adding a step must select the steps", PlanSource.STEPS, state.source)

        state.usePace()
        neverBoth(state, "usePace again")

        state.stopPace()
        neverBoth(state, "stopPace")
        assertEquals("nothing is selected after the curve is dropped", PlanSource.STEADY, state.source)
    }

    @Test
    fun `a loaded curve does not leave stale steps behind it`() {
        val state = PlanSourceState()
        state.adoptSteps(PlanDraft.PRESETS.first())
        assertTrue(state.draft.steps.isNotEmpty())

        val wire = "pace:0=3.3333;1000=3.3333"
        assertTrue(state.load(wire))

        neverBoth(state, "load")
        assertEquals(PlanSource.PACE, state.source)
        assertTrue("stale steps must be cleared", state.draft.steps.isEmpty())
    }

    @Test
    fun `a loaded step plan turns the curve off`() {
        val state = PlanSourceState()
        state.usePace()
        assertTrue(state.load("3.0@180.0;1.4@60.0#5"))

        neverBoth(state, "load")
        assertEquals(PlanSource.STEPS, state.source)
    }

    @Test
    fun `an unreadable load changes neither shape`() {
        val state = PlanSourceState()
        state.adoptSteps(PlanDraft.PRESETS.first())
        val before = state.toWireFormat()

        assertFalse(state.load("not a plan"))
        assertFalse(state.load(""))

        assertEquals(before, state.toWireFormat())
        assertEquals(PlanSource.STEPS, state.source)
    }

    @Test
    fun `an absent plan is steady speed and is not invalid`() {
        val state = PlanSourceState()
        assertEquals(PlanSource.STEADY, state.source)
        assertEquals("", state.toWireFormat())
        assertTrue(state.isValid)
    }

    @Test
    fun `the source is derived from the two facts, and both at once still resolves`() {
        // The pure function is what the editor reads, so it has to have an answer even for the
        // pair the header used to resolve on its own. What keeps the pair from arising at all
        // is the transition tests above, not this function.
        assertEquals(PlanSource.STEADY, PlanSource.of(paceEnabled = false, stepCount = 0))
        assertEquals(PlanSource.STEPS, PlanSource.of(paceEnabled = false, stepCount = 3))
        assertEquals(PlanSource.PACE, PlanSource.of(paceEnabled = true, stepCount = 0))
        assertEquals(PlanSource.PACE, PlanSource.of(paceEnabled = true, stepCount = 3))
    }

    @Test
    fun `every pace preset is named in both languages`() {
        val names = mapOf(
            "en" to EnglishStrings.pacePresetNames,
            "zh" to ChineseStrings.pacePresetNames,
        )
        for ((language, table) in names) {
            assertEquals(language, PaceDraft.PRESETS.map { it.id }.toSet(), table.keys)
            for ((id, label) in table) {
                assertTrue("$language/$id is blank", label.isNotBlank())
            }
        }
    }

    @Test
    fun `the two languages do not reuse the same pace label by accident`() {
        for (preset in PaceDraft.PRESETS) {
            val en = EnglishStrings.pacePresetNames.getValue(preset.id)
            val zh = ChineseStrings.pacePresetNames.getValue(preset.id)
            assertTrue("${preset.id} is untranslated ($zh)", en != zh)
        }
    }
}
