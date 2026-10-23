package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.motion.StrideLinker

/**
 * The straight-line step counter must be a monotone integral of distance.
 *
 * It used to report `cadence(speedNow) / 60 * elapsedSinceStart`. That equals the integral
 * only while the speed is constant; once a pace curve or an interval plan makes the speed
 * fall, the product falls with it and the counter runs backwards. The detector-facing cost is
 * the same one this class already guards elsewhere: a counter that jumps down by hundreds in
 * one second is a shape no device produces.
 *
 * The accumulator is modelled here rather than called, because the real one lives in an
 * Android class that cannot be constructed in a JVM test. What is pinned is the rule the
 * implementation must follow.
 */
class StepCounterTest {

    /** What the counter must do: one increment per tick, from the distance covered. */
    private fun integrate(speeds: List<Double>, dtSec: Double = 1.0): List<Double> {
        var steps = 0.0
        return speeds.map { v ->
            if (v > 0.05 && dtSec > 0) steps += v * dtSec / StrideLinker.strideForSpeed(v)
            steps
        }
    }

    /** The formula that was wrong: current cadence times total elapsed time. */
    private fun oldFormula(speeds: List<Double>, dtSec: Double = 1.0): List<Double> {
        var t = 0.0
        return speeds.map { v ->
            t += dtSec
            val cadence = if (v <= 0.05) 0.0 else v / StrideLinker.strideForSpeed(v) * 60.0
            cadence / 60.0 * t
        }
    }

    private fun fromPace(splits: List<Double>, secondsPerSegment: Int): List<Double> =
        splits.flatMap { split -> List(secondsPerSegment) { 1000.0 / split } }

    @Test
    fun `a constant speed accumulates exactly what the old formula gave`() {
        // The old formula was not wrong here, which is why the defect survived: with a flat
        // speed the two agree, and that is the case anyone would test by hand.
        val speeds = List(600) { 3.0 }
        assertEquals(oldFormula(speeds).last(), integrate(speeds).last(), 1e-9)
    }

    @Test
    fun `a falling speed never lowers the counter`() {
        // 4:00 then 10:00 per kilometre: both inside the editor's allowed range.
        val speeds = fromPace(listOf(240.0, 600.0), 420)
        val steps = integrate(speeds)
        for (i in 1 until steps.size) {
            assertTrue(
                "counter dropped at second $i: ${steps[i - 1]} -> ${steps[i]}",
                steps[i] >= steps[i - 1],
            )
        }
        // And the same curve DOES break the old formula, so the test is not vacuous.
        val old = oldFormula(speeds)
        val drops = (1 until old.size).count { old[it] < old[it - 1] }
        assertTrue("the old formula should be shown to be broken here", drops > 0)
    }

    @Test
    fun `a paced run accumulates the distance it actually covered`() {
        // Steps and distance have to describe one motion: total steps times the stride at each
        // speed is the distance the position travels, which is what an audit compares.
        for (speed in listOf(1.0, 1.4, 2.5, 3.0, 4.5, 6.0)) {
            val seconds = 600
            val steps = integrate(List(seconds) { speed }).last()
            val distance = speed * seconds
            assertEquals(
                "speed $speed",
                distance,
                steps * StrideLinker.strideForSpeed(speed),
                1e-6,
            )
        }
    }

    @Test
    fun `an interval plan does not lose steps at the changeover`() {
        // The timed plan is the other varying-speed source, and it was broken the same way.
        val speeds = List(180) { 3.0 } + List(60) { 1.4 } + List(180) { 3.0 }
        val steps = integrate(speeds)
        for (i in 1 until steps.size) {
            assertTrue("dropped at second $i", steps[i] >= steps[i - 1])
        }
        // Steps keep growing through the slow minute, just more slowly.
        val beforeSlow = steps[179]
        val afterSlow = steps[239]
        assertTrue("no steps during the slow minute", afterSlow > beforeSlow)
    }

    @Test
    fun `a standing subject accumulates nothing`() {
        val steps = integrate(List(60) { 0.0 })
        assertEquals(0.0, steps.last(), 0.0)
    }

    @Test
    fun `an irregular tick does not change the total`() {
        // The counter advances by the interval since the previous call, so the same distance
        // must come out whether it is sampled every second or every three.
        val perSecond = integrate(List(300) { 3.0 }, dtSec = 1.0).last()
        val perThree = integrate(List(100) { 3.0 }, dtSec = 3.0).last()
        assertEquals(perSecond, perThree, 1e-9)
    }
}
