package org.pacer.motion

import kotlin.math.floor
import kotlin.math.min

/**
 * A time-based interval plan: alternating effort levels, optionally repeated.
 *
 * TIME, not distance, is the unit. That is how intervals are prescribed ("three
 * minutes on, one minute off"), and it is also what keeps the simulated position a
 * pure function of the clock: the distance covered by a moment is a sum of
 * speed x duration, so nothing has to be accumulated to know where the subject is.
 * A distance-based plan would make the position depend on its own integral, which
 * is both harder to express and impossible to read off a clock.
 *
 * After the plan's last pass the final step's speed continues, so a route longer
 * than the session does not suddenly stop.
 */
data class SpeedPlan(
    val steps: List<Step>,
    /** Passes through [steps]; 0 means "repeat forever". */
    val repeats: Int,
) {

    data class Step(val speedMps: Double, val durationSec: Double) {
        init {
            require(speedMps >= 0.0) { "step speed must not be negative" }
            require(durationSec > 0.0) { "step duration must be positive" }
        }
    }

    init {
        require(steps.isNotEmpty()) { "a plan needs at least one step" }
        require(repeats >= 0) { "repeats must be 0 (forever) or positive" }
    }

    /** Seconds of one pass through [steps]. */
    val passSeconds: Double = steps.sumOf { it.durationSec }

    /** Metres of one pass through [steps]. */
    val passMeters: Double = steps.sumOf { it.speedMps * it.durationSec }

    /** Planned seconds, or null when the plan repeats indefinitely. */
    val totalSeconds: Double? = if (repeats > 0) passSeconds * repeats else null

    /** Planned metres, or null when the plan repeats indefinitely. */
    val totalMeters: Double? = if (repeats > 0) passMeters * repeats else null

    /** Speed at [elapsedSec]. Before the start it is the first step's speed. */
    fun speedAt(elapsedSec: Double): Double {
        if (elapsedSec <= 0.0) return steps.first().speedMps
        val total = totalSeconds
        if (total != null && elapsedSec >= total) return steps.last().speedMps
        var left = if (total == null) elapsedSec % passSeconds else elapsedSec
        for (step in steps) {
            if (left < step.durationSec) return step.speedMps
            left -= step.durationSec
        }
        return steps.last().speedMps
    }

    /** Distance covered by [elapsedSec], the exact integral of [speedAt]. */
    fun metersAt(elapsedSec: Double): Double {
        if (elapsedSec <= 0.0) return 0.0
        val total = totalSeconds
        if (total != null && elapsedSec >= total) {
            return passMeters * repeats + steps.last().speedMps * (elapsedSec - total)
        }
        val completed = floor(elapsedSec / passSeconds).toInt()
        var within = elapsedSec - completed * passSeconds
        var meters = passMeters * completed
        for (step in steps) {
            if (within <= 0.0) break
            val use = min(within, step.durationSec)
            meters += step.speedMps * use
            within -= use
        }
        return meters
    }

    /** The canonical text form, which is also what a user types. */
    fun format(): String {
        val body = steps.joinToString(";") { "${it.speedMps}@${it.durationSec}" }
        return if (repeats == 1) body else "$body#$repeats"
    }

    companion object {

        /**
         * Reads `3.0@180;1.4@60#5`: speed in m/s, `@`, seconds, steps separated by
         * `;`, and an optional `#repeats` where 0 means "repeat forever". Null for
         * anything malformed, so a bad field shows up as "no plan" instead of a plan
         * with invented numbers.
         */
        fun parse(text: String): SpeedPlan? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val halves = trimmed.split('#', limit = 2)
            val repeats = if (halves.size == 2) {
                halves[1].trim().toIntOrNull() ?: return null
            } else {
                1
            }
            if (repeats < 0) return null
            val steps = ArrayList<Step>()
            for (piece in halves[0].split(';')) {
                val item = piece.trim()
                if (item.isEmpty()) continue
                val parts = item.split('@', limit = 2)
                if (parts.size != 2) return null
                val speed = parts[0].trim().toDoubleOrNull() ?: return null
                val duration = parts[1].trim().toDoubleOrNull() ?: return null
                if (speed < 0.0 || duration <= 0.0) return null
                steps.add(Step(speed, duration))
            }
            if (steps.isEmpty()) return null
            return SpeedPlan(steps, repeats)
        }
    }
}
