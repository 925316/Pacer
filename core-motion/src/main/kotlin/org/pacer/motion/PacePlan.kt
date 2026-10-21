package org.pacer.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * A pace curve indexed by DISTANCE, which is how a runner describes a session.
 *
 * [SpeedPlan] is the other kind of plan and answers the opposite question: it is a sequence
 * of timed steps ("three minutes on, one minute off"), so the speed at a moment is read off
 * the clock. This one is "the first kilometre at 5:00, the second at 5:15", so the speed at a
 * place is read off the distance travelled. Both are needed because both are real ways to
 * prescribe a run, and converting one into the other is not honest: a timed plan drifts off
 * the kilometre marks as soon as fatigue slows the subject, and a distance plan cannot say
 * what happens at minute nine.
 *
 * The curve is piecewise LINEAR in speed against distance, and that single mechanism covers
 * the three levels a session is described at. An overall target shows up as the mean of the
 * curve; kilometre targets are breakpoints at 0, 1000, 2000, ...; finer adjustments are extra
 * breakpoints between them. Nothing else is needed, and a list that can express all three
 * cannot disagree with itself the way three parallel models would.
 *
 * Distance and time are both required, so the class carries the exact integral and its
 * inverse rather than making every caller re-derive them. That matters because two layers
 * read this: the route engine integrates it, and the straight-line engine asks it directly
 * for "how far at time t". If the two did the arithmetic separately they would eventually
 * disagree, which is the same fault this codebase already fixed once for the route clock.
 */
