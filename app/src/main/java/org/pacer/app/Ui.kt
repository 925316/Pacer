package org.pacer.app

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView

/**
 * The plain view builders every screen uses.
 *
 * Shared so the three screens cannot drift into different type sizes. Plain framework views
 * on purpose: the engine is pure Kotlin and only the map needs a canvas.
 */

/** A screen title. */
fun Activity.titleView(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 24f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
}

/** Supporting text under a control; small, and never the only explanation of a control. */
fun Activity.hint(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setPadding(0, 4, 0, 12)
}

/** A group heading inside a scrollable page. */
fun Activity.section(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 17f
    typeface = Typeface.DEFAULT_BOLD
    setPadding(0, 20, 0, 6)
}

fun Activity.radio(label: String): RadioButton = RadioButton(this).apply { text = label }

/**
 * A number with its name, unit and explanation all permanently visible.
 *
 * Label and unit cannot live in the placeholder, which disappears the moment the field holds
 * a value - the only way to learn what "5.0" meant would be to delete it. The unit is a
 * separate view here, the explanation is a line under the row, and [reset] puts the
 * documented default back, which is otherwise only guessable.
 *
 * An emptied field is restored on focus loss rather than left blank: a blank box that
 * silently runs on a hidden default is the same bug in a different disguise.
 */
fun Activity.numberRow(
    into: LinearLayout,
    label: String,
    unit: String,
    initial: String,
    explanation: String,
    reset: String,
): EditText {
    into.addView(
        TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, 12, 0, 0)
        },
    )
    val line = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val input = EditText(this).apply {
        setText(initial)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnFocusChangeListener { _, focused ->
            if (!focused && text.isBlank()) setText(initial)
        }
    }
    line.addView(input)
    line.addView(
        TextView(this).apply {
            text = unit
            textSize = 14f
            setPadding(8, 0, 8, 0)
        },
    )
    line.addView(smallAction(reset) { input.setText(initial) })
    into.addView(line)
    into.addView(hint(explanation))
    return input
}

fun Activity.action(label: String, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    setOnClickListener { onClick() }
}

/** A compact button for a row: inline editor steps, per-waypoint actions. */
fun Activity.smallAction(label: String, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    textSize = 12f
    minWidth = 0
    minimumWidth = 0
    setPadding(16, 0, 16, 0)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    setOnClickListener { onClick() }
}

/**
 * A switch with its explanation underneath.
 *
 * The explanation is part of the control rather than a tooltip: every one of these changes
 * what the simulated track looks like, and none is obvious from its label.
 */
fun Activity.option(
    into: LinearLayout,
    label: String,
    explanation: String,
    checked: Boolean = true,
): CheckBox {
    val box = CheckBox(this).apply {
        text = label
        isChecked = checked
    }
    into.addView(box)
    into.addView(hint(explanation))
    return box
}

/** A vertical, padded page body inside a scroll view. */
fun Activity.pageBody(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    // The bottom padding is generous because the last line of a page sits against the
    // start of the pinned control bar; without it the final row reads as cut off.
    setPadding(40, 32, 40, 96)
}

/** Distinguishes "not started" from "inside a run" without a second state field. */
fun View.showIf(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
