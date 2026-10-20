package org.pacer.app

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.pacer.motion.PlanDraft
import java.util.Locale

/**
 * The interval plan as steps with steppers, not as `3.0@180;1.4@60#5`.
 *
 * Every value is adjusted by a button, so a plan cannot be mistyped. The stored value is
 * still the wire string: [onChange] hands out [PlanDraft.toWireFormat].
 */
class PlanEditor(
    private val activity: Activity,
    private val strings: Strings,
    private val onChange: (String) -> Unit,
) {

    private val draft = PlanDraft()

    /** The rows, rebuilt whole on every change: there are at most a handful of steps. */
    private val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    /** Repeats and totals, separate from the rows so a rebuild does not lose them. */
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(rows)
        addView(footer)
    }

    /** False while the draft would not run; the screen shows that instead of starting. */
    var isValid: Boolean = true
        private set

    /** The wire format to store, or "" when there is no usable plan. */
    fun wireFormat(): String = draft.toWireFormat()

    /** Loads a stored plan; a blank or unreadable string leaves the editor as it is. */
    fun load(wire: String) {
        if (wire.isNotBlank()) draft.load(wire)
        refresh()
    }

    /** Adopts a preset, so a plan can be built without touching a stepper. */
    fun applyPreset(preset: PlanDraft.Preset) {
        val next = preset.intoDraft()
        draft.steps.clear()
        draft.steps.addAll(next.steps)
        draft.repeats = next.repeats
        refresh()
    }

    private fun publish() {
        isValid = draft.toPlan() != null
        onChange(if (isValid) draft.toWireFormat() else "")
        renderFooter()
    }

    private fun rebuild() {
        rows.removeAllViews()
        if (draft.steps.isEmpty()) {
            rows.addView(
                TextView(activity).apply {
                    text = strings.planNoSteps
                    textSize = 13f
                },
            )
        }
        draft.steps.forEachIndexed { index, step ->
            rows.addView(stepRow(index, step.speedMps, step.durationSec))
        }
        rows.addView(
            Button(activity).apply {
                text = strings.planAddStep
                textSize = 13f
                setOnClickListener {
                    draft.add()
                    rebuild()
                    publish()
                }
            },
        )
        rows.addView(presetRow())
    }

    /** One step: − value + for speed and for duration, plus reorder and remove. */
    private fun stepRow(index: Int, speed: Double, duration: Double): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(
            TextView(activity).apply {
                text = String.format(Locale.US, "%d.", index + 1)
                textSize = 14f
                minWidth = 32
            },
        )
        top.addView(stepper("−", strings.planSpeed) { draft.setSpeed(index, speed - SPEED_STEP); refresh() })
        top.addView(valueLabel(String.format(Locale.US, "%.1f", speed), " m/s"))
        top.addView(
            stepper("+", strings.planSpeed) { draft.setSpeed(index, speed + SPEED_STEP); refresh() },
        )
        row.addView(top)

        val bottom = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(
            stepper("−", strings.planDuration) {
                draft.setDuration(index, duration - DURATION_STEP); refresh()
            },
        )
        bottom.addView(valueLabel(String.format(Locale.US, "%.0f", duration), " s"))
        bottom.addView(
            stepper("+", strings.planDuration) {
                draft.setDuration(index, duration + DURATION_STEP); refresh()
            },
        )
        bottom.addView(
            activity.smallAction("↑") {
                if (draft.move(index, -1)) refresh()
            },
        )
        bottom.addView(
            activity.smallAction("↓") {
                if (draft.move(index, 1)) refresh()
            },
        )
        bottom.addView(
            activity.smallAction(strings.planRemove) {
                draft.remove(index)
                refresh()
            },
        )
        row.addView(bottom)
        return row
    }

    /** One step of change, then redraw. Every mutation goes through here. */
    private fun refresh() {
        rebuild()
        publish()
    }

    /** A stepper button with a screen-reader label. */
    private fun stepper(text: String, label: String, onClick: () -> Unit): Button =
        activity.smallAction(text) { onClick() }.apply {
            contentDescription = label
        }

    private fun valueLabel(value: String, unit: String): TextView = TextView(activity).apply {
        text = "$value$unit"
        textSize = 14f
        minWidth = 96
    }

    private fun presetRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        row.addView(
            TextView(activity).apply {
                text = strings.planPresets
                textSize = 13f
            },
        )
        for (preset in PlanDraft.PRESETS) {
            row.addView(
                Button(activity).apply {
                    text = preset.name
                    textSize = 13f
                    setOnClickListener {
                        applyPreset(preset)
                    }
                },
            )
        }
        return row
    }

    /** Repeats, and what the plan adds up to, so a wrong number is visible before Start. */
    private fun renderFooter() {
        footer.removeAllViews()
        val repeatRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        repeatRow.addView(
            TextView(activity).apply {
                text = strings.planRepeats
                textSize = 14f
            },
        )
        repeatRow.addView(
            activity.smallAction("−") {
                draft.repeats -= 1
                publish()
            },
        )
        repeatRow.addView(
            TextView(activity).apply {
                text = if (draft.repeats == 0) strings.planForever else draft.repeats.toString()
                textSize = 14f
                minWidth = 72
            },
        )
        repeatRow.addView(
            activity.smallAction("+") {
                draft.repeats += 1
                publish()
            },
        )
        footer.addView(repeatRow)

        val totals = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 0)
        }
        totals.addView(
            TextView(activity).apply {
                text = totalsText()
                textSize = 13f
            },
        )
        footer.addView(totals)

        if (!isValid) {
            footer.addView(
                TextView(activity).apply {
                    text = strings.planInvalid
                    textSize = 13f
                },
            )
        }
    }

    private fun totalsText(): String {
        val seconds = draft.totalSeconds
        val meters = draft.totalMeters
        if (seconds == null || meters == null) {
            return String.format(
                Locale.US,
                strings.planTotalsForever,
                draft.passSeconds,
                draft.passMeters,
            )
        }
        return String.format(Locale.US, strings.planTotals, seconds, meters)
    }

    private companion object {
        /** One pace level at walking speed. */
        const val SPEED_STEP = 0.1

        /** The smallest interval worth prescribing. */
        const val DURATION_STEP = 30.0
    }
}
