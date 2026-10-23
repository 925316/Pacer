package org.pacer.app

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.pacer.motion.PaceDraft
import org.pacer.motion.PacePlan
import org.pacer.motion.PlanDraft
import java.util.Locale

/**
 * The interval plan as steps with steppers, not as `3.0@180;1.4@60#5`.
 *
 * Folded behind one row. A plan is chosen once per session, but its steps are the tallest
 * block on the page and pushed everything else off the screen; the folded row says what will
 * run, and opening it is one tap. The stored value is still the wire string, handed to
 * [onChange].
 */
class PlanEditor(
    private val activity: Activity,
    private val strings: Strings,
    private val onChange: (String) -> Unit,
) {

    /** Both drafts and the choice between them; the one-speed-source rule lives there. */
    private val state = PlanSourceState()

    private val draft: PlanDraft get() = state.draft

    /**
     * The per-kilometre curve, which is the other shape this editor can express.
     *
     * Two shapes in one editor on purpose: they are two ways to answer the same question
     * ("what speed, when"), a run may use only one of them, and giving each its own screen
     * would mean two entry points for one decision.
     */
    private val paceDraft: PaceDraft get() = state.pace

    private val paceEnabled: Boolean get() = state.paceEnabled

    private val paceColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val paceTotals = TextView(activity).apply { textSize = 13f }
    private val paceToggle = Button(activity).apply { textSize = 13f; isAllCaps = false }

    /** The curve's presets, shown with the curve and filled in [buildBody]. */
    private val pacePresets = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    /** Rebuilt whole on every change: there are at most a handful of steps. */
    private val stepsColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private val repeatRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private val totals = TextView(activity).apply { textSize = 13f }

    private val summary = TextView(activity).apply {
        textSize = 13f
        gravity = Gravity.END
    }

    /** The chevron in front of the summary: "v" folded, "^" open. */
    private val chevron = TextView(activity).apply {
        textSize = 16f
        setPadding(0, 0, dp(4), 0)
    }

    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    /**
     * The folded header: a real, tappable row with a chevron.
     *
     * A bold title and a right-aligned summary alone would read as a section label over the
     * page rather than as the control that opens the plan - so the whole editor behind it
     * would be easy to miss.
     */
    private val headerRow: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        isClickable = true
        isFocusable = true
        addView(chevron)
        addView(
            TextView(activity).apply {
                text = strings.intervalPlanSection
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(summary)
    }

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(headerRow)
        addView(body)
    }

    /** False while the draft would not run; the screen shows that instead of starting. */
    var isValid: Boolean = true
        private set

    private var expanded = false

    init {
        // Built here, not only from load(): the editor has a valid state of its own (no
        // steps, steady speed), and waiting for a stored plan left this block blank whenever
        // the config channel was not bound - which is most of the time, since it binds only
        // under the module.
        headerRow.setOnClickListener { toggle() }
        buildBody()
        refresh()
        setShow(false)
    }

    /** Static parts once; the dynamic ones are the only things a change has to redraw. */
    private fun buildBody() {
        body.addView(stepsColumn)
        body.addView(
            Button(activity).apply {
                text = strings.planAddStep
                textSize = 13f
                isAllCaps = false
                setOnClickListener {
                    // Adding a step also makes the steps the shape that runs: a step the
                    // curve would displace is a button that appears to do nothing.
                    state.addStep()
                    refresh()
                }
            },
        )

        // The pace curve sits beside the steps, not in another screen: both answer "what
        // speed when", a run uses at most one of them, and two screens would be two entry
        // points for one decision.
        body.addView(
            TextView(activity).apply {
                text = strings.paceSection
                textSize = 15f
                setPadding(0, dp(8), 0, 0)
            },
        )
        body.addView(
            TextView(activity).apply {
                text = strings.paceHint
                textSize = 12f
                setPadding(0, dp(1), 0, dp(2))
            },
        )
        paceToggle.textSize = 13f
        paceToggle.isAllCaps = false
        paceToggle.setOnClickListener {
            if (!paceEnabled) {
                // Turning the curve on has to displace the steps, because a run has one
                // speed source. Refusing instead would leave the user with a button that
                // does nothing and no explanation.
                state.usePace()
            } else {
                state.stopPace()
            }
            refresh()
        }
        body.addView(paceToggle)
        // Above the rows, not below: a five-kilometre preset would otherwise push its own
        // grid off the screen it just grew.
        pacePresets.addView(
            TextView(activity).apply {
                text = strings.pacePresets
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            },
        )
        pacePresets.addView(
            presetGrid(
                PaceDraft.PRESETS.map { preset ->
                    val caption = strings.pacePresetNames[preset.id] ?: preset.name
                    val action: () -> Unit = { applyPacePreset(preset) }
                    caption to action
                },
            ),
        )
        body.addView(pacePresets)
        body.addView(paceColumn)
        body.addView(paceTotals)

        body.addView(
            TextView(activity).apply {
                text = strings.planPresets
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            },
        )
        body.addView(
            presetGrid(
                PlanDraft.PRESETS.map { preset ->
                    val caption = strings.planPresetNames[preset.id] ?: preset.name
                    val action: () -> Unit = { applyPreset(preset) }
                    caption to action
                },
            ),
        )

        repeatRow.addView(
            TextView(activity).apply {
                text = strings.planRepeats
                textSize = 14f
            },
        )
        repeatRow.addView(
            activity.smallAction("−") {
                draft.repeats -= 1
                refresh()
            },
        )
        repeatRow.addView(
            TextView(activity).apply {
                text = "1"
                textSize = 14f
                minWidth = dp(32)
                gravity = Gravity.CENTER
                tag = REPEAT_LABEL
            },
        )
        repeatRow.addView(
            activity.smallAction("+") {
                draft.repeats += 1
                refresh()
            },
        )
        body.addView(repeatRow)
        body.addView(totals)
    }

    /** Opens the editor, so a start that needs a plan can show what is wrong with it. */
    fun open() = setShow(true)

    /**
     * Loads a stored plan; a blank or unreadable string leaves the editor as it is.
     *
     * The prefix decides which shape is stored, and [PlanSourceState.load] clears the other
     * draft so a stored curve cannot keep a stale step plan behind it.
     */
    fun load(wire: String) {
        state.load(wire)
        refresh()
    }

    /** Adopts a step preset as the shape that runs, not merely as rows on screen. */
    fun applyPreset(preset: PlanDraft.Preset) {
        state.adoptSteps(preset)
        refresh()
    }

    /** Adopts a pace preset, which also makes the curve the shape that runs. */
    private fun applyPacePreset(preset: PaceDraft.Preset) {
        state.adoptPace(preset)
        refresh()
    }

    private fun toggle() = setShow(!expanded)

    private fun setShow(show: Boolean) {
        expanded = show
        body.visibility = if (show) View.VISIBLE else View.GONE
        summary.text = headerSummary()
        // The chevron is the only thing on the row that says it can be opened without
        // reading the words, and it says which way it will go.
        chevron.text = if (show) "\u25be" else "\u25b8"
        headerRow.contentDescription = if (show) strings.planCollapse else strings.planExpand
    }

    /** The folded label: what will run, without opening anything. */
    private fun headerSummary(): String {
        if (expanded) return ""
        if (!isValid) return strings.planInvalidShort
        return when (state.source) {
            PlanSource.PACE -> String.format(
                Locale.US,
                strings.paceSummary,
                paceDraft.splits.size,
                formatPace(paceDraft.averageSplit),
            )
            PlanSource.STEADY -> strings.planNone
            PlanSource.STEPS -> String.format(
                Locale.US,
                strings.planSummary,
                draft.steps.size,
                // Repeats 0 means "for the whole route", and "x0" would read as a mistake.
                if (draft.repeats == 0) strings.planForever else draft.repeats.toString(),
            )
        }
    }

    private fun rebuild() {
        stepsColumn.removeAllViews()
        if (draft.steps.isEmpty()) {
            stepsColumn.addView(
                TextView(activity).apply {
                    text = strings.planNoSteps
                    textSize = 13f
                },
            )
        } else {
            // Column headings belong to the rows, so they appear only when there are rows.
            // A heading over "no steps yet" labels a table that is not there.
            stepsColumn.addView(columnHeadings())
        }
        draft.steps.forEachIndexed { index, step ->
            stepsColumn.addView(stepRow(index, step.speedMps, step.durationSec))
        }
        paceToggle.text = if (paceEnabled) strings.paceRemoveCurve else strings.paceEnableCurve
        rebuildPace()
        repeatRow.findViewWithTag<TextView>(REPEAT_LABEL)?.text =
            if (draft.repeats == 0) strings.planForever else draft.repeats.toString()
        // Repeats and totals only describe a plan that exists. With no steps they would read
        // "1 x 0 s / 0 m", which looks like a plan that runs nowhere rather than no plan.
        val hasPlan = draft.steps.isNotEmpty()
        repeatRow.visibility = if (hasPlan) View.VISIBLE else View.GONE
        totals.text = buildString {
            if (hasPlan) append(totalsText())
            if (!isValid) {
                if (hasPlan) append('\n')
                append(strings.planInvalid)
            }
        }
        totals.visibility = if (hasPlan || !isValid) View.VISIBLE else View.GONE
    }

    /**
     * The two column headings, sized to sit over the steppers they label.
     *
     * The numbers carry no unit of their own any more (that was what made the row too wide),
     * so the headings are the only thing saying which column is speed and which is seconds.
     */
    private fun columnHeadings(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(2))
        addView(label("", dp(16)))
        addView(label(strings.planSpeed, dp(124), 11f))
        addView(label(strings.planDuration, dp(118), 11f))
    }

    /** The per-kilometre rows, one line each: mark, pace, and the two steppers. */
    private fun rebuildPace() {
        paceColumn.removeAllViews()
        paceColumn.visibility = if (paceEnabled) View.VISIBLE else View.GONE
        pacePresets.visibility = if (paceEnabled) View.VISIBLE else View.GONE
        paceTotals.visibility = if (paceEnabled) View.VISIBLE else View.GONE
        if (!paceEnabled) return

        paceDraft.splits.forEachIndexed { index, seconds ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(activity).apply {
                    text = String.format(Locale.US, strings.paceKilometre, index + 1)
                    textSize = 13f
                    // dp, not px: a pixel width is 44 dp on a 3x screen and clips the
                    // Chinese label to "km 3".
                    minWidth = dp(96)
                },
            )
            row.addView(
                stepper("−", strings.paceSection) {
                    paceDraft.setSplit(index, seconds - PaceDraft.SPLIT_STEP_SEC)
                    refresh()
                },
            )
            row.addView(
                TextView(activity).apply {
                    text = formatPace(seconds)
                    textSize = 13f
                    minWidth = dp(56)
                    gravity = Gravity.CENTER
                },
            )
            row.addView(
                stepper("+", strings.paceSection) {
                    paceDraft.setSplit(index, seconds + PaceDraft.SPLIT_STEP_SEC)
                    refresh()
                },
            )
            if (paceDraft.splits.size > 1) {
                row.addView(
                    activity.smallAction("×") {
                        paceDraft.remove(index)
                        refresh()
                    }.apply { contentDescription = strings.paceRemove },
                )
            }
            paceColumn.addView(row)
        }
        paceColumn.addView(
            Button(activity).apply {
                text = strings.paceAddKilometre
                textSize = 13f
                isAllCaps = false
                setOnClickListener {
                    paceDraft.add()
                    refresh()
                }
            },
        )
        paceTotals.text = if (paceDraft.toPlan() == null) {
            strings.paceInvalid
        } else {
            String.format(
                Locale.US,
                strings.paceTotals,
                paceDraft.totalSeconds,
                paceDraft.distanceMeters / 1000.0,
                formatPace(paceDraft.averageSplit),
            )
        }
    }

    /** `5:00` for 300 seconds; a runner reads a split as minutes and seconds. */
    private fun formatPace(seconds: Double): String {
        val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /**
     * One step on one line: index, then speed and duration each as − value +, then reorder
     * and remove.
     *
     * The two stacked lines this replaces made five steps taller than the screen. A long row
     * is affordable now because the whole block is folded away most of the time.
     */
    private fun stepRow(index: Int, speed: Double, duration: Double): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        // No unit text per row; the column headings above say what the two numbers are, so
        // nine controls fit on one line instead of eleven.
        row.addView(label(String.format(Locale.US, "%d", index + 1), dp(16)))
        row.addView(
            stepper("−", strings.planSpeed) {
                draft.setSpeed(index, speed - SPEED_STEP); refresh()
            },
        )
        row.addView(label(String.format(Locale.US, "%.1f", speed), dp(38)))
        row.addView(
            stepper("+", strings.planSpeed) {
                draft.setSpeed(index, speed + SPEED_STEP); refresh()
            },
        )
        row.addView(
            stepper("−", strings.planDuration) {
                draft.setDuration(index, duration - DURATION_STEP); refresh()
            },
        )
        row.addView(label(String.format(Locale.US, "%.0f", duration), dp(32)))
        row.addView(
            stepper("+", strings.planDuration) {
                draft.setDuration(index, duration + DURATION_STEP); refresh()
            },
        )
        row.addView(activity.smallAction("↑") { if (draft.move(index, -1)) refresh() })
        row.addView(activity.smallAction("↓") { if (draft.move(index, 1)) refresh() })
        row.addView(
            activity.smallAction("×") {
                draft.remove(index)
                refresh()
            }.apply { contentDescription = strings.planRemove },
        )
        return row
    }

    /** Density-independent size, so a layout tuned on one screen fits another. */
    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /**
     * One step of change, then redraw. Every mutation goes through here.
     *
     * Validity is computed before the views are drawn: the totals line reports it, so
     * deciding it afterwards would show the warning one edit behind.
     */
    private fun refresh() {
        // Validity, the send and the header all read the same derived source, so they cannot
        // disagree about which shape is in effect.
        isValid = state.isValid
        rebuild()
        onChange(state.toWireFormat())
        summary.text = headerSummary()
    }

    /** A stepper button with a screen-reader label. */
    private fun stepper(text: String, label: String, onClick: () -> Unit): Button =
        activity.smallAction(text) { onClick() }.apply {
            contentDescription = label
        }

    private fun label(text: String, width: Int, size: Float = 13f): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        minWidth = width
        gravity = Gravity.CENTER
    }

    /**
     * Presets in rows of three: one per line buried them under the page.
     *
     * [presets] is label-to-action rather than a list of presets so the same grid serves the
     * step presets and the curve presets, which are different types.
     */
    private fun presetGrid(presets: List<Pair<String, () -> Unit>>): LinearLayout {
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (group in presets.chunked(PRESETS_PER_ROW)) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for ((caption, action) in group) {
                row.addView(
                    Button(activity).apply {
                        text = caption
                        textSize = 12f
                        isAllCaps = false
                        minWidth = 0
                        minimumWidth = 0
                        setPadding(dp(2), 0, dp(2), 0)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                        setOnClickListener { action() }
                    },
                )
            }
            // A short final row still fills the width, so its buttons align with the others.
            while (row.childCount < PRESETS_PER_ROW) {
                row.addView(
                    View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    },
                )
            }
            grid.addView(row)
        }
        return grid
    }

    private fun totalsText(): String = formatPlanTotals(
        passSeconds = draft.passSeconds,
        passMeters = draft.passMeters,
        totalSeconds = draft.totalSeconds,
        totalMeters = draft.totalMeters,
        totals = strings.planTotals,
        totalsForever = strings.planTotalsForever,
    )

    private companion object {
        /** One pace level at walking speed. */
        const val SPEED_STEP = 0.1

        /** The smallest interval worth prescribing. */
        const val DURATION_STEP = 30.0

        const val PRESETS_PER_ROW = 3
        const val REPEAT_LABEL = "planRepeatsValue"
    }
}