data class PacePlan(
    /** Breakpoints in increasing distance order; the first must sit at 0 metres. */
    val points: List<Point>,
) {

    /**
     * One breakpoint of the curve.
     *
     * @param distanceMeters distance from the start; 0 for the first point
     * @param speedMps the speed the session aims for on arriving here
     */
    data class Point(val distanceMeters: Double, val speedMps: Double)

    init {
        require(points.size >= 2) { "a pace plan needs at least two points" }
        require(points.first().distanceMeters == 0.0) { "the first point must sit at 0 m" }
        require(points.all { it.speedMps > 0.0 }) { "every point must have a positive speed" }
        for (i in 1 until points.size) {
            require(points[i].distanceMeters > points[i - 1].distanceMeters) {
                "pace plan distances must increase"
            }
        }
    }

    /** The distance the curve describes; beyond it the last speed is held. */
    val distanceMeters: Double get() = points.last().distanceMeters

    /**
     * Seconds needed to reach the end of the curve.
     *
     * Null when the curve repeats, which it never does; kept as a property so a caller that
     * only knows it has a plan does not have to branch on the type.
     */
    val totalSeconds: Double get() = secondsTo(distanceMeters)

    /**
     * The speed aimed for at [distanceMeters].
     *
     * Before the first point (which is at 0, so only a negative argument can get there) the
     * first speed answers; past the end the last one is held, matching how [SpeedPlan] keeps
     * its final step so a route longer than the session does not stop dead.
     */
    fun speedAtDistance(distanceMeters: Double): Double {
        if (distanceMeters <= 0.0) return points.first().speedMps
        if (distanceMeters >= this.distanceMeters) return points.last().speedMps
        val i = segmentIndexAt(distanceMeters)
        return speedWithin(i, distanceMeters)
    }

    /**
     * Seconds needed to cover [distanceMeters], the exact integral of 1 / speed.
     *
     * Within one segment the speed is linear in distance, so the time is a logarithm
     * rather than a quadrature. A numeric step here would be close enough for a track and
     * not close enough for two engines that must agree to the metre.
     */
    fun secondsTo(distanceMeters: Double): Double {
        if (distanceMeters <= 0.0) return 0.0
        var remaining = distanceMeters
        var seconds = 0.0
        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            val span = to.distanceMeters - from.distanceMeters
            val use = if (remaining < span) remaining else span
            seconds += segmentSeconds(from.speedMps, to.speedMps, span, use)
            remaining -= use
            if (remaining <= 0.0) return seconds
        }
        // Past the end the last speed is held, so the remainder is a straight line.
        return seconds + remaining / points.last().speedMps
    }

    /**
     * Distance covered after [elapsedSec], the exact inverse of [secondsTo].
     *
     * Walks whole segments and then inverts the logarithm inside the one the moment lands
     * in, so this is closed form rather than a search.
     */
    fun metersAt(elapsedSec: Double): Double {
        if (elapsedSec <= 0.0) return 0.0
        var left = elapsedSec
        for (i in 0 until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            val span = to.distanceMeters - from.distanceMeters
            val segment = segmentSeconds(from.speedMps, to.speedMps, span, span)
            if (left < segment) {
                return from.distanceMeters +
                    distanceWithin(from.speedMps, to.speedMps, span, left)
            }
            left -= segment
        }
        return distanceMeters + left * points.last().speedMps
    }

    /** Average speed over the whole curve, which is what an overall target compares against. */
    fun averageSpeed(): Double = if (totalSeconds <= 0.0) {
        points.first().speedMps
    } else {
        distanceMeters / totalSeconds
    }

    /** The canonical text form: `pace:0=3.00;1000=2.80;2000=2.60`. */
    fun format(): String = WIRE_PREFIX + points.joinToString(";") {
        "${trim(it.distanceMeters)}=${trim(it.speedMps)}"
    }

    private fun segmentIndexAt(distanceMeters: Double): Int {
        // Linear scan: a plan has a handful of points, and a binary search here would be
        // more code than the list is worth.
        for (i in 0 until points.size - 1) {
            if (distanceMeters < points[i + 1].distanceMeters) return i
        }
        return points.size - 2
    }

    private fun speedWithin(index: Int, distanceMeters: Double): Double {
        val from = points[index]
        val to = points[index + 1]
        val span = to.distanceMeters - from.distanceMeters
        if (span <= 0.0) return to.speedMps
        val k = (distanceMeters - from.distanceMeters) / span
        return from.speedMps + (to.speedMps - from.speedMps) * k
    }

    companion object {

        /** Marks the text form so it cannot be mistaken for a [SpeedPlan]. */
        const val WIRE_PREFIX = "pace:"

        /**
         * Width of the speed change at each kilometre mark, in metres.
         *
         * Short enough that a split still averages its own pace, long enough that the change
         * does not look like a step.
         */
        const val BLEND_METERS = 100.0

        /**
         * Reads `pace:0=3.00;1000=2.80`. Null for anything malformed, so a bad field shows
         * up as "no plan" rather than as a plan with invented numbers.
         */
        fun parse(text: String): PacePlan? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(WIRE_PREFIX)) return null
            val body = trimmed.removePrefix(WIRE_PREFIX)
            val points = ArrayList<Point>()
            for (piece in body.split(';')) {
                val item = piece.trim()
                if (item.isEmpty()) continue
                val parts = item.split('=', limit = 2)
                if (parts.size != 2) return null
                val distance = parts[0].trim().toDoubleOrNull() ?: return null
                val speed = parts[1].trim().toDoubleOrNull() ?: return null
                if (distance < 0.0 || speed <= 0.0) return null
                points.add(Point(distance, speed))
            }
            return try {
                PacePlan(points)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Builds a curve from one target per kilometre.
         *
         * The breakpoints are NOT placed on the kilometre marks. A kilometre has to average
         * exactly the pace the user typed, and a curve that runs straight from one mark's
         * speed to the next spends the whole kilometre in between, so it finishes about five
         * seconds slow on a 300 s split. Instead each kilometre holds its own speed and the
         * change is squeezed into a short blend across the mark, which keeps every split on
         * its target (a 100 m blend leaves the error under a fifth of a second) and still
         * looks like a person easing into a new effort rather than a step change.
         *
         * A final partial kilometre ends the curve where the route does, at the last split's
         * pace; the route engine holds the final speed past the end anyway.
         */
        fun fromKilometreSplits(splits: List<Double>, lastPartialMeters: Double? = null): PacePlan {
            val points = ArrayList<Point>()
            val blend = BLEND_METERS / 2.0
            points.add(Point(0.0, 1000.0 / splits.first()))
            for (i in 1 until splits.size) {
                val mark = i * 1000.0
                points.add(Point(mark - blend, 1000.0 / splits[i - 1]))
                points.add(Point(mark + blend, 1000.0 / splits[i]))
            }
            points.add(Point(splits.size * 1000.0, 1000.0 / splits.last()))

            val partial = lastPartialMeters
            if (partial != null && partial > 0.0 && partial < 1000.0) {
                val end = (splits.size - 1) * 1000.0 + partial
                // Drop any breakpoint the shorter finish would leave past the end, so the
                // last point really is the end of the curve.
                while (points.size > 1 && points.last().distanceMeters > end + 1.0) {
                    points.removeAt(points.size - 1)
                }
                points.add(Point(end, 1000.0 / splits.last()))
            }
            return PacePlan(points.distinctBy { it.distanceMeters })
        }

        /** Time the plan takes in seconds, for the totals line. */
        private fun trim(value: Double): String {
            val rounded = kotlin.math.round(value * 100.0) / 100.0
            return if (rounded == rounded.toLong().toDouble()) {
                rounded.toLong().toString()
            } else {
                rounded.toString()
            }
        }

        /**
         * Seconds to cross [use] metres of a segment whose speed runs from [v1] to [v2] over
         * [span] metres in total.
         *
         * Constant speed is the linear case; otherwise the speed at distance s is
         * `v1 + (v2 - v1) * s / span`, and the integral of 1/v is a logarithm.
         */
        private fun segmentSeconds(v1: Double, v2: Double, span: Double, use: Double): Double {
            if (use <= 0.0) return 0.0
            if (abs(v2 - v1) < 1e-9) return use / v1
            val slope = (v2 - v1) / span
            val vEnd = v1 + slope * use
            // Guarded: a segment that decelerates towards zero would take infinite time, and
            // the constructor already rejects a zero speed, so this only protects rounding.
            if (vEnd <= 1e-9) return use / v1
            return ln(vEnd / v1) / slope
        }

        /** Distance covered in [seconds] inside a segment, the inverse of [segmentSeconds]. */
        private fun distanceWithin(v1: Double, v2: Double, span: Double, seconds: Double): Double {
            if (seconds <= 0.0) return 0.0
            if (abs(v2 - v1) < 1e-9) return (v1 * seconds).coerceIn(0.0, span)
            val slope = (v2 - v1) / span
            val vEnd = v1 * exp(slope * seconds)
            return ((vEnd - v1) / slope).coerceIn(0.0, span)
        }
    }
}
