package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A per-kilometre pace is a promise about DELIVERY, so these tests measure the run the
 * engine actually produces instead of the analytic integral of the curve. The integral is
 * [PacePlanTest]'s subject; what is pinned here is that nothing multiplies or pads the
 * splits on their way out of the profile.
 *
 * The prescribed runs use the DEFAULT fatigue and pause settings on purpose. A test that
 * switched both off would pass against the very defect it is meant to catch.
 */
class PaceDeliveryTest {

    private fun prescribed(
        splits: List<Double>,
        distanceMeters: Double,
        seed: Long,
        noiseFraction: Double = 0.12,
    ): List<SpeedSample> {
        val config = MotionConfig(
            baseSpeedMps = 3.0,
            cruiseNoiseFraction = noiseFraction,
            pacePlan = PacePlan.fromKilometreSplits(splits),
        )
        return SpeedProfile(config, seed).generate(distanceMeters, dtSec = 1.0)
    }

    /** Time the run crossed [mark], interpolated inside the step that bracketed it. */
    private fun timeAt(samples: List<SpeedSample>, mark: Double): Double {
        for (i in 1 until samples.size) {
            val before = samples[i - 1]
            val after = samples[i]
            if (after.distanceMeters >= mark) {
                val span = after.distanceMeters - before.distanceMeters
                if (span <= 1e-9) return after.tSec
                val k = (mark - before.distanceMeters) / span
                return before.tSec + (after.tSec - before.tSec) * k
            }
        }
        return samples.last().tSec
    }

    /** One entry per kilometre: the time spent between consecutive marks. */
    private fun splitTimes(samples: List<SpeedSample>, kilometres: Int): List<Double> {
        val out = ArrayList<Double>(kilometres)
        var previous = 0.0
        for (i in 1..kilometres) {
            val mark = timeAt(samples, i * 1000.0)
            out.add(mark - previous)
            previous = mark
        }
        return out
    }

    private fun mean(values: Collection<Double>): Double = values.average()

