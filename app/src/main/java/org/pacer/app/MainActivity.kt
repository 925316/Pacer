package org.pacer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.pacer.motion.CoordinatePolicy
import org.pacer.motion.FixSample
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionOwner
import org.pacer.motion.MotionSeed
import org.pacer.motion.PacePlan
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteDraft
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteRecorder
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SavedRoutes
import org.pacer.motion.SpeedPlan
import org.pacer.motion.TrackAudit
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The three pages: what a run is, what it runs along, and how it is reported.
 *
 * Split because the alternative was one column of fourteen sections where reaching Start
 * meant scrolling past the map button and the coordinate table.
 */
class MainActivity : Activity() {

    private enum class Page { RUN, ROUTE, SETTINGS }

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft

    private lateinit var settings: AppSettings
    private lateinit var strings: Strings

    /** startedAtMs of the run whose settings this page last adopted. */
    private var lastRestoreStartedAt = Long.MIN_VALUE

    /**
     * The one line under the pages that describes a momentary outcome.
     *
     * It floats over the bottom of the page instead of sitting in a band above it: the band
     * was reserved whether or not there was anything to say, on every page, and it held a
     * running stopwatch and a one-off confirmation in the same two TextViews.
     */
    private lateinit var noticeStrip: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeDismiss: Button

    /** The run's state, inside the pinned bar where Start and Stop already are. */
    private lateinit var runStateLine: TextView

    /**
     * Where the audit and preview reports are printed.
     *
     * On the Settings page, under the buttons that produce them, and selectable: a six-line
     * report does not fit the two-line notice strip, and text there cannot be selected. The
     * self-check panel next to it has always worked this way, so this is the existing shape
     * rather than a new one.
     */
    private lateinit var reportOutput: TextView

    private lateinit var runPageRoot: LinearLayout
    private lateinit var routePageRoot: LinearLayout
    private lateinit var settingsPageRoot: LinearLayout
    private lateinit var runScroll: ScrollView
    private lateinit var routeScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private lateinit var tabRow: LinearLayout

    private var page: Page = Page.RUN

    /** Guards the speed field's own writes from being read back as typing. */
    private var writingSpeed = false

    private lateinit var walkBtn: RadioButton
    private lateinit var runBtn: RadioButton
    private lateinit var rideBtn: RadioButton
    private lateinit var customBtn: RadioButton
    private lateinit var speedInput: EditText

    private lateinit var stepToggle: CheckBox
    private lateinit var loopToggle: CheckBox
    private lateinit var bluetoothToggle: CheckBox
    private lateinit var accuracyInput: EditText
    private lateinit var altitudeInput: EditText
    private lateinit var bearingInput: EditText

    /** The interval-plan editor; the wire string lives in [planWire]. */
    private lateinit var planEditor: PlanEditor

    /** The plan as the module reads it, or "" when the editor holds no usable plan. */
    private var planWire: String = ""

    /** The per-app coordinate table as typed. */
    private lateinit var coordinateInput: EditText
    private lateinit var bearingHoldToggle: CheckBox
    private lateinit var altitudeDriftToggle: CheckBox
    private lateinit var accuracyWarmupToggle: CheckBox
    private lateinit var satelliteJitterToggle: CheckBox
    private lateinit var signalDropoutToggle: CheckBox

    private lateinit var routeSummary: TextView

    /** One row per saved route: name, length, and the three actions on it. */
    private lateinit var savedRouteBox: LinearLayout

    /**
     * The saved route whose geometry matches the draft exactly, if any.
     *
     * Derived from the content rather than a "loaded this id" flag: every edit path would
     * otherwise have to reset it, and one missed reset makes the next save overwrite an
     * unrelated route.
     */
    private fun currentSavedRouteId(routes: SavedRoutes = store.savedRoutes()): Long? =
        routes.routes.firstOrNull { it.points == draft.points }?.id

    private lateinit var previewBtn: Button
    private lateinit var auditBtn: Button

    /** The self-check report as text; selectable, so an unselectable report is impossible. */
    private lateinit var selfCheckOutput: TextView

    /** Pauses or resumes a route run; hidden whenever neither action applies. */
    private lateinit var pauseBtn: Button

    /** Starts and stops the joystick recording; its label shows which way the next tap goes. */
    private lateinit var recordBtn: Button

    /**
     * The recording in progress, or null.
     *
     * Held here rather than in the overlay service: this page is the only writer of the
     * draft, and a recording that outlived it would collect points nobody could stop.
     */
    private var recorder: RouteRecorder? = null
    private var recordStartedAtMs = 0L

    /**
     * The sampling tick.
     *
     * Main thread on purpose: it reads the config and writes the draft, both cheap, and a
     * background thread would have to marshal every point back here anyway.
     */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val subject = LiveSubject()
    private val recordTick = object : Runnable {
        override fun run() {
            sampleRecording()
            if (recorder != null) handler.postDelayed(this, RECORD_POLL_MS)
        }
    }

    /** Refreshes the status line once a second, including runs changed on the map page. */
    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = RouteStore(this)
        draft = store.load()
        settings = AppSettings(this)
        strings = stringsFor(settings.language)

        runPageRoot = buildRunPage()
        routePageRoot = buildRoutePage()
        settingsPageRoot = buildSettingsPage()