/**
 * The two totals lines, separated from the view so they can be tested.
 *
 * The crash this replaces: `planTotals` advertises four numbers and the call supplied two,
 * so the footer threw on its first draw and took the activity with it. Formatting is the
 * whole job here, so it takes exactly the values the templates ask for - a mismatch is a
 * compile error, not a runtime abort.
 */
internal fun formatPlanTotals(
    passSeconds: Double,
    passMeters: Double,
    totalSeconds: Double?,
    totalMeters: Double?,
    totals: String,
    totalsForever: String,
): String {
    if (totalSeconds == null || totalMeters == null) {
        return String.format(Locale.US, totalsForever, passSeconds, passMeters)
    }
    return String.format(
        Locale.US,
        totals,
        passSeconds,
        passMeters,
        totalSeconds / 60.0,
        totalMeters / 1000.0,
    )
}

/** Which of the editor's speed sources a run would actually be configured with. */
internal enum class PlanSource {
    STEADY,
    STEPS,
    PACE,
    ;

    companion object {
        /**
         * The shape a run is configured with, from the two facts the editor holds.
         *
         * The precedence itself is not a policy worth having - it is what the old send path
         * applied on its own. [PlanSourceState] never lets the pair arise, so this only has to
         * give the screen, the send and the tests one answer.
         */
        fun of(paceEnabled: Boolean, stepCount: Int): PlanSource = when {
            paceEnabled -> PACE
            stepCount > 0 -> STEPS
            else -> STEADY
        }
    }
}

