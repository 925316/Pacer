package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor works in minutes per kilometre; the engine works in metres per second. This is
 * the seam between them, so the tests are about the two agreeing rather than about either
 * one alone.
 */
class PaceDraftTest {

    @Test
    fun `pace and speed are the same number in different units`() {
        val draft = PaceDraft(listOf(300.0, 200.0))
        assertEquals(1000.0 / 300.0, draft.speedOf(0), 1e-9)
        assertEquals(1000.0 / 200.0, draft.speedOf(1), 1e-9)
    }

    @Test
    fun `the default draft is a valid three kilometre session`() {
        val draft = PaceDraft()
        assertEquals(3, draft.splits.size)
        assertEquals(3000.0, draft.distanceMeters, 1e-9)
        assertNotNull(draft.toPlan())
        // 300 + 310 + 320 s
        assertEquals(930.0, draft.totalSeconds, 1.0)
    }

    @Test
    fun `every split survives a round trip through the stored text`() {
        val original = PaceDraft(listOf(285.0, 300.0, 312.5))
        val parsed = PaceDraft()
        assertTrue(parsed.load(original.toWireFormat()))
        assertEquals(original.splits.size, parsed.splits.size)
        for (i in original.splits.indices) {
            assertEquals(original.splits[i], parsed.splits[i], 0.5)
        }
    }

    @Test
    fun `the mean pace is what an overall target compares against`() {
        val draft = PaceDraft(listOf(300.0, 300.0, 300.0))
        assertEquals(300.0, draft.averageSplit, 1e-9)
        // A flat 5:00 kilometre is 3.333 m/s.
        assertEquals(1000.0 / 300.0, draft.averageSpeed, 1e-9)
    }

    @Test
    fun `a fading session has a slower mean than its first kilometre`() {
        val draft = PaceDraft(listOf(280.0, 300.0, 320.0))
        assertTrue(draft.averageSplit > draft.splits.first())
        assertTrue(draft.averageSplit < draft.splits.last())
    }

    @Test
    fun `pace is clamped so a split can never mean a standstill`() {
        val draft = PaceDraft(listOf(300.0))
        draft.setSplit(0, 1.0)
        assertEquals(PaceDraft.MIN_SPLIT_SEC, draft.splits[0], 1e-9)
        draft.setSplit(0, 99_999.0)
        assertEquals(PaceDraft.MAX_SPLIT_SEC, draft.splits[0], 1e-9)
        // Even at the slow end the speed stays positive, which the curve requires.
        assertTrue(draft.speedOf(0) > 0.0)
    }

    @Test
    fun `the last split cannot be removed`() {
        val draft = PaceDraft(listOf(300.0, 310.0))
        draft.remove(0)
        assertEquals(1, draft.splits.size)
        draft.remove(0)
        assertEquals("one kilometre is still a plan", 1, draft.splits.size)
    }

    @Test
    fun `adding a kilometre copies the last pace rather than inventing one`() {
        val draft = PaceDraft(listOf(300.0, 305.0))
        draft.add()
        assertEquals(3, draft.splits.size)
        assertEquals(305.0, draft.splits[2], 1e-9)
    }

    @Test
    fun `a timed plan is refused rather than loaded as a pace plan`() {
        val draft = PaceDraft(listOf(300.0))
        assertTrue(!draft.load("3.0@180;1.4@60#5"))
        assertEquals("a refused load must not touch the draft", 1, draft.splits.size)
    }
}