        // The run state lives in the pinned bar with Start and Stop; there is no separate
        // status band, because a band reserved on every page would mostly be empty and
        // would mix a stopwatch with one-off confirmations in the same two TextViews.
        // Outcomes instead float over the bottom of the page.
        runScroll = scrollPage(runPageRoot)
        routeScroll = scrollPage(routePageRoot)
        settingsScroll = scrollPage(settingsPageRoot)

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // The window is edge to edge on API 35, so without this the status bar sits
                // on top of the first row and the gesture bar on top of the tab strip.
                fitsSystemWindows = true
                // A second, fixed allowance because fitsSystemWindows only reports a
                // navigation bar when the device draws one as a window; a gesture pill is
                // drawn by the system and still overlaps this row.
                setPadding(0, 0, 0, dp(NAV_BAR_ALLOWANCE_DP))
                addView(
                    FrameLayout(this@MainActivity).apply {
                        addView(runScroll)
                        addView(routeScroll)
                        addView(settingsScroll)
                        // Last child, so it draws above the pages. It covers the tail of a
                        // scroll view rather than pushing the page up: a message that appears
                        // and disappears must not resize the page under the user's thumb.
                        addView(
                            buildNoticeStrip(),
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM,
                            ),
                        )
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
                addView(runBar())
                addView(tabBar())
            },
        )

        PacerConfigClient.init()
        selectPage(Page.entries.getOrElse(savedInstanceState?.getInt(KEY_PAGE) ?: 0) { Page.RUN })
        renderRoute()
        renderSavedRoutes()
        updatePauseButton()
        // Opens on the stored configuration, not on this screen's defaults: every start
        // writes all of these keys, so touching one switch on defaults would silently
        // restore the others.
        PacerConfigClient.onReady { runOnUiThread { restoreFromStore() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page.ordinal)
    }

    /**
     * The page the user actually reaches for.
     *
     * Speed, the interval plan and the switches that change what a run looks like. Kept
     * separate from the route so that starting a run does not mean scrolling past the map
     * button, and separate from the settings so a knob that is set once is not in the way.
     */
    private fun buildRunPage(): LinearLayout {
        val root = pageBody()
        root.addView(titleView(strings.tabRun))

        root.addView(section(strings.speed))
        val speedGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        walkBtn = radio(strings.walk)
        runBtn = radio(strings.run)
        rideBtn = radio(strings.ride)
        customBtn = radio(strings.custom)
        speedGroup.addView(walkBtn)
        speedGroup.addView(runBtn)
        speedGroup.addView(rideBtn)
        speedGroup.addView(customBtn)
        runBtn.isChecked = true
        root.addView(speedGroup)
        speedInput = numberRow(
            root,
            strings.customSpeed,
            strings.unitMps,
            "3.0",
            strings.customSpeedHint,
            strings.resetToDefault,
        )
        // Typing a speed is choosing Custom. The guard stops the radio listener's own
        // write from being read back as typing.
        speedInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!writingSpeed && !customBtn.isChecked) customBtn.isChecked = true
                }
            },
        )
        speedGroup.setOnCheckedChangeListener { _, checkedId ->
            // The field mirrors the chosen preset, so what it shows is what will run.
            val preset = when (checkedId) {
                walkBtn.id -> WALK_SPEED
                rideBtn.id -> RIDE_SPEED
                runBtn.id -> RUN_SPEED
                else -> null
            }
            if (preset != null) {
                writingSpeed = true
                speedInput.setText(String.format(Locale.US, "%.1f", preset))
                writingSpeed = false
            }
        }

        // The editor draws its own heading: it is folded, and the heading doubles as the
        // tap target that opens it.
        planEditor = PlanEditor(this, strings) { wire -> planWire = wire }
        root.addView(planEditor.view)
        loopToggle = option(
            root,
            strings.loopRoute,
            strings.loopRouteHint,
            checked = false,
        )
        stepToggle = option(
            root,
            strings.stepSimulation,
            strings.stepSimulationHint,
        )
        bluetoothToggle = option(
            root,
            strings.bluetooth,
            strings.bluetoothHint,
        )
        return root
    }

    /**
     * Everything about the line being run: drawing it, keeping it, replaying it later.
     *
     * The points themselves are placed on the map. This page is the list of what exists and
     * the actions on it, so there is no second editor for the same geometry.
     */
    private fun buildRoutePage(): LinearLayout {
        val root = pageBody()
        root.addView(titleView(strings.tabRoute))

        root.addView(hint(strings.routeHint))
        root.addView(action(strings.openMap) { openMap() })
        routeSummary = TextView(this).apply {
            textSize = 15f
            setPadding(0, 16, 0, 8)
            setOnClickListener { openMap() }
        }
        root.addView(routeSummary)

        root.addView(action(strings.loadSample) { loadSampleRoute() })
        root.addView(action(strings.clearRoute) { clearDraftRoute() })

        // Hand steering and recording both need a run the joystick can drive, and only a
        // straight-line run qualifies - a route or a plan owns its own motion. So this stays
        // reachable, but here rather than in the bottom bar: it is how you record a walk,
        // not how you run a drawn route.
        //
        // It brings the joystick up itself. Starting the run and then telling the user to go
        // find the joystick in the bottom bar was the reason this looked like a button for a
        // feature nobody could reach.
        root.addView(
            action(strings.startJoystickMode) {
                // Only when the run really started: on a refusal there is nothing to steer,
                // and a floating window over an idle subject explains nothing.
                if (startStraight()) showJoystick()
            },
        )
        recordBtn = action(strings.startRecording) { toggleRecording() }
        root.addView(recordBtn)

        root.addView(section(strings.savedRoutes))
        root.addView(action(strings.saveRoute) { saveCurrentRoute() })
        savedRouteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedRouteBox)
        return root
    }

    /**
     * What is configured once and then left alone.
     *
     * Numbers the run reports, the realism switches, coordinate overrides, and the
     * diagnostics. Nothing here changes the route or the speed from one minute to the next.
     */
    private fun buildSettingsPage(): LinearLayout {
        val root = pageBody()
        root.addView(titleView(strings.tabSettings))

        root.addView(section(strings.simulation))
        root.addView(hint(strings.simulationHint))
        accuracyInput = numberRow(
            root,
            strings.accuracy,
            strings.unitMeters,
            "5.0",
            strings.accuracyHint,
            strings.resetToDefault,
        )
        altitudeInput = numberRow(
            root,
            strings.altitude,
            strings.unitMeters,
            "0.0",
            strings.altitudeHint,
            strings.resetToDefault,
        )
        bearingInput = numberRow(
            root,
            strings.bearing,
            strings.unitDegrees,
            "45.0",
            strings.bearingHint,
            strings.resetToDefault,
        )

        // --- per-app coordinate systems --------------------------------------
        // One plain text field. The table is a preference that applies to whatever
        // package it names, and it is saved on its own rather than only at Start.
        root.addView(section(strings.coordinateSystem))
        coordinateInput = EditText(this).apply {
            hint = "com.autonavi.minimap=GCJ02"
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        }
        root.addView(coordinateInput)
        root.addView(hint(strings.coordinateSystemHint))
        root.addView(action(strings.saveOverrides) { saveCoordinateOverrides() })

        // --- signal realism --------------------------------------------------
        root.addView(section(strings.signalRealism))
        root.addView(hint(strings.signalRealismHint))
        bearingHoldToggle = option(
            root,
            strings.bearingHold,
            strings.bearingHoldHint,
        )
        altitudeDriftToggle = option(
            root,
            strings.altitudeDrift,
            strings.altitudeDriftHint,
        )
        accuracyWarmupToggle = option(
            root,
            strings.accuracyWarmup,
            strings.accuracyWarmupHint,
        )
        satelliteJitterToggle = option(
            root,
            strings.satelliteJitter,
            strings.satelliteJitterHint,
        )
        signalDropoutToggle = option(
            root,
            strings.signalDropout,
            strings.signalDropoutHint,
        )

        previewBtn = action(strings.preview) { previewLocally() }
        root.addView(previewBtn)

        auditBtn = action(strings.audit) { auditRoute() }
        root.addView(auditBtn)

        // Both buttons print here, so the report sits under the control that made it and can
        // be selected. One view for both: two reports side by side would only raise the
        // question of which one is current.
        reportOutput = MessageView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 0)
        }
        root.addView(reportOutput)

        // --- diagnostics ------------------------------------------------------
        // Plain text in a plain box. The sheet is for reading and for pasting into a
        // chat, not for looking at, so there is no styling to keep in step with the
        // rest of the page.
        root.addView(section(strings.selfCheck))
        root.addView(action(strings.runSelfCheck) { runSelfCheck() })
        root.addView(hint(strings.selfCheckHint))
        selfCheckOutput = MessageView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 0)
        }
        root.addView(selfCheckOutput)

        // --- language ---------------------------------------------------------
        // The startup screen is the primary place this is chosen; this is the second,
        // for a user who already skipped that screen.
        root.addView(section(strings.language))
        val languageGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (language in Language.entries) {
            languageGroup.addView(
                RadioButton(this).apply {
                    text = language.label
                    id = language.ordinal + 1
                    isChecked = language == settings.language
                },
            )
        }
        languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val chosen = Language.entries.getOrNull(checkedId - 1) ?: return@setOnCheckedChangeListener
            if (chosen == settings.language) return@setOnCheckedChangeListener
            settings.language = chosen
            // Recreated rather than relabelled: labels are built inline by the view
            // helpers, so a setText loop would leave some of them in the previous language.
            recreate()
        }
        root.addView(languageGroup)
        return root
    }

    private fun scrollPage(content: LinearLayout): ScrollView =
        ScrollView(this).apply { addView(content) }

    /**
     * The three pages as a tab strip, not as three buttons.
     *
     * A button reads as "do something"; a tab reads as "go somewhere", and the underline is
     * what says where you are. Disabling the current page's button would make the selected
     * tab look broken rather than selected.
     */
    private fun tabBar(): LinearLayout {
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (candidate in Page.entries) {
            val label = TextView(this).apply {
                text = when (candidate) {
                    Page.RUN -> strings.tabRun
                    Page.ROUTE -> strings.tabRoute
                    Page.SETTINGS -> strings.tabSettings
                }
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(8))
            }
            val underline = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(2),
                )
            }
            tabRow.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                    isClickable = true
                    setOnClickListener { selectPage(candidate) }
                    addView(label)
                    addView(underline)
                },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1),
                    )
                    setBackgroundColor(TAB_DIVIDER)
                },
            )
            addView(tabRow)
        }
    }

    private fun selectPage(chosen: Page) {
        page = chosen
        runScroll.showIf(chosen == Page.RUN)
        routeScroll.showIf(chosen == Page.ROUTE)
        settingsScroll.showIf(chosen == Page.SETTINGS)
        for (i in 0 until tabRow.childCount) {
            val tab = tabRow.getChildAt(i) as? LinearLayout ?: continue
            val active = Page.entries.getOrNull(i) == chosen
            (tab.getChildAt(0) as? TextView)?.setTextColor(if (active) TAB_ACTIVE else TAB_INACTIVE)
            tab.getChildAt(1)?.setBackgroundColor(if (active) TAB_ACTIVE else Color.TRANSPARENT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun openMap() {
        startActivity(Intent(this, MapActivity::class.java))
    }

    /** Clears the drawn route, asking first when there is more than a stray point to lose. */
    private fun clearDraftRoute() {
        if (draft.size < 2) {
            draft.clear()
            persistAndRender()
            renderSavedRoutes()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(strings.clearRouteTitle)
            .setMessage(String.format(Locale.US, strings.clearRouteMessage, draft.size))
            .setPositiveButton(strings.clear) { _, _ ->
                draft.clear()
                persistAndRender()
                renderSavedRoutes()
            }
            .setNegativeButton(strings.cancel, null)
            .show()
    }

    /** Shows what is actually configured. */
    private fun restoreFromStore() {
        // Read on its own: a table saved before any run was ever started is still applied by
        // the hook side, so the field must show it even when nothing else is stored.
        coordinateInput.setText(PacerConfigClient.coordinateOverrides())
        val stored = PacerConfigClient.stored() ?: return
        lastRestoreStartedAt = stored.startedAtMs
        accuracyInput.setText(String.format(Locale.US, "%.1f", stored.accuracy))
        altitudeInput.setText(String.format(Locale.US, "%.1f", stored.altitude))
        bearingInput.setText(String.format(Locale.US, "%.0f", stored.bearing))
        // A stopped run stores speed 0, which is a state and not a speed the user
        // chose; the field keeps its previous value in that case.
        if (stored.speed > 0.0) {
            speedInput.setText(String.format(Locale.US, "%.1f", stored.speed))
            when (stored.speed) {
                WALK_SPEED -> walkBtn.isChecked = true
                RUN_SPEED -> runBtn.isChecked = true
                RIDE_SPEED -> rideBtn.isChecked = true
                else -> customBtn.isChecked = true
            }
        }
        stepToggle.isChecked = stored.stepSimulation
        bluetoothToggle.isChecked = stored.mockBluetooth
        planEditor.load(stored.plan)
        loopToggle.isChecked = stored.loopRoute
        bearingHoldToggle.isChecked = stored.realism.bearingHold
        altitudeDriftToggle.isChecked = stored.realism.altitudeDrift
        accuracyWarmupToggle.isChecked = stored.realism.accuracyWarmup
        satelliteJitterToggle.isChecked = stored.realism.satelliteJitter
        signalDropoutToggle.isChecked = stored.realism.signalDropout
        // No message here any more: "a run is active" is what the state line in the pinned
        // bar says, and it says it continuously instead of once when the page resumed.
        updatePauseButton()
    }

    /**
     * Freezes a running route where it stands, or lets it continue.
     *
     * The clock is what stops, so position, steps, distance and lap stay consistent instead
     * of being re-derived from a separately stored point.
     */
    private fun toggleRoutePause() {
        val owner = PacerConfigClient.owner()
        val pause = owner.canPauseRoute
        if (!pause && !owner.canResumeRoute) {
            // Reachable only if the state changed between the poll and the tap; the button
            // itself is disabled in this case, see updatePauseButton.
            updatePauseButton()
            return
        }
        if (!PacerConfigClient.setRoutePaused(pause)) {
            setNotice(strings.configNotReady, NoticeKind.FAILURE)
            return
        }
        setNotice(
            if (pause) {
                strings.routePausedWhereItStands
            } else {
                strings.routeResumedFromPoint
            },
        )
        updatePauseButton()
    }

    /** Enables the pause control only while it would do something. */
    private fun updatePauseButton() {
        if (!::pauseBtn.isInitialized) return
        val owner = PacerConfigClient.owner()
        // Disabled, not hidden: hiding it reflows the bar, so Stop moves under the user's
        // finger at the moment the run state changes.
        pauseBtn.isEnabled = owner.canPauseRoute || owner.canResumeRoute
        pauseBtn.text = if (owner.canResumeRoute) strings.resumeRoute else strings.pauseRoute
    }

    /**
     * The state line in the pinned bar, and nothing else.
     *
     * Derives from what is stored, never from a button press: a start that failed writes
     * nothing, so this cannot end up claiming a run that is not happening. When there is no
     * run and the channel is bound it says nothing at all, because the disabled Pause button
     * beside it already carries that fact.
     */
    private fun updateStatus() {
        val stored = PacerConfigClient.stored()
        val active = stored?.active == true
        val paused = stored?.routePaused == true
        val state = runStateOf(active = active, paused = paused, bound = PacerConfigClient.bound())
        if (!::runStateLine.isInitialized) return
        if (state == RunState.HIDDEN) {
            // Hidden rather than an empty line: an empty line still costs its height.
            runStateLine.visibility = View.GONE
            return
        }
        runStateLine.visibility = View.VISIBLE
        val label = when (state) {
            RunState.UNBOUND -> strings.moduleUnbound
            RunState.PAUSED -> strings.statusPaused
            RunState.RUNNING -> strings.statusRunning
            RunState.HIDDEN -> ""
        }
        runStateLine.text = if (!active || stored == null) {
            label
        } else {
            // A route advances on its own clock; a straight run on its anchor, which hand
            // steering moves without restarting the run.
            val now = System.currentTimeMillis()
            val seconds = if (stored.onRoute) {
                stored.routeSeconds(now)
            } else {
                stored.straightSeconds(now)
            }.toLong()
            val point = subject.point(stored)
            val where = point?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }
                ?: String.format(Locale.US, "%.5f, %.5f", stored.lat, stored.lon)
            String.format(
                Locale.US,
                "%s   %d:%02d   %s   %.1f m/s",
                label,
                seconds / 60,
                seconds % 60,
                where,
                stored.speed,
            )
        }
    }

    /**
     * Shows an outcome in the strip over the bottom of the page.
     *
     * Three lifetimes: a confirmation goes away on its own, a long job stays until its
     * result arrives, and a failure stays until the user dismisses it or does something
     * that replaces it. That last case is the one that
     * matters: a user who looked away at the wrong moment must not come back to a screen
     * that looks like the run started.
     */
    private fun setNotice(text: String, kind: NoticeKind = NoticeKind.INFO) {
        if (!::noticeStrip.isInitialized) return
        showNotice(text, kind)
    }

    private fun showNotice(text: String, kind: NoticeKind) {
        handler.removeCallbacks(hideNoticeTask)
        noticeText.text = text
        noticeStrip.visibility = View.VISIBLE
        noticeStrip.setBackgroundColor(
            when (kind) {
                NoticeKind.FAILURE -> NOTICE_FAILURE_BG
                NoticeKind.PROGRESS -> NOTICE_PROGRESS_BG
                NoticeKind.INFO -> NOTICE_INFO_BG
            },
        )
        noticeDismiss.visibility = if (kind == NoticeKind.FAILURE) View.VISIBLE else View.GONE
        // A failure is the one thing worth interrupting a screen reader for; the rest can
        // wait for the reader to reach them.
        noticeStrip.accessibilityLiveRegion = if (kind == NoticeKind.FAILURE) {
            View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        } else {
            View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val ms = noticeDurationMs(text, kind)
        if (ms > 0L) handler.postDelayed(hideNoticeTask, ms)
    }

    /** Hides the strip; the text goes too so a screen reader cannot still find it. */
    private fun hideNotice() {
        if (!::noticeStrip.isInitialized) return
        noticeStrip.visibility = View.GONE
        noticeText.text = ""
    }

    private val hideNoticeTask = Runnable { hideNotice() }

    /** The floating strip itself. Built once; [showNotice] only changes its content. */
    private fun buildNoticeStrip(): LinearLayout {
        noticeStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Clicks must not fall through to the page underneath: on the map page a tap
            // appends a waypoint, and a message drifting over it must not do that.
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(10), dp(8), dp(10))
            visibility = View.GONE
            elevation = dp(4).toFloat()
        }
        noticeText = TextView(this).apply {
            textSize = 14f
            setTextColor(NOTICE_TEXT_COLOR)
            setLineSpacing(0f, 1.15f)
        }
        noticeStrip.addView(
            noticeText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        noticeDismiss = Button(this).apply {
            text = strings.dismissNotice
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { hideNotice() }
        }
        noticeStrip.addView(noticeDismiss)
        return noticeStrip
    }

    override fun onResume() {
        super.onResume()
        // The map screen edits the same persisted draft, so re-read it here.
        draft = store.load()
        renderRoute()
        renderSavedRoutes()
        // A run started from the other screen carries settings this page shows; adopt them
        // only when the stored run actually changed, so a half-typed value survives a
        // permission dialog.
        val stored = PacerConfigClient.stored()
        if (stored != null && stored.startedAtMs != lastRestoreStartedAt) restoreFromStore()
        // A pause toggled on the other screen changes nothing this page adopts, so the
        // control has to be refreshed on its own.
        updatePauseButton()
        updateStatus()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTick)
        // The strip's own timer must not fire while the page is behind another one; it would
        // hide a message the user has not seen yet on their way back.
        handler.removeCallbacks(hideNoticeTask)
        // A recording cannot outlive the page that owns the draft: the tick would keep
        // collecting points nobody can see or stop. Ending it here keeps the promise the
        // button makes - what was walked is what gets saved.
        recorder?.let { finishRecording(it) }
    }

    // ---------------------------------------------------------------------
    // Route editing
    // ---------------------------------------------------------------------

    /** Replaces the route with a fixed one, for trying the engine without drawing anything. */
    private fun loadSampleRoute() {
        draft.clear()
        SAMPLE_ROUTE.forEach { draft.add(it) }
        persistAndRender()
        renderSavedRoutes()
    }

    /**
     * Saves the per-app datum table.
     *
     * Parsed before storing and written back normalised, so a typo shows up as a dropped
     * entry immediately rather than as a fix in the wrong datum on the next run.
     */
    private fun saveCoordinateOverrides() {
        val typed = coordinateInput.text.toString()
        val parsed = CoordinatePolicy.parse(typed)
        val normalised = CoordinatePolicy.format(parsed)
        coordinateInput.setText(normalised)
        if (!PacerConfigClient.saveCoordinateOverrides(normalised)) {
            setNotice(strings.configNotReady, NoticeKind.FAILURE)
            return
        }
        val dropped = typed.split(';').count { it.isNotBlank() } - parsed.size
        when {
            parsed.isEmpty() && typed.isBlank() ->
                setNotice(strings.overridesCleared)
            parsed.isEmpty() ->
                setNotice(strings.overridesUnreadable, NoticeKind.FAILURE)
            dropped > 0 -> setNotice(
                String.format(Locale.US, strings.overridesSavedPartial, parsed.size, dropped),
                NoticeKind.FAILURE,
            )
            else -> setNotice(String.format(Locale.US, strings.overridesSaved, parsed.size))
        }
    }

    private fun persistAndRender() {
        store.save(draft)
        renderRoute()
    }

    // ---------------------------------------------------------------------
    // Saved routes
    // ---------------------------------------------------------------------

    /** Asks for a name, then stores the current draft under it. */
    private fun saveCurrentRoute() {
        if (!draft.isValid) {
            setNotice(strings.routeNeedsTwoPoints, NoticeKind.FAILURE)
            return
        }
        val stored = store.savedRoutes()
        val existing = currentSavedRouteId(stored)?.let { stored.find(it) }
        val field = EditText(this).apply {
            hint = strings.routeName
            setText(existing?.name ?: "")
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing != null) "Save over \"${existing.name}\"?" else "Save route as")
            .setView(field)
            .setPositiveButton("Save") { _, _ -> commitSave(field.text.toString(), existing?.id) }
            .setNegativeButton("Cancel", null)
            // "Save as new" exists because the only other way to avoid overwriting a
            // loaded route would be to edit it first, which is not an answer.
            .setNeutralButton("Save as new") { _, _ -> commitSave(field.text.toString(), null) }
            .show()
    }

    /** One place for the two save buttons, so they cannot diverge in what they report. */
    private fun commitSave(name: String, replaceId: Long?) {
        val routes = store.savedRoutes()
        when (val result = routes.save(draft, name, replaceId)) {
            is SavedRoutes.SaveResult.Saved -> {
                store.saveNamedRoutes(routes)
                setNotice(
                    if (result.replaced) {
                        String.format(Locale.US, strings.routeReplaced, result.route.name)
                    } else {
                        String.format(
                            Locale.US,
                            strings.routeSavedAs,
                            result.route.name,
                            result.route.points.size,
                            result.route.lengthMeters,
                        )
                    },
                )
                renderSavedRoutes()
            }
            is SavedRoutes.SaveResult.InvalidName ->
                setNotice(result.reason, NoticeKind.FAILURE)
            is SavedRoutes.SaveResult.InvalidRoute ->
                setNotice(result.reason, NoticeKind.FAILURE)
        }
    }

    /** Rebuilds the saved-route rows. */
    private fun renderSavedRoutes() {
        if (!::savedRouteBox.isInitialized) return
        savedRouteBox.removeAllViews()
        val routes = store.savedRoutes()
        if (routes.size == 0) {
            savedRouteBox.addView(hint(strings.noSavedRoutes))
            return
        }
        val currentId = currentSavedRouteId(routes)
        for (route in routes.sortedByName()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = String.format(
                    Locale.US,
                    "%s  (%d points, %.0f m)%s",
                    route.name,
                    route.points.size,
                    route.lengthMeters,
                    if (route.id == currentId) "  *" else "",
                )
                textSize = 14f
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(smallAction("Load") { loadSavedRoute(route.id) })
            row.addView(smallAction("Rename") { renameSavedRoute(route.id) })
            row.addView(smallAction("x") { deleteSavedRoute(route.id) })
            savedRouteBox.addView(row)
        }
    }

    /**
     * Replaces the draft with a saved route.
     *
     * Asks first when the draft holds something that is not already that saved route: a
     * Load tap must not destroy work the user cannot get back. Loading the route that is
     * already in the editor, or loading over an empty draft, does not need a question.
     */
    private fun loadSavedRoute(id: Long) {
        val routes = store.savedRoutes()
        val route = routes.find(id) ?: return
        val replacesWork = draft.isValid && id != currentSavedRouteId(routes)
        val apply = {
            val loaded = routes.draftOf(id)
            if (loaded == null) {
                setNotice(strings.routeGone, NoticeKind.FAILURE)
            } else {
                draft = loaded
                persistAndRender()
                renderSavedRoutes()
                setNotice(
                    String.format(
                        Locale.US,
                        strings.routeLoadedAs,
                        route.name,
                        route.points.size,
                        strings.waypointCountSuffix,
                    ),
                )
            }
        }
        if (!replacesWork) {
            apply()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Replace the current draft?")
            .setMessage(
                "Loading \"${route.name}\" discards the current ${draft.size} waypoints. " +
                    "Save them first if they are worth keeping.",
            )
            .setPositiveButton("Load") { _, _ -> apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameSavedRoute(id: Long) {
        val routes = store.savedRoutes()
        val route = routes.find(id) ?: return
        val field = EditText(this).apply {
            setText(route.name)
            setSelection(text.length)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename route")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                if (routes.rename(id, field.text.toString())) {
                    store.saveNamedRoutes(routes)
                    renderSavedRoutes()
                    setNotice(strings.renamed)
                } else {
                    setNotice(
                        String.format(
                            Locale.US,
                            strings.nameRequiredWithLimit,
                            SavedRoutes.MAX_NAME_LENGTH,
                        ),
                        NoticeKind.FAILURE,
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSavedRoute(id: Long) {
        val route = store.savedRoutes().find(id) ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"${route.name}\"?")
            .setMessage("The route is removed from the list. The map draft is not affected.")
            .setPositiveButton("Delete") { _, _ ->
                val routes = store.savedRoutes()
                routes.remove(id)
                store.saveNamedRoutes(routes)
                // The draft stays as it is: deleting the saved copy of a route is not a
                // request to make the map forget what is drawn on it.
                renderSavedRoutes()
                setNotice(strings.deleted)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The route in one line.
     *
     * The per-point rows that used to live here are gone: the points are placed, dragged and
     * removed on the map, and a second list that also edits them is exactly the duplication
     * this screen is being cleared of. What is left is the one thing the map cannot say at a
     * glance - how long the route is.
     */
    private fun renderRoute() {
        routeSummary.text = String.format(
            Locale.US,
            strings.routeSummary,
            draft.size,
            draft.totalLengthMeters(),
            if (draft.isValid) "" else strings.routeSummaryIncomplete,
        )
    }

    // ---------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------

    private fun speedMps(): Double = when {
        customBtn.isChecked -> speedInput.text.toString().toDoubleOrNull()?.coerceIn(0.1, 30.0) ?: RUN_SPEED
        rideBtn.isChecked -> RIDE_SPEED
        walkBtn.isChecked -> WALK_SPEED
        else -> RUN_SPEED
    }

    /**
     * A straight-line run from the first waypoint on the map.
     *
     * There is no coordinate field any more, so the start point has to come from the route:
     * the first waypoint is the one the user placed first, and a run that ignored it would
     * be a run from nowhere in particular.
     */
    /** Asks before replacing a run that is already going. */
    private fun confirmReplace(onConfirm: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle(strings.replaceRunTitle)
            .setMessage(strings.replaceRunMessage)
            .setPositiveButton(strings.replaceRunConfirm) { _, _ -> onConfirm() }
            .setNegativeButton(strings.cancel, null)
            .show()
    }

    /**
     * A straight-line run from the first waypoint on the map.
     *
     * There is no coordinate field any more, so the start point has to come from the route:
     * the first waypoint is the one the user placed first, and a run that ignored it would
     * be a run from nowhere in particular.
     *
     * @return true when a run was actually written, so the caller knows whether showing the
     *   joystick now would point at something real. Every refusal path returns false.
     */
    private fun startStraight(confirmed: Boolean = false): Boolean {
        if (!confirmed && PacerConfigClient.owner() != MotionOwner.NONE) {
            confirmReplace { startStraight(true) }
            return false
        }
        val start = draft.points.firstOrNull()
        if (start == null) {
            setNotice(strings.placeWaypointFirst, NoticeKind.FAILURE)
            return false
        }
        val lat = start.lat
        val lon = start.lon
        val speed = speedMps()
        val plan = planForStart() ?: return false
        val started = PacerConfigClient.start(
            lat = lat,
            lon = lon,
            speed = speed,
            bearing = numberOr(bearingInput, 45f),
            accuracy = numberOr(accuracyInput, 5f),
            altitudeMeters = doubleOr(altitudeInput, 0.0),
            plan = plan,
            mockBluetooth = bluetoothToggle.isChecked,
            stepSimulation = stepToggle.isChecked,
            realism = realismToggles(),
        )
        // Nothing was stored when the channel is not bound, so claiming a run here would
        // send the user looking for the wrong problem on the target side.
        if (!started) {
            setNotice(strings.configNotReady, NoticeKind.FAILURE)
            return false
        }
        setNotice(
            if (plan.isBlank()) {
                String.format(
                    Locale.US,
                    strings.startedJoystickMode,
                    lat,
                    lon,
                    numberOr(bearingInput, 45f),
                    speed,
                )
            } else {
                // The plan overrides the speed field, so quoting only the field would
                // describe a run that is not happening.
                String.format(
                    Locale.US,
                    strings.startedJoystickModeWithPlan,
                    lat,
                    lon,
                    numberOr(bearingInput, 45f),
                    planSummary(plan),
                )
            },
        )
        updatePauseButton()
        return true
    }

    /**
     * A line about the interval plan that is about to run, using the plan's own arithmetic.
     */
    private fun planSummary(plan: String): String {
        // A pace curve is described in splits, not in passes: quoting "0 s / 0 m per pass"
        // for one would be the summary of a plan that is not running.
        PacePlan.parse(plan)?.let { pace ->
            val meanPace = pace.totalSeconds / (pace.distanceMeters / 1000.0)
            return " " + String.format(
                Locale.US,
                strings.paceSummary,
                kotlin.math.round(pace.distanceMeters / 1000.0).toInt(),
                formatPace(meanPace),
            ) + "."
        }
        val parsed = SpeedPlan.parse(plan) ?: return ""
        val pass = String.format(Locale.US, strings.planPassSummary, parsed.passSeconds, parsed.passMeters)
        val total = parsed.totalMeters
            ?.let { String.format(Locale.US, strings.planTotalSummary, it) }
            ?: strings.planTotalForever
        return " Plan: $pass$total."
    }

    /** `5:00` for 300 seconds; a runner reads a split as minutes and seconds. */
    private fun formatPace(seconds: Double): String {
        val total = kotlin.math.round(seconds).toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** The signal-realism switches as the engine's own type, so nothing has to list them twice. */
    private fun realismToggles(): RealismToggles = RealismToggles(
        bearingHold = bearingHoldToggle.isChecked,
        altitudeDrift = altitudeDriftToggle.isChecked,
        accuracyWarmup = accuracyWarmupToggle.isChecked,
        satelliteJitter = satelliteJitterToggle.isChecked,
        signalDropout = signalDropoutToggle.isChecked,
    )

    /**
     * Starts or stops a joystick recording.
     *
     * Recording needs a run the joystick can steer: a route or a plan owns its own motion,
     * and recording one would just re-record the route the map already has.
     */
    private fun toggleRecording() {
        val active = recorder
        if (active == null) {
            val owner = PacerConfigClient.owner()
            if (!owner.acceptsHandSteering) {
                setNotice(
                    when (owner) {
                        MotionOwner.ROUTE, MotionOwner.ROUTE_PAUSED -> strings.recordNeedsJoystickRoute
                        MotionOwner.PLAN -> strings.recordNeedsJoystickPlan
                        else -> strings.recordNeedsJoystickIdle
                    },
                    NoticeKind.FAILURE,
                )
                return
            }
            recorder = RouteRecorder()
            recordStartedAtMs = System.currentTimeMillis()
            recordBtn.text = strings.stopRecording
            setNotice(strings.recording)
            sampleRecording()
            handler.removeCallbacks(recordTick)
            handler.postDelayed(recordTick, RECORD_POLL_MS)
        } else {
            finishRecording(active)
        }
    }

    /** One sample of the running subject, offered to the recorder's decimation rule. */
    private fun sampleRecording() {
        val recording = recorder ?: return
        val stored = PacerConfigClient.stored() ?: return
        if (!stored.active) return
        subject.point(stored)?.let { recording.offer(it) }
        recordBtn.text = String.format(
            Locale.US,
            "%s (%d pts, %.0f m)",
            strings.stopRecording,
            recording.size,
            recording.distanceMeters,
        )
    }

    /**
     * Ends the recording and replaces the draft with what was walked.
     *
     * The last position is forced in, so the route ends where the subject stopped rather
     * than where it last crossed a threshold. An empty recording leaves the draft alone:
     * a stray tap on the button must not erase a route the user already had.
     */
    private fun finishRecording(recording: RouteRecorder) {
        handler.removeCallbacks(recordTick)
        recorder = null
        recordBtn.text = strings.startRecording
        val stored = PacerConfigClient.stored()
        val last = stored?.takeIf { it.active }?.let { subject.point(it) }
        if (!recording.finish(last)) {
            setNotice(strings.nothingRecorded, NoticeKind.FAILURE)
            return
        }
        draft = recording.toDraft()
        persistAndRender()
        renderSavedRoutes()
        setNotice(
            String.format(
                Locale.US,
                strings.recordedSummary,
                recording.size,
                recording.distanceMeters,
                ((System.currentTimeMillis() - recordStartedAtMs) / 1000L).toInt(),
            ),
        )
    }

    /**
     * The plan a run should start with, or null after saying why not.
     *
     * The editor reports its own reason ("a step needs a speed above zero"), and opening it
     * puts that reason on screen instead of leaving the user to guess which field is wrong.
     */
    private fun planForStart(): String? {
        if (!planEditor.isValid) {
            setNotice(strings.planInvalid, NoticeKind.FAILURE)
            planEditor.open()
            return null
        }
        return planWire
    }

    /**
     * A numeric field's value, or [fallback] when it is empty or unreadable.
     *
     * The field is written back with what was actually used. Otherwise a blank box would
     * run on a value nothing displayed, which is how "I deleted it to see the hint" turns
     * into an accuracy the user never chose.
     */
    private fun numberOr(field: EditText, fallback: Float): Float {
        val parsed = field.text.toString().toFloatOrNull()
        if (parsed == null) {
            field.setText(String.format(Locale.US, "%.1f", fallback))
            return fallback
        }
        return parsed
    }

    private fun doubleOr(field: EditText, fallback: Double): Double {
        val parsed = field.text.toString().toDoubleOrNull()
        if (parsed == null) {
            field.setText(String.format(Locale.US, "%.1f", fallback))
            return fallback
        }
        return parsed
    }

    private fun startRoute(confirmed: Boolean = false) {
        if (!confirmed && PacerConfigClient.owner() != MotionOwner.NONE) {
            confirmReplace { startRoute(true) }
            return
        }
        if (!draft.isValid) {
            setNotice(strings.needTwoWaypoints, NoticeKind.FAILURE)
            return
        }
        // The road path when one was planned on the map screen, the plain polyline otherwise.
        val geometry = store.geometry(draft)
        val route = RoutePath(geometry)
        val first = route.points.first()
        val speed = speedMps()
        val plan = planForStart() ?: return
        val started = PacerConfigClient.start(
            lat = first.lat,
            lon = first.lon,
            speed = speed,
            bearing = numberOr(bearingInput, 45f),
            accuracy = numberOr(accuracyInput, 5f),
            altitudeMeters = doubleOr(altitudeInput, 0.0),
            routeJson = RouteJson.encode(geometry),
            plan = plan,
            loopRoute = loopToggle.isChecked,
            mockBluetooth = bluetoothToggle.isChecked,
            stepSimulation = stepToggle.isChecked,
            realism = realismToggles(),
        )
        if (!started) {
            setNotice(strings.configNotReady, NoticeKind.FAILURE)
            return
        }
        setNotice(
            if (plan.isBlank()) {
                String.format(
                    Locale.US,
                    strings.startedRoute,
                    geometry.size,
                    route.totalLengthMeters,
                    speed,
                )
            } else {
                String.format(
                    Locale.US,
                    strings.startedRouteWithPlan,
                    geometry.size,
                    route.totalLengthMeters,
                    speed,
                    planSummary(plan),
                )
            },
        )
        updatePauseButton()
    }

    private fun showJoystick() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, JoystickOverlayService::class.java))
            // Saying which input owns the run here is cheaper than letting the user find
            // out by dragging the knob.
            setNotice(
                when (PacerConfigClient.owner()) {
                    MotionOwner.ROUTE -> strings.joystickLockedRoute
                    MotionOwner.ROUTE_PAUSED -> strings.joystickLockedRoutePaused
                    MotionOwner.PLAN -> strings.joystickLockedPlan
                    else -> strings.joystickReady
                },
            )
        } else {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            setNotice(strings.grantOverlay, NoticeKind.FAILURE)
        }
    }

    /**
     * Audits the current track the way a server-side heuristic would read an upload, and
     * reports how far a SECOND simulated run of the same route drifts from it.
     *
     * The audit itself judges one track. The comparison is a separate number on purpose:
     * two runs of the same route necessarily occupy the same grid cells, so the audit's
     * duplicate-track rule would flag them whether the engine randomizes or not. What
     * actually says whether the engine randomizes is the distance between the two runs
     * at the same sample index - a machine repeating itself would report zero.
     */
    private fun auditRoute() {
        // The geometry that would actually be simulated, road path included.
        //
        // No fallback to the sample route: an audit of a track the user never drew produces
        // a confident report about nothing.
        val route = routeForPreview()
        if (route == null) {
            setNotice(strings.needTwoWaypoints, NoticeKind.FAILURE)
            return
        }
        // A plan the editor rejects would silently audit a steady-speed run instead.
        if (!planEditor.isValid) {
            setNotice(strings.planInvalid, NoticeKind.FAILURE)
            planEditor.open()
            return
        }
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        // The preview follows the interval plan too, or it would show a steady-speed track
        // for a session that will not be one.
        val plan = SpeedPlan.parse(planWire)
        val pacePlan = PacePlan.parse(planWire)
        val speed = speedMps()
        // Two different runs of the same route, so the divergence below is meaningful.
        // Deterministic rather than Random, so a report can be reproduced from its numbers.
        val seedBase = System.currentTimeMillis()
        auditBtn.isEnabled = false
        setNotice(strings.auditing, NoticeKind.PROGRESS)
        thread {
            fun runOnce(seed: Long) = RouteSimulator(
                route,
                MotionConfig(baseSpeedMps = speed).copy(plan = plan, pacePlan = pacePlan),
                toggles = toggles,
                steadyAccuracyMeters = accuracy,
            ).simulate(dtSec = 1.0, seed = seed)

            val first = runOnce(MotionSeed.forRun(seedBase))
            val second = runOnce(MotionSeed.forRun(seedBase + 1))
            // Only the duplicate check is answerable here: zone and checkpoint rules need a
            // server's geometry.
            val report = TrackAudit.audit(
                second,
                TrackAudit.AuditOptions(priorTracks = listOf(first.map { it.point })),
            )
            val divergence = meanDisplacementMeters(first, second)
            val text = buildString {
                appendLine(String.format(Locale.US, "Track audit: %s (risk %.2f)", report.verdict, report.risk))
                appendLine(
                    String.format(
                        Locale.US,
                        "Two runs of this route differ by %.1f m per sample on average " +
                            "(a machine repeating itself would report 0).",
                        divergence,
                    ),
                )
                for (finding in report.findings.sortedByDescending { it.score }) {
                    appendLine(String.format(Locale.US, "%.2f  %s: %s", finding.score, finding.rule, finding.detail))
                }
            }
            runOnUiThread {
                showReport(text)
                auditBtn.isEnabled = true
            }
        }.also { worker ->
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
                runOnUiThread {
                    auditBtn.isEnabled = true
                    setNotice(
                        String.format(
                            Locale.US,
                            strings.unexpectedFailure,
                            t.javaClass.simpleName,
                            t.message ?: "",
                        ),
                        NoticeKind.FAILURE,
                    )
                }
            }
        }
    }

    /**
     * Runs the in-process self-check and shows the report.
     *
     * Off the main thread because one probe waits for a sensor heartbeat and the
     * clipboard call is cheap but the report is long. The result is shown verbatim: the
     * panel's job is to be pasted into a chat when something is wrong, so it must not
     * be summarised away here.
     */
    private fun runSelfCheck() {
        selfCheckOutput.text = strings.checkingEnvironment
        val stored = PacerConfigClient.stored()
        thread {
            val report = try {
                SelfCheckRunner(this@MainActivity, stored).run()
            } catch (t: Throwable) {
                // The runner guards each probe, so this is a bug in the runner itself;
                // say so rather than showing an empty report that reads like a pass.
                selfCheckOutput.post {
                    selfCheckOutput.text = String.format(
                        Locale.US,
                        strings.selfCheckCrashed,
                        t.javaClass.simpleName,
                        t.message ?: "",
                    )
                }
                return@thread
            }
            val text = report.copyText()
            runOnUiThread {
                selfCheckOutput.text = text
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Pacer self-check", text))
                setNotice(String.format(Locale.US, strings.selfCheckDone, report.summaryLine()))
            }
        }
    }

    /**
     * Prints a long report under the button that made it, and brings it into view.
     *
     * The scroll matters: the buttons sit low on the Settings page, so a report written below
     * them would land off screen and read as "nothing happened".
     */
    private fun showReport(text: String) {
        if (!::reportOutput.isInitialized) return
        reportOutput.text = text
        reportOutput.post {
            if (::settingsScroll.isInitialized) {
                settingsScroll.smoothScrollTo(0, reportOutput.top)
            }
        }
    }

    /** Re-runs the self-check once the probe permissions have been answered. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SelfCheckRunner.PROBE_PERMISSION_REQUEST) return
        // The first press only asked for permissions; without this the user would have to
        // press again and would see a report of SKIPs for their trouble.
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        ) {
            runSelfCheck()
        }
    }

    /** Mean distance between two runs' positions at the same sample index. */
    private fun meanDisplacementMeters(a: List<FixSample>, b: List<FixSample>): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var total = 0.0
        for (i in 0 until n) total += GeoMath.haversine(a[i].point, b[i].point)
        return total / n
    }

    /**
     * The route the previews and the audit work on: the stored road path when the map
     * planned one, the draft's own polyline otherwise.
     */
    private fun routeForPreview(): RoutePath? {
        if (!draft.isValid) return null
        val geometry = store.geometry(draft)
        return if (geometry.size >= 2) RoutePath(geometry) else null
    }

    private fun previewLocally() {
        // Same rule as the audit: a preview of a route the user did not draw is a report
        // about nothing. The sample route stays behind its own explicit button.
        val previewRoute = routeForPreview()
        if (previewRoute == null) {
            setNotice(strings.needTwoWaypoints, NoticeKind.FAILURE)
            return
        }
        // Same rule as Start: a plan the editor rejects must not be previewed as a steady run.
        if (!planEditor.isValid) {
            setNotice(strings.planInvalid, NoticeKind.FAILURE)
            planEditor.open()
            return
        }
        previewBtn.isEnabled = false
        setNotice(strings.simulating, NoticeKind.PROGRESS)
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        val plan = SpeedPlan.parse(planWire)
        val pacePlanForPreview = PacePlan.parse(planWire)
        val speed = speedMps()
        thread {
            val route = previewRoute
            val fixes = RouteSimulator(
                route,
                MotionConfig(baseSpeedMps = speed)
                    .copy(plan = plan, pacePlan = pacePlanForPreview),
                toggles = toggles,
                steadyAccuracyMeters = accuracy,
            ).simulate(dtSec = 1.0)
            val last = fixes.last()
            val avgPaceMinPerKm = if (last.distanceMeters > 0) {
                last.tSec / last.distanceMeters * 1000 / 60
            } else {
                0.0
            }
            val text = buildString {
                appendLine(String.format(Locale.US, "Route length: %.0f m", route.totalLengthMeters))
                appendLine(String.format(Locale.US, "Simulated time: %.0f s", last.tSec))
                appendLine("Steps: ${last.cumulativeSteps}")
                appendLine(String.format(Locale.US, "Avg pace: %.1f min/km", avgPaceMinPerKm))
                appendLine("Final phase: ${last.phase}")
                appendLine("Samples: ${fixes.size}")
            }
            runOnUiThread {
                showReport(text)
                previewBtn.isEnabled = true
            }
        }.also { worker ->
            // A throw would otherwise leave the button greyed out for the rest of the
            // session, frozen on "Simulating..."
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
                runOnUiThread {
                    previewBtn.isEnabled = true
                    setNotice(
                        String.format(
                            Locale.US,
                            strings.unexpectedFailure,
                            t.javaClass.simpleName,
                            t.message ?: "",
                        ),
                        NoticeKind.FAILURE,
                    )
                }
            }
        }
    }

    // The view builders live in Ui.kt: three screens share them, and a per-screen copy is
    // how one page ends up with a 12 sp hint and another with 13 sp.

    /**
     * The controls that start and stop a run, pinned below the pages.
     *
     * One full-width button to start, then a row of secondary actions. Starting is the only
     * thing worth a thumb-sized target; pausing and stopping are reached once and rarely, so
     * they stay small and share a line.
     */
    private fun runBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        // Above the Start button: the state is about the run the button belongs to, and it is
        // GONE when there is nothing to say so the bar keeps its height while idle.
        runStateLine = TextView(this).apply {
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(6))
            visibility = View.GONE
        }
        bar.addView(runStateLine)
        bar.addView(
            Button(this).apply {
                text = strings.startRouteShort
                contentDescription = strings.startRoute
                textSize = 18f
                minWidth = 0
                minimumWidth = 0
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener { startRoute() }
            },
        )

        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseBtn = weightedButton(strings.pauseRoute, strings.pauseRoute) { toggleRoutePause() }
        secondary.addView(pauseBtn)
        secondary.addView(
            weightedButton(strings.stopShort, strings.stopSimulation) {
                // Success needs no words: the state line above goes away and the pause
                // button disables, which is what "stopped" looks like. A failure does need
                // them, because otherwise the screen would look exactly like success.
                if (!PacerConfigClient.stop()) {
                    setNotice(strings.configNotReady, NoticeKind.FAILURE)
                }
                updatePauseButton()
            },
        )
        secondary.addView(weightedButton(strings.joystickShort, strings.showJoystick) { showJoystick() })
        bar.addView(secondary)
        return bar
    }

    /**
     * A button that shares its row evenly, so three actions fit one line on a phone.
     *
     * The short label is what is drawn; [description] is what a screen reader reads and what
     * a long press explains, because "Stop" alone does not say what stops.
     */
    private fun weightedButton(
        label: String,
        description: String,
        onClick: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        contentDescription = description
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        isAllCaps = false
        setPadding(8, 0, 8, 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    /** Takes no room while empty; the message area is otherwise a permanent blank line. */
    private class MessageView(context: android.content.Context) : TextView(context) {
        override fun setText(text: CharSequence?, type: BufferType?) {
            super.setText(text, type)
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val KEY_PAGE = "pacer.page"

        /** Tab strip colours: the selected page is marked by an underline, not by colour alone. */
        val TAB_ACTIVE = 0xFF1A73E8.toInt()
        val TAB_INACTIVE = 0xFF5F6368.toInt()
        val TAB_DIVIDER = 0x14000000

        /**
         * The floating strip. Solid, not translucent: it sits over running text, and a
         * see-through background under 14sp copy is unreadable on this kind of page. The
         * kind is carried by three different backgrounds plus the presence of a dismiss
         * button, so colour is never the only signal.
         */
        val NOTICE_INFO_BG = 0xFF303134.toInt()
        val NOTICE_PROGRESS_BG = 0xFF1A4C7A.toInt()
        val NOTICE_FAILURE_BG = 0xFF8C1D18.toInt()
        val NOTICE_TEXT_COLOR = 0xFFF1F1F1.toInt()

        /** Room kept clear under the tab strip for a gesture bar. */
        const val NAV_BAR_ALLOWANCE_DP = 24

        /** Speed presets, also used to recognise the stored value when the screen opens. */
        const val WALK_SPEED = 1.4
        const val RUN_SPEED = 3.0
        const val RIDE_SPEED = 6.0

        /**
         * How often a recording samples the subject.
         *
         * One second matches the run's own resolution and the decimation rule's 5 m
         * spacing at any walking pace, so a faster poll would only produce points the
         * rule discards.
         */
        const val RECORD_POLL_MS = 1000L

        /** How often the status line re-reads the stored run. */
        const val STATUS_POLL_MS = 1000L

        /**
         * A sample loop in Urumqi, the same place the map opens on.
         *
         * Somewhere almost no user is, so it cannot be mistaken for their own position.
         * Inside China because AMap and Baidu serve blank tiles outside it.
         */
        val SAMPLE_ROUTE: List<GeoPoint> = listOf(
            GeoPoint(43.8240, 87.6140),
            GeoPoint(43.8252, 87.6152),
            GeoPoint(43.8264, 87.6166),
            GeoPoint(43.8276, 87.6182),
            GeoPoint(43.8268, 87.6198),
            GeoPoint(43.8250, 87.6188),
        )
    }
}