/**
 * The editor's two drafts and the flag that says which one runs, without an Android view.
 *
 * Both facts being true at once - the curve enabled and a non-empty step list - is the state
 * in which the rows on screen are not the plan being run. Every method that selects a shape
 * unselects the other, so that state cannot be produced.
 */
internal class PlanSourceState(
    val draft: PlanDraft = PlanDraft(steps = emptyList()),
    val pace: PaceDraft = PaceDraft(),
) {

    var paceEnabled: Boolean = false
        private set

    val source: PlanSource
        get() = PlanSource.of(paceEnabled, draft.steps.size)

    /** An absent plan is a valid choice; only a plan that exists and would not run is not. */
    val isValid: Boolean
        get() = when (source) {
            PlanSource.STEADY -> true
            PlanSource.STEPS -> draft.toPlan() != null
            PlanSource.PACE -> pace.toPlan() != null
        }

    /** The one wire string the run is configured from. */
    fun toWireFormat(): String = when (source) {
        PlanSource.STEADY -> ""
        PlanSource.STEPS -> draft.toWireFormat()
        PlanSource.PACE -> pace.toWireFormat()
    }

    /** Makes the curve the shape that runs, displacing the steps. */
    fun usePace() {
        draft.steps.clear()
        paceEnabled = true
    }

    /** Leaves the curve without choosing another shape: the run falls back to steady speed. */
    fun stopPace() {
        paceEnabled = false
    }

    /** Adding a step also selects the steps, or the curve would keep the run. */
    fun addStep() {
        paceEnabled = false
        draft.add()
    }

    /** Adopts [preset] as the step plan, which also makes it the shape that runs. */
    fun adoptSteps(preset: PlanDraft.Preset) {
        val next = preset.intoDraft()
        draft.steps.clear()
        draft.steps.addAll(next.steps)
        draft.repeats = next.repeats
        paceEnabled = false
    }

    /** Adopts [preset] as the curve, which also makes it the shape that runs. */
    fun adoptPace(preset: PaceDraft.Preset) {
        val next = preset.intoDraft()
        draft.steps.clear()
        pace.splits.clear()
        pace.splits.addAll(next.splits)
        paceEnabled = true
    }

    /**
     * Loads a stored wire string and selects the shape it describes; false when unreadable.
     *
     * The prefix decides which draft is read, because a curve and a step plan are different
     * kinds of value and reading the wrong one as "no plan" would downgrade the run to a
     * steady speed.
     */
    fun load(wire: String): Boolean {
        if (wire.isBlank()) return false
        if (wire.trim().startsWith(PacePlan.WIRE_PREFIX)) {
            if (!pace.load(wire)) return false
            draft.steps.clear()
            paceEnabled = true
            return true
        }
        if (!draft.load(wire)) return false
        paceEnabled = false
        return true
    }
}
