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
 * Every failure of the module half looks the same from the inside - the map does not move,
 * and nothing says why - so the two facts that decide whether it can work at all are
 * checked before the user places a route.
 *
 * It is not a gate: everything is reported, nothing blocks the next screen.
 */
class StartupActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var strings: Strings

    /** Latest environment answer, so a language switch redraws it in the new words. */
    private var rootResult: RootCheck.Result? = null
    private var rootChecking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        strings = stringsFor(settings.language)
        // Asked before drawing, or the screen always claims "not connected".
        PacerConfigClient.init()
        PacerConfigClient.onReady { runOnUiThread { if (!isFinishing) render() } }
        // Before the skip-startup shortcut: a user who hides this screen still needs the
        // permissions, and asking from a page they will never see again would only show up
        // much later as an empty self-check.
        AppPermissions.request(this) { if (!isFinishing) render() }
        if (settings.skipStartup) {
            openMain()
            return
        }
        render()
        startRootCheck()
    }

    /** The user answered the permission dialog; the report line may now say more. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppPermissions.REQUEST_CODE) render()
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
            // Rebuilt rather than relabelled: the environment report is assembled from
            // several language-dependent parts.
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

        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(root)
            },
        )
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
