package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal-realism switches are user-facing, so each one gets both halves of the
 * assertion: the behaviour is present when it is on, and absent when it is off. A test
 * that only checked the "off" case could not tell a working switch from a model that
 * never did anything.
 */
class SignalRealismTest {

    @Test
    fun `accuracy ramps down while warmup is on`() {
        val realism = SignalRealism(seed = 1L)
        val early = realism.next(0.0, 3.0, 1.0, 45f)
        val settled = realism.next(120.0, 3.0, 1.0, 45f)
        assertTrue("early=${early.accuracyMeters} settled=${settled.accuracyMeters}", early.accuracyMeters > settled.accuracyMeters)
    }

    @Test
    fun `accuracy is steady from the first sample when warmup is off`() {
        val realism = SignalRealism(toggles = RealismToggles(accuracyWarmup = false), seed = 1L)
        val first = realism.next(0.0, 3.0, 1.0, 45f)
        val later = realism.next(120.0, 3.0, 1.0, 45f)
        assertEquals(first.accuracyMeters, later.accuracyMeters, 1e-6f)
        // The steady accuracy, not the wide one a cold fix reports.
        assertTrue("accuracy=${first.accuracyMeters}", first.accuracyMeters < 10f)
    }

    @Test
    fun `altitude walks while the drift is on`() {
        val realism = SignalRealism(seed = 2L)
        repeat(120) { realism.next(it.toDouble(), 3.0, 1.0, 0f) }
        assertTrue(kotlin.math.abs(realism.next(121.0, 3.0, 1.0, 0f).altitudeMeters) > 0.001)
    }

    @Test
    fun `altitude stays at zero when the drift is off`() {
        val realism = SignalRealism(toggles = RealismToggles(altitudeDrift = false), seed = 2L)
        repeat(120) { realism.next(it.toDouble(), 3.0, 1.0, 0f) }
        assertEquals(0.0, realism.next(121.0, 3.0, 1.0, 0f).altitudeMeters, 0.0)
    }

    @Test
    fun `satellite count varies while the jitter is on`() {
        val realism = SignalRealism(seed = 3L)
        val counts = (0 until 40).map { realism.next(it.toDouble(), 3.0, 1.0, 0f).satellites }
        assertTrue("counts=${counts.toSet()}", counts.toSet().size > 1)
    }

    @Test
    fun `satellite count is steady and plausible when the jitter is off`() {
        val realism = SignalRealism(toggles = RealismToggles(satelliteJitter = false), seed = 3L)
        val counts = (0 until 40).map { realism.next(it.toDouble(), 3.0, 1.0, 0f).satellites }
        assertEquals(1, counts.toSet().size)
        assertTrue("satellites=${counts.first()}", counts.first() in 7..14)
    }

    @Test
    fun `bearing is held while standing still`() {
        val realism = SignalRealism(seed = 4L)
        realism.next(0.0, 3.0, 1.0, 90f)
        val held = realism.next(1.0, 0.0, 1.0, 0f)
        assertEquals(90f, held.bearingDeg, 1e-6f)
    }

    @Test
    fun `bearing follows the movement when the hold is off`() {
        val realism = SignalRealism(toggles = RealismToggles(bearingHold = false), seed = 4L)
        realism.next(0.0, 3.0, 1.0, 90f)
        assertEquals(0f, realism.next(1.0, 0.0, 1.0, 0f).bearingDeg, 1e-6f)
    }

    @Test
    fun `dropout fires with the default switches`() {
        // The counterpart of the test below: the switch must not be hiding a model that
        // never drops out in the first place.
        val realism = SignalRealism(seed = 5L)
        val dropped = (0 until 6000).any { realism.next(it * 0.1, 3.0, 0.1, 0f).dropout }
        assertTrue(dropped)
    }

    @Test
    fun `dropout never fires when switched off`() {
        val realism = SignalRealism(toggles = RealismToggles(signalDropout = false), seed = 5L)
        val dropped = (0 until 6000).any { realism.next(it * 0.1, 3.0, 0.1, 0f).dropout }
        assertFalse(dropped)
    }

    @Test
    fun `all switches off still reports a usable fix`() {
        // A track with every embellishment off has to remain a valid fix: the toggles
        // remove realism, not the data.
        val realism = SignalRealism(
            toggles = RealismToggles(
                bearingHold = false,
                altitudeDrift = false,
                accuracyWarmup = false,
                satelliteJitter = false,
                signalDropout = false,
            ),
            seed = 6L,
        )
        val fix = realism.next(0.0, 3.0, 1.0, 123f)
        assertTrue(fix.accuracyMeters > 0f)
        assertEquals(123f, fix.bearingDeg, 1e-6f)
        assertFalse(fix.dropout)
    }
}
