package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The notice's lifetime across a trip to the background.
 *
 * The activity removes the hide callback in onPause and has to re-arm it on return; the
 * only thing that makes that safe is expressing the notice as a deadline rather than as a
 * countdown that survives in one place and not the other.
 */
class NoticeLifetimeTest {

    @Test
    fun `an info notice gets a deadline that is its start plus its life`() {
        assertEquals(5_000L, noticeDeadlineMs(startedAtMs = 1_000L, durationMs = 4_000L) ?: -1L)
    }

    @Test
    fun `a failure and a progress line have no deadline`() {
        val failure = noticeDurationMs("module not bound", NoticeKind.FAILURE)
        val progress = noticeDurationMs("Auditing the track...", NoticeKind.PROGRESS)
        assertNull(noticeDeadlineMs(1_000L, failure))
        assertNull(noticeDeadlineMs(1_000L, progress))
    }

    @Test
    fun `what is left shrinks by the time spent away`() {
        val deadline = noticeDeadlineMs(startedAtMs = 1_000L, durationMs = 4_000L)
        // Two seconds after it appeared, two seconds of life remain.
        assertEquals(2_000L, noticeRemainingMs(nowMs = 3_000L, deadlineMs = deadline) ?: -1L)
    }

    @Test
    fun `time away longer than the notice leaves nothing to re-arm`() {
        // The bug this replaces: a notice that outlived its own deadline was re-armed with
        // a full life, so coming back from the background showed it again.
        val deadline = noticeDeadlineMs(startedAtMs = 1_000L, durationMs = 4_000L)
        assertEquals(0L, noticeRemainingMs(nowMs = 60_000L, deadlineMs = deadline) ?: -1L)
        // Exactly at the deadline is already over; a zero delay would be a re-post.
        assertEquals(0L, noticeRemainingMs(nowMs = 5_000L, deadlineMs = deadline) ?: -1L)
    }

    @Test
    fun `a notice with no timer keeps no timer after a resume`() {
        assertNull(noticeRemainingMs(nowMs = 60_000L, deadlineMs = null))
    }
}
