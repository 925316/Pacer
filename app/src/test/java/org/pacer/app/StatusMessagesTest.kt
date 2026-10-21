package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide what the user is told and for how long.
 *
 * Both are pure functions on purpose: the status line's honesty and the notice's lifetime are
 * the parts of this screen most likely to be broken by a later edit, and they are also the
 * only parts a JVM test can reach.
 */
class StatusMessagesTest {

    @Test
    fun `a failed start cannot leave the line claiming a run`() {
        // PacerConfigClient.start() writes nothing when the channel is unbound, so the stored
        // state stays inactive. The line must follow the stored state, not the tap.
        assertEquals(RunState.UNBOUND, runStateOf(active = false, paused = false, bound = false))
    }

    @Test
    fun `a bound idle app says nothing at all`() {
        // The pinned bar already has a disabled Pause button; a line reading "idle" beside it
        // is the same fact twice, and it is what occupied the top of every page.
        assertEquals(RunState.HIDDEN, runStateOf(active = false, paused = false, bound = true))
    }

    @Test
    fun `a paused run outranks a running one`() {
        assertEquals(RunState.PAUSED, runStateOf(active = true, paused = true, bound = true))
        assertEquals(RunState.RUNNING, runStateOf(active = true, paused = false, bound = true))
    }

    @Test
    fun `an unbound channel is reported even while idle`() {
        // This is the fact that explains "Run does nothing" for as long as it is true, which
        // is why it is a state and not a one-off notice.
        assertEquals(RunState.UNBOUND, runStateOf(active = false, paused = false, bound = false))
    }

    @Test
    fun `a failure never expires`() {
        assertEquals(0L, noticeDurationMs("module not bound", NoticeKind.FAILURE))
        assertEquals(0L, noticeDurationMs("a very long failure message ".repeat(20), NoticeKind.FAILURE))
    }

    @Test
    fun `progress stays until its result replaces it`() {
        assertEquals(0L, noticeDurationMs("Auditing the track...", NoticeKind.PROGRESS))
    }

    @Test
    fun `an info notice lasts longer when it is longer`() {
        val short = noticeDurationMs("Saved", NoticeKind.INFO)
        val long = noticeDurationMs(
            "Joystick shown. Drag the knob to steer, drag the ring to move it, tap to stop.",
            NoticeKind.INFO,
        )
        assertTrue("short $short should be at least 4 s", short >= 4_000L)
        assertTrue("long $long should outlast short $short", long > short)
    }

    @Test
    fun `even a long info notice eventually goes`() {
        // A ceiling matters: an unread paragraph must not pin itself over the page forever.
        val huge = noticeDurationMs("x".repeat(5_000), NoticeKind.INFO)
        assertTrue("expected a ceiling, got $huge", huge <= 12_000L)
    }
}
