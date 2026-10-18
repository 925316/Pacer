package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An interval plan is the one place in the engine where the target speed changes
 * without the subject changing activity, so both halves are pinned: the plan's own
 * arithmetic (speed and the distance it implies), and the profile actually following
 * it instead of one flat base speed.
 */
class SpeedPlanTest {

    private val intervals = SpeedPlan(
        steps = listOf(SpeedPlan.Step(3.0, 10.0), SpeedPlan.Step(1.0, 10.0)),
        repeats = 1,
    )

    @Test
    fun `speed follows the steps in time`() {
        assertEquals(3.0, intervals.speedAt(0.0), 1e-9)
        assertEquals(3.0, intervals.speedAt(9.9), 1e-9)
        assertEquals(1.0, intervals.speedAt(10.0), 1e-9)
        assertEquals(1.0, intervals.speedAt(19.9), 1e-9)
    }

    @Test
    fun `after the last pass the final step continues`() {
        assertEquals(1.0, intervals.speedAt(25.0), 1e-9)
        assertEquals(1.0, intervals.speedAt(3600.0), 1e-9)
    }

    @Test
    fun `distance is the exact integral of the speed`() {
        assertEquals(0.0, intervals.metersAt(0.0), 1e-9)
        assertEquals(30.0, intervals.metersAt(10.0), 1e-9)
        assertEquals(40.0, intervals.metersAt(20.0), 1e-9)
        assertEquals(45.0, intervals.metersAt(25.0), 1e-9)
    }

    @Test
    fun `repeats loop, and a plan that repeats forever keeps looping`() {
        val repeated = SpeedPlan(intervals.steps, repeats = 2)
        assertEquals(3.0, repeated.speedAt(20.0), 1e-9)
        assertEquals(80.0, repeated.metersAt(40.0), 1e-9)
        assertEquals(85.0, repeated.metersAt(45.0), 1e-9)

        val forever = SpeedPlan(intervals.steps, repeats = 0)
        assertNull(forever.totalSeconds)
        assertNull(forever.totalMeters)
        assertEquals(30.0, forever.metersAt(10.0), 1e-9)
        assertEquals(55.0, forever.metersAt(25.0), 1e-9)
    }

    @Test
    fun `the text form round trips and rejects nonsense`() {
        val parsed = SpeedPlan.parse("3.0@180;1.4@60#5")
        assertEquals("3.0@180.0;1.4@60.0#5", parsed!!.format())
        assertEquals(1, SpeedPlan.parse("3.0@180;1.4@60")!!.repeats)
        assertEquals(0, SpeedPlan.parse("3.0@180#0")!!.repeats)
        assertNull(SpeedPlan.parse(""))
        assertNull(SpeedPlan.parse("3.0"))
        assertNull(SpeedPlan.parse("3.0@0"))
        assertNull(SpeedPlan.parse("-1@60"))
        assertNull(SpeedPlan.parse("3.0@60#-2"))
    }

    @Test
    fun `the profile follows the plan instead of one flat speed`() {
        // Noise and pauses off, so the plan is the only thing moving the speed, and
        // the acquire ramp is over long before the windows below.
        val config = MotionConfig(
            baseSpeedMps = 3.0,
            acquireDurationSec = 2.0,
            cruiseNoiseFraction = 0.0,
            pausesPerMinute = 0.0,
            fatiguePerKm = 0.0,
            plan = SpeedPlan(
                listOf(SpeedPlan.Step(4.0, 60.0), SpeedPlan.Step(1.0, 60.0)),
                repeats = 0,
            ),
        )
        val samples = SpeedProfile(config, seed = 11L).generate(distanceMeters = 600.0, dtSec = 1.0)

        val fast = samples.filter { it.tSec in 20.0..50.0 }.map { it.speedMps }
        val slow = samples.filter { it.tSec in 80.0..110.0 }.map { it.speedMps }

        assertTrue("no samples in the fast window", fast.isNotEmpty())
        assertTrue("fast window speeds: $fast", fast.all { it > 3.5 })
        assertTrue("no samples in the slow window", slow.isNotEmpty())
        assertTrue("slow window speeds: $slow", slow.all { it < 1.5 })
    }
}
