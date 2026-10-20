package org.pacer.motion

/**
 * The mutable step list a plan is edited through.
 *
 * [SpeedPlan] is the immutable, validated form the engine reads; `parse`/`format` expose it
 * as `3.0@180;1.4@60#5`. That is the right storage form and the wrong input form, so this
 * holds the same information as separate fields and converts at the boundary.
 *
 * It accepts an incomplete plan on purpose - a half-typed step, no steps at all. [toPlan]
 * answers null until the draft would pass [SpeedPlan]'s own rules.
 */
class PlanDraft(
    steps: List<SpeedPlan.Step> = listOf(DEFAULT_STEP),
    repeats: Int = 1,
) {

    val steps: MutableList<SpeedPlan.Step> = ArrayList(steps)

    /** Passes through the steps; 0 means repeat forever. */
    var repeats: Int = repeats.coerceAtLeast(0)
        set(value) {
            field = value.coerceIn(0, MAX_REPEATS)
        }

    fun add(index: Int = steps.size) {
        val template = steps.lastOrNull() ?: DEFAULT_STEP
        steps.add(index.coerceIn(0, steps.size), template)
    }

    fun remove(index: Int) {
        if (index in steps.indices) steps.removeAt(index)
    }

    /** Moves a step one place earlier or later; false at the ends. */
    fun move(index: Int, delta: Int): Boolean {
        val target = index + delta
        if (index !in steps.indices || target !in steps.indices) return false
        val step = steps.removeAt(index)
        steps.add(target, step)
        return true
    }

    fun setSpeed(index: Int, speedMps: Double) {
        if (index !in steps.indices) return
        steps[index] = steps[index].copy(speedMps = speedMps.coerceIn(0.0, MAX_SPEED_MPS))
    }

    fun setDuration(index: Int, seconds: Double) {
        if (index !in steps.indices) return
        steps[index] = steps[index].copy(durationSec = seconds.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC))
    }

    /** The validated plan, or null while the draft would not run. */
    fun toPlan(): SpeedPlan? = try {
        SpeedPlan(steps.toList(), repeats)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The storage form, or the last valid text when the draft is mid-edit. */
    fun toWireFormat(): String = toPlan()?.format() ?: ""

    /**
     * Loads [wire] into this draft.
     *
     * A blank or unreadable string leaves the draft untouched and returns false, so
     * opening the editor on a run with no plan does not silently create one.
     */
    fun load(wire: String): Boolean {
        val parsed = SpeedPlan.parse(wire) ?: return false
        steps.clear()
        steps.addAll(parsed.steps)
        repeats = parsed.repeats
        return true
    }

    /** One pass through the steps, in seconds. */
    val passSeconds: Double get() = steps.sumOf { it.durationSec }

    /** One pass through the steps, in metres. */
    val passMeters: Double get() = steps.sumOf { it.speedMps * it.durationSec }

    /** Planned seconds, or null when the plan repeats forever. */
    val totalSeconds: Double? get() = if (repeats > 0) passSeconds * repeats else null

    /** Planned metres, or null when the plan repeats forever. */
    val totalMeters: Double? get() = if (repeats > 0) passMeters * repeats else null

    companion object {
        val DEFAULT_STEP = SpeedPlan.Step(speedMps = 3.0, durationSec = 180.0)

        const val MAX_REPEATS = 999
        const val MIN_DURATION_SEC = 1.0
        const val MAX_DURATION_SEC = 36_000.0
        const val MAX_SPEED_MPS = 30.0

        /** Presets offered as buttons, so a plan can be built without typing at all. */
        val PRESETS: List<Preset> = listOf(
            Preset("Walk", listOf(SpeedPlan.Step(1.4, 1800.0)), repeats = 0),
            Preset("Run", listOf(SpeedPlan.Step(3.0, 1800.0)), repeats = 0),
            Preset("Ride", listOf(SpeedPlan.Step(6.0, 1800.0)), repeats = 0),
            Preset(
                "Run/walk intervals",
                listOf(SpeedPlan.Step(3.0, 180.0), SpeedPlan.Step(1.4, 60.0)),
                repeats = 5,
            ),
            Preset(
                "Sprints",
                listOf(SpeedPlan.Step(6.0, 30.0), SpeedPlan.Step(1.4, 90.0)),
                repeats = 8,
            ),
        )
    }

    /** A named starting point for the editor. */
    data class Preset(val name: String, val steps: List<SpeedPlan.Step>, val repeats: Int) {
        fun intoDraft(): PlanDraft = PlanDraft(steps, repeats)
    }
}