    private fun spread(values: Collection<Double>): Double {
        val m = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    @Test
    fun `a prescribed pace carries no systematic padding`() {
        // Noise off, so this is one deterministic run of the real integrator: whatever the
        // total is here is the engine's own bias rather than a sample of its wobble. It
        // used to finish roughly a quarter over the sum of the splits, because fatigue was
        // charged on top of a curve that already stated it and random stops were added too.
        val target = listOf(300.0, 310.0, 320.0)
        val samples = prescribed(target, 3000.0, seed = 1L, noiseFraction = 0.0)
        val times = splitTimes(samples, target.size)

        assertEquals("whole session", 930.0, times.sum(), 9.3)
        assertTrue("first kilometre ${times[0]}", times[0] in 297.0..305.0)
        assertEquals("kilometre 2", 310.0, times[1], 3.1)
        assertEquals("kilometre 3", 320.0, times[2], 3.2)

        val flat = prescribed(List(5) { 300.0 }, 5000.0, seed = 1L, noiseFraction = 0.0)
        assertEquals("flat 5 km", 1500.0, splitTimes(flat, 5).sum(), 15.0)
    }

    @Test
    fun `a prescribed pace delivers the splits the user typed`() {
        val target = listOf(300.0, 310.0, 320.0)
        val totals = ArrayList<Double>(SEEDS)
        val perKm = List(target.size) { ArrayList<Double>(SEEDS) }
        var stopSamples = 0
        for (seed in 1..SEEDS) {
            val samples = prescribed(target, 3000.0, seed.toLong())
            val times = splitTimes(samples, target.size)
            for (i in target.indices) perKm[i].add(times[i])
            totals.add(times.sum())
            stopSamples += samples.count {
                it.phase == MotionPhase.PAUSE || it.phase == MotionPhase.DECEL
            }
        }

        // A stop inside a kilometre that was timed to the second is an addition nobody
        // asked for, and the editor's totals line does not count it.
        assertTrue("$stopSamples stop samples in a prescribed session", stopSamples == 0)

        // The cruise noise is deliberately kept, so a single seed is a sample of the
        // delivery rather than the delivery: averaging is what separates the systematic
        // error from the wobble that is supposed to be there.
        val expectedTotal = target.sum()
        assertEquals(
            "finish time over $SEEDS seeds",
            expectedTotal,
            mean(totals),
            expectedTotal * 0.01,
        )
        // Starting from rest is real and costs about 2.5 s in the first kilometre, so
        // that kilometre is allowed the extra five seconds the other two are not.
        val first = mean(perKm[0])
        assertTrue("first kilometre $first", first in 297.0..305.0)
        for (i in 1 until target.size) {
            assertEquals(
                "kilometre ${i + 1}",
                target[i],
                mean(perKm[i]),
                target[i] * 0.01,
            )
        }
        // Removing double-counted fatigue must not remove the natural wobble: the same
        // kilometre still varies from one run of the same session by seconds.
        assertTrue("kilometre 2 spread ${spread(perKm[1])}", spread(perKm[1]) > 2.0)
    }

    @Test
    fun `a flat prescribed pace delivers the promised total`() {
        val target = List(5) { 300.0 }
        val totals = ArrayList<Double>(SEEDS)
        val perKm = List(target.size) { ArrayList<Double>(SEEDS) }
        for (seed in 1..SEEDS) {
            val times = splitTimes(prescribed(target, 5000.0, seed.toLong()), target.size)
            for (i in target.indices) perKm[i].add(times[i])
            totals.add(times.sum())
        }

        // 5:00/km for 5 km is 25:00. This is the case that used to finish well over 30 min.
        assertEquals("total over $SEEDS seeds", 1500.0, mean(totals), 15.0)
        val first = mean(perKm[0])
        assertTrue("first kilometre $first", first in 297.0..305.0)
        for (i in 1 until target.size) {
            assertEquals("kilometre ${i + 1}", 300.0, mean(perKm[i]), 3.0)
        }
    }

    @Test
    fun `a timed plan still inserts pauses`() {
        // Suspending stops is specific to a prescribed pace curve. An interval session
        // still belongs to a person who stops for traffic.
        val config = MotionConfig(
            baseSpeedMps = 3.0,
            pausesPerMinute = 0.4,
            plan = SpeedPlan(listOf(SpeedPlan.Step(3.0, 600.0)), repeats = 1),
        )
        for (seed in 1L..4L) {
            val samples = SpeedProfile(config, seed).generate(6000.0, dtSec = 1.0)
            assertTrue(
                "seed $seed produced no stop; a timed plan must still pause",
                samples.any { it.phase == MotionPhase.PAUSE },
            )
        }
    }

    @Test
    fun `a timed plan still slows down with distance`() {
        // The other half of the same guard: fatigue is still a property of a person on an
        // interval session. Pauses are off here only so they cannot pollute the mean.
        val config = MotionConfig(
            baseSpeedMps = 3.0,
            fatiguePerKm = 0.07,
            pausesPerMinute = 0.0,
            plan = SpeedPlan(listOf(SpeedPlan.Step(3.0, 900.0)), repeats = 1),
        )
        // A window is only a few hundred cruise samples and the cruise noise is wider than
        // the 6% fatigue gap between them, so one seed can put the late window ahead by
        // chance. Pooling the windows over seeds measures the drift instead.
        val early = ArrayList<Double>()
        val late = ArrayList<Double>()
        for (seed in 1L..20L) {
            val samples = SpeedProfile(config, seed).generate(3000.0, dtSec = 1.0)
            for (s in samples) {
                if (s.phase != MotionPhase.CRUISE) continue
                if (s.distanceMeters in 300.0..800.0) {
                    early.add(s.speedMps)
                } else if (s.distanceMeters in 2500.0..2950.0) {
                    late.add(s.speedMps)
                }
            }
        }
        assertTrue("no early samples", early.isNotEmpty())
        assertTrue("no late samples", late.isNotEmpty())
        assertTrue(
            "early ${mean(early)} late ${mean(late)}: fatigue must still bite",
            mean(late) < mean(early) * 0.95,
        )
    }

    @Test
    fun `the same seed rebuilds a prescribed run bit for bit`() {
        // The app's live marker rebuilds this run from the seed rather than reading the
        // module's state, so a new random draw anywhere would put the two markers in
        // different places for the same second.
        val route = RoutePath(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.027)))
        val config = MotionConfig(
            baseSpeedMps = 3.0,
            pacePlan = PacePlan.fromKilometreSplits(listOf(300.0, 310.0, 320.0)),
        )
        fun rebuild(): List<FixSample> =
            RouteSimulator(route = route, config = config).simulate(dtSec = 1.0, seed = 4242L)

        val a = rebuild()
        val b = rebuild()
        assertEquals("sample count", a.size, b.size)
        assertEquals("positions", a.map { it.point }, b.map { it.point })
        assertEquals("speeds", a.map { it.speedMps }, b.map { it.speedMps })
        assertEquals("distances", a.map { it.distanceMeters }, b.map { it.distanceMeters })
    }

    private companion object {
        /** Enough seeds that the mean split is pinned far tighter than the advertised 1%. */
        const val SEEDS = 400
    }
}
