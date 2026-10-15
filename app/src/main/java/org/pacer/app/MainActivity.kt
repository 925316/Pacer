package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder shell. Real UI (map, route editor, joystick) lands in later milestones.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = "Pacer scaffold — motion engine lives in :core-motion."
        setContentView(view)
    }
}
