package org.pacer.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * The first screen: language, and whether this device can run what Pacer needs.
 *
 * WHY IT EXISTS AT ALL. The module half of Pacer is an LSPosed module, and every failure of
 * it looks the same from the inside: the map does not move, and nothing says why. The two
 * facts that decide whether it can work at all - a rooted device, and a connected module -
 * are cheap to check and are checked here, before the user places a route. The reference
 * implementation the user asked to follow opens the same way.
 *
 * IT IS NOT A GATE. Every conclusion is reported, none blocks the next screen: an
 * unrooted device can still run the preview and the engine tests, and a module that is not
 * bound yet binds a second later. A screen that refuses to continue would be wrong about
 * both.
 *
 * LANGUAGE IS CHOSEN HERE AND NOWHERE ELSE. The default is English regardless of the
 * phone's locale, and the choice is stored rather than re-derived, so it survives a
 * re-flash of the device and does not change under a user who has set it deliberately.
 */
class StartupActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var strings: Strings

    /** Latest environment answer, kept so a language switch redraws it in the new words. */
    private var rootResult: RootCheck.Result? = null
    private var rootChecking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        strings = stringsFor(settings.language)
        // The module line is a fact about the framework binding, not about this app's
        // memory: without this the startup screen always claimed "not connected", because
        // nothing had asked the framework yet.
        PacerConfigClient.init()
        PacerConfigClient.onReady { runOnUiThread { if (!isFinishing) render() } }
        // A returning user goes straight in. The check is a first-run aid, not a toll gate.
        if (settings.skipStartup) {
            openMain()
            return
        }
        render()
        // Started immediately rather than behind the button: an answer that is already
        // there when the screen appears is one less tap, and `su` costs nothing when it
        // is denied. The button re-runs it for the case where the user just allowed it.
        startRootCheck()
    }

    private fun render() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 64, 48, 48)

        root.addView(
            TextView(this).apply {
                text = "Pacer"
                textSize = 32f
                gravity = Gravity.CENTER
            },
        )
        root.addView(
            TextView(this).apply {
                text = strings.appTagline
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 32)
            },
        )

        // --- language -------------------------------------------------------
        root.addView(sectionOf(strings.language))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (language in Language.entries) {
            val button = RadioButton(this).apply {
                text = language.label
                id = language.ordinal + 1
                isChecked = language == settings.language
            }
            group.addView(button)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = Language.entries.getOrNull(checkedId - 1) ?: return@setOnCheckedChangeListener
            if (chosen == settings.language) return@setOnCheckedChangeListener
            settings.language = chosen
            strings = stringsFor(chosen)
            // Rebuilt rather than relabelled: every string on the page changes, and a
            // loop of setText calls would still miss the environment report, which is
            // assembled from more than one language-dependent part.
            render()
        }
        root.addView(group)

        // --- environment ----------------------------------------------------
        root.addView(sectionOf(strings.environment))
        root.addView(environmentView())
        root.addView(
            Button(this).apply {
                text = if (rootChecking) strings.checkingEnvironment else strings.checkEnvironment
                isEnabled = !rootChecking
                setOnClickListener { startRootCheck() }
            },
        )

        // --- continue -------------------------------------------------------
        val skip = CheckBox(this).apply { text = strings.dontShowAgain }
        root.addView(skip)
        root.addView(
            Button(this).apply {
                text = strings.continueLabel
                setOnClickListener {
                    settings.skipStartup = skip.isChecked
                    openMain()
                }
            },
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Root and module state in one paragraph, in whichever language is selected. */
    private fun environmentView(): TextView {
        val result = rootResult
        val line = when {
            rootChecking -> strings.checkingEnvironment
            result == null -> strings.rootUnknown
            result.state == RootCheck.State.GRANTED -> "${strings.rootGranted} (${result.detail})"
            result.state == RootCheck.State.DENIED -> "${strings.rootDenied} (${result.detail})"
            else -> "${strings.rootUnknown} (${result.detail})"
        }
        val module = if (PacerConfigClient.bound()) strings.moduleBound else strings.moduleUnbound
        return TextView(this).apply {
            text = "$line\n$module"
            textSize = 14f
            setPadding(0, 4, 0, 12)
        }
    }

    private fun startRootCheck() {
        rootChecking = true
        render()
        RootCheck.check { result ->
            runOnUiThread {
                rootResult = result
                rootChecking = false
                render()
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun sectionOf(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 17f
            setPadding(0, 24, 0, 8)
        }
}
