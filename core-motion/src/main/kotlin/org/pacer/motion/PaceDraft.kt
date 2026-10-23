package org.pacer.motion

/**
 * The mutable form of a [PacePlan], the way [PlanDraft] is the mutable form of [SpeedPlan].
 *
 * The editor works in the runner's units: "what pace for the first kilometre", not "what
 * speed at 1200 metres". So the rows are kilometre splits, and this class owns the
 * conversion to breakpoints. Keeping that conversion in one place matters because the
 * engine's curve is linear in speed between breakpoints, while a person thinks in minutes
 * per kilometre - the two only agree at the marks.
 *
 * An empty split list means "no pace plan", which is a valid state (the run falls back to a
 * steady speed). A list that exists must have a positive pace everywhere.
 */
class PaceDraft(
    /** Seconds per kilometre, one per kilometre, in order. */
    splits: List<Double> = DEFAULT_SPLITS,
) {

    val splits: MutableList<Double> = ArrayList(splits)

    /**
     * The speed a given split prescribes, in m/s.
     *
     * Pace is seconds per kilometre; speed is metres per second, so the conversion is
     * `1000 / pace`. The two live side by side on purpose: the field is edited in pace
     * because that is what a runner knows, and the engine consumes speed.
     */
    fun speedOf(index: Int): Double {
        if (index !in splits.indices) return 0.0
        return 1000.0 / splits[index].coerceAtLeast(MIN_SPLIT_SEC)
    }

    fun setSplit(index: Int, secondsPerKm: Double) {
        if (index !in splits.indices) return
        splits[index] = secondsPerKm.coerceIn(MIN_SPLIT_SEC, MAX_SPLIT_SEC)
    }

    fun add() {
        // Copy the last split rather than inventing a number: a runner adding a kilometre
        // usually means "and keep going like that".
        splits.add(splits.lastOrNull() ?: DEFAULT_SPLITS.last())
    }

    fun remove(index: Int) {
        // One split is still a plan (a flat kilometre pace); zero is "no plan".
        if (splits.size > 1 && index in splits.indices) splits.removeAt(index)
    }

    /** The validated curve, or null while the draft would not run. */
    fun toPlan(): PacePlan? = try {
        PacePlan.fromKilometreSplits(splits.toList())
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Total metres the curve describes. */
    val distanceMeters: Double get() = splits.size * 1000.0

    /** Seconds the whole curve takes. */
    val totalSeconds: Double get() = splits.sum()

    /** Mean speed over the curve, which is what the overall target is checked against. */
    val averageSpeed: Double get() = if (totalSeconds <= 0.0) 0.0 else distanceMeters / totalSeconds

    /** Mean pace in seconds per kilometre. */
    val averageSplit: Double get() = if (splits.isEmpty()) 0.0 else totalSeconds / splits.size

    /** The storage form, empty when the draft would not run. */
    fun toWireFormat(): String = toPlan()?.format() ?: ""

    /** Loads [wire]; false when it is not a pace plan, leaving the draft untouched. */
    fun load(wire: String): Boolean {
        val parsed = PacePlan.parse(wire) ?: return false
        val loaded = splitsFromPlan(parsed) ?: return false
        splits.clear()
        splits.addAll(loaded)
        return true
    }

    companion object {

        /** 5:00 / 5:10 / 5:20 per kilometre: a three-kilometre run that fades. */
        val DEFAULT_SPLITS = listOf(300.0, 310.0, 320.0)

        /**
         * Starting points for the curve, shaped like [PlanDraft.PRESETS].
         *
         * A flat preset is deliberate here even though the step presets refuse one: there a
         * speed radio already states a steady speed, while here the same intention is a number
         * in another unit that no radio can show, and the default curve fades.
         */
        val PRESETS: List<Preset> = listOf(
            Preset("pace-even", "Even", listOf(300.0, 300.0, 300.0)),
            Preset("pace-negative", "Negative split", listOf(310.0, 300.0, 290.0)),
            Preset("pace-positive", "Positive split", listOf(290.0, 300.0, 310.0)),
            Preset("pace-5k", "5 km", listOf(300.0, 300.0, 300.0, 300.0, 300.0)),
        )

        /** Slower than this is walking pace; the field is for running sessions. */
        const val MIN_SPLIT_SEC = 120.0

        /** Very slow indeed, but a limit is needed so the speed cannot reach zero. */
        const val MAX_SPLIT_SEC = 3600.0

        /** One press of a pace stepper. Five seconds per kilometre is a normal increment. */
        const val SPLIT_STEP_SEC = 5.0

        /**
         * Recovers per-kilometre splits from a stored curve.
         *
         * The curve holds its speed for most of each kilometre and blends across the mark, so
         * reading the speed at the middle of a kilometre is the honest way back to the pace
         * that was typed. A curve that was not built from kilometre splits (arbitrary
         * breakpoints) still yields the pace at each kilometre's midpoint, which is what the
         * editor can represent; anything it cannot show is refused rather than rewritten.
         */
        fun splitsFromPlan(plan: PacePlan): List<Double>? {
            val kilometres = kotlin.math.round(plan.distanceMeters / 1000.0).toInt()
            if (kilometres <= 0) return null
            // Only real kilometre plans come back: the end has to be on a mark.
            if (kotlin.math.abs(plan.distanceMeters - kilometres * 1000.0) > 0.5) return null
            val out = ArrayList<Double>(kilometres)
            for (i in 0 until kilometres) {
                val mid = (i + 0.5) * 1000.0
                out.add(1000.0 / plan.speedAtDistance(mid))
            }
            return out
        }
    }

    /**
     * A named starting point for the curve.
     *
     * [id] is a stable key rather than the label, so the translated button text cannot be
     * mistaken for the preset itself. [splits] are seconds per kilometre, one per kilometre.
     */
    data class Preset(
        val id: String,
        val name: String,
        val splits: List<Double>,
    ) {
        fun intoDraft(): PaceDraft = PaceDraft(splits)
    }
}
