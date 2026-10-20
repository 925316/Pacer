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
 * Shared because three screens now build their own content, and a per-screen copy is how
 * one page ends up with a 12 sp hint and another with 13 sp. Plain framework views on
 * purpose: the engine is pure Kotlin and only the map needs a canvas.
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

fun Activity.numberField(hint: String, initial: String): EditText = EditText(this).apply {
    this.hint = hint
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
        InputType.TYPE_NUMBER_FLAG_SIGNED
    setText(initial)
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
    setPadding(40, 32, 40, 48)
}

/** Distinguishes "not started" from "inside a run" without a second state field. */
fun View.showIf(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
