package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor model, tested where it can be: without a device.
 *
 * The rules mirrored from [SpeedPlan] are the interesting part - an editor that produces a
 * plan the engine refuses would fail at Start, which is the silence this whole screen
 * exists to remove.
 */
class PlanDraftTest {

    @Test
    fun `a default draft is a valid steady run`() {
        val draft = PlanDraft()
        val plan = draft.toPlan()
        assertNotNull(plan)
        assertEquals(1, plan!!.steps.size)
        assertEquals(3.0, plan.steps[0].speedMps, 1e-9)
    }

    @Test
    fun `starts at one repeat and the wire form matches the advertised syntax`() {
        val draft = PlanDraft(
            steps = listOf(SpeedPlan.Step(3.0, 180.0), SpeedPlan.Step(1.4, 60.0)),
            repeats = 5,
        )
        assertEquals("3.0@180.0;1.4@60.0#5", draft.toWireFormat())
    }

    @Test
    fun `single pass omits the repeat suffix`() {
        val draft = PlanDraft(steps = listOf(SpeedPlan.Step(3.0, 180.0)), repeats = 1)
        assertEquals("3.0@180.0", draft.toWireFormat())
    }

    @Test
    fun `round trips through the wire format`() {
        val wire = "3.0@180.0;1.4@60.0#5"
        val draft = PlanDraft()
        assertTrue(draft.load(wire))
        assertEquals(wire, draft.toWireFormat())
    }

    @Test
    fun `blank wire leaves the draft alone`() {
        val draft = PlanDraft(steps = listOf(SpeedPlan.Step(5.0, 30.0)), repeats = 2)
        assertFalse(draft.load(""))
        assertEquals(1, draft.steps.size)
        assertEquals(5.0, draft.steps[0].speedMps, 1e-9)
        assertEquals(2, draft.repeats)
    }

    @Test
    fun `unreadable wire leaves the draft alone`() {
        val draft = PlanDraft()
        assertFalse(draft.load("not a plan"))
        assertEquals(1, draft.steps.size)
    }

    @Test
    fun `an all-zero-speed plan is rejected rather than hanging the engine`() {
        val draft = PlanDraft(steps = listOf(SpeedPlan.Step(0.0, 60.0)), repeats = 1)
        assertNull(draft.toPlan())
        assertEquals("", draft.toWireFormat())
    }

    @Test
    fun `adding copies the previous step`() {
        val draft = PlanDraft(steps = listOf(SpeedPlan.Step(4.5, 90.0)))
        draft.add()
        assertEquals(2, draft.steps.size)
        assertEquals(4.5, draft.steps[1].speedMps, 1e-9)
        assertEquals(90.0, draft.steps[1].durationSec, 1e-9)
    }

    @Test
    fun `moving a step past the ends is refused`() {
        val draft = PlanDraft(
            steps = listOf(SpeedPlan.Step(1.0, 10.0), SpeedPlan.Step(2.0, 20.0)),
            repeats = 1,
        )
        assertFalse(draft.move(0, -1))
        assertFalse(draft.move(1, 1))
        assertTrue(draft.move(0, 1))
        assertEquals(2.0, draft.steps[0].speedMps, 1e-9)
    }

    @Test
    fun `removing the last step leaves an invalid draft, not a crash`() {
        val draft = PlanDraft()
        draft.remove(0)
        assertTrue(draft.steps.isEmpty())
        assertNull(draft.toPlan())
    }

    @Test
    fun `speed and duration are clamped to what the engine accepts`() {
        val draft = PlanDraft()
        draft.setSpeed(0, -5.0)
        draft.setDuration(0, 0.0)
        assertEquals(0.0, draft.steps[0].speedMps, 1e-9)
        assertEquals(PlanDraft.MIN_DURATION_SEC, draft.steps[0].durationSec, 1e-9)

        draft.setSpeed(0, 999.0)
        assertEquals(PlanDraft.MAX_SPEED_MPS, draft.steps[0].speedMps, 1e-9)
    }

    @Test
    fun `repeats are clamped and zero means forever`() {
        val draft = PlanDraft()
        draft.repeats = -3
        assertEquals(0, draft.repeats)
        assertNull(draft.totalSeconds)

        draft.repeats = 99_999
        assertEquals(PlanDraft.MAX_REPEATS, draft.repeats)
    }

    @Test
    fun `totals follow the plan arithmetic`() {
        val draft = PlanDraft(
            steps = listOf(SpeedPlan.Step(3.0, 180.0), SpeedPlan.Step(1.4, 60.0)),
            repeats = 5,
        )
        assertEquals(240.0, draft.passSeconds, 1e-9)
        assertEquals(540.0 + 84.0, draft.passMeters, 1e-9)
        assertEquals(1200.0, draft.totalSeconds!!, 1e-9)
    }

    @Test
    fun `every preset is a plan the engine accepts`() {
        for (preset in PlanDraft.PRESETS) {
            val plan = preset.intoDraft().toPlan()
            assertNotNull("preset '${preset.name}' must be runnable", plan)
        }
    }
}
