package org.pacer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextWatcher
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
 * The speed the state line reports, in m/s.
 *
 * [sampleMps] is the instantaneous speed of the run as the engine computes it - the same
 * figure the map marker and the self-check read. The configured speed is not that: a route
 * carries its own speed curve and an interval plan walks through its steps, so printing
 * the configured number made a plan-driven run look like the plan had not taken effect.
 *
 * A paused route reports zero: the design is that a frozen run reports no speed, and
 * [SelfCheckRunner] substitutes zero in exactly the same place.
 *
 * [configuredMps] is the fallback when no sample exists (a stored route that cannot be
 * simulated). Approximate, but better than leaving the only speed on screen blank.
 *
 * A top-level function rather than a method so a JVM test can pin the rule.
 */
internal fun reportedSpeedMps(
    sampleMps: Double?,
    configuredMps: Double,
    paused: Boolean,
): Double = when {
    paused -> 0.0
    sampleMps != null -> sampleMps
    else -> configuredMps
}

/**
 * The pinned state line: state, elapsed time, position, speed and that speed's unit.
 *
 * Top-level and formatted from the template rather than assembled from pieces, so the unit
 * can come from [Strings.unitMps] - the same field the speed box beside it shows. The line
 * used to hard-code "m/s", which left a Chinese user reading "3.0 米/秒" in the box and
 * "3.0 m/s" in the bar for the same value.
 *
 * [elapsedSeconds] is a Long because the caller already divided the run's clock into Long
 * seconds, and narrowing it here would be a lossy step with no reason behind it.
 */
internal fun statusLineText(
    strings: Strings,
    label: String,
    elapsedSeconds: Long,
    where: String,
    speedMps: Double,
): String = String.format(
    Locale.US,
    strings.statusLine,
    label,
    elapsedSeconds / 60,
    elapsedSeconds % 60,
    where,
    speedMps,
    strings.unitMps,
)

/**
 * A labelled action offered by a notice, e.g. the button that opens the map when a start is
 * refused because there is nothing to run.
 *
 * A notice that names a next step is only useful if that step is one tap away: the route is
 * on another page and its points are placed in another activity, so plain text saying "on the
 * map" is a hint the user has to translate into navigation.
 */
internal class NoticeAction(val label: String, val onChosen: () -> Unit)

/**
 * The route line at the top of the run page.
 *
 * The run page is where the app opens, and it used to say nothing about the line a run would
 * follow: the route was one tab away and only the route page mentioned it. This line answers
 * "which line, how long" where the Start button is, and names the next step when there is
 * nothing to run yet.
 *
 * [complete] is [RouteDraft.isValid] as the caller already computes it, rather than a second
 * copy of "two distinct points" here. A single waypoint is not an error state: it still
 * cannot be a route, and saying so is what points the user at either the second point or
 * joystick mode.
 *
 * Top-level so a JVM test can pin the three cases without an Android runtime.
 */
internal fun runPageRouteSummary(
    waypointCount: Int,
    lengthMeters: Double,
    complete: Boolean,
    strings: Strings,
): String = when {
    waypointCount <= 0 -> strings.runPageNoRoute
    else -> String.format(
        Locale.US,
        strings.routeSummary,
        waypointCount,
        lengthMeters,
        if (complete) {
            strings.routeSummaryOpenMapHint
        } else {
            strings.routeSummaryIncomplete + strings.routeSummaryOpenMapHint
        },
    )
}

/**
 * The values a Run writes, as the controls currently hold them.
 *
 * Text, not parsed numbers: an emptied box is a state the user can really leave behind, and
 * Run answers it with a documented fallback - which is a change from what was stored even
 * though there is no number to compare. Keeping the typed text also means the snapshot can
 * be taken from the screen, so a value the engine would round differently does not read as
 * a permanent "unsaved change".
 *
 * Deliberately excludes the per-app coordinate table and the language: both are written the
 * moment they are changed, so counting them here would report a pending Run that the user
 * does not need.
 */
internal data class RunSettings(
    val speed: String,
    val accuracy: String,
    val altitude: String,
    val bearing: String,
    val plan: String,
    val loopRoute: Boolean,
    val stepSimulation: Boolean,
    val mockBluetooth: Boolean,
    val bearingHold: Boolean,
    val altitudeDrift: Boolean,
    val accuracyWarmup: Boolean,
    val satelliteJitter: Boolean,
    val signalDropout: Boolean,
    val positionNoise: Boolean,
) {
    /**
     * How many values a Run would write differently from [saved].
     *
     * Numbers are compared by value, so retyping "5.0" as "5" is not a change. A number
     * field Run cannot read counts as one: `numberOr` would replace it with a fallback, and
     * that fallback is not what the last run used.
     */
    fun pendingChanges(saved: RunSettings): Int {
        var count = 0
        if (numberDiffers(speed, saved.speed)) count++
        if (numberDiffers(accuracy, saved.accuracy)) count++
        if (numberDiffers(altitude, saved.altitude)) count++
        if (numberDiffers(bearing, saved.bearing)) count++
        if (plan != saved.plan) count++
        if (loopRoute != saved.loopRoute) count++
        if (stepSimulation != saved.stepSimulation) count++
        if (mockBluetooth != saved.mockBluetooth) count++
        if (bearingHold != saved.bearingHold) count++
        if (altitudeDrift != saved.altitudeDrift) count++
        if (accuracyWarmup != saved.accuracyWarmup) count++
        if (satelliteJitter != saved.satelliteJitter) count++
        if (signalDropout != saved.signalDropout) count++
        if (positionNoise != saved.positionNoise) count++
        return count
    }
}

/**
 * True when two number fields hold different values, or either cannot be read at all.
 *
 * Identical text is equal even when neither side parses: otherwise a blank box compared with
 * the blank box the run itself stored would report a pending change forever. A blank box on
 * one side only still differs, because Run would write a fallback the other side does not
 * have.
 */
internal fun numberDiffers(current: String, saved: String): Boolean {
    if (current.trim() == saved.trim()) return false
    val typed = current.trim().toDoubleOrNull() ?: return true
    val stored = saved.trim().toDoubleOrNull() ?: return true
    return typed != stored
}

/**
 * The label on the Run button.
 *
 * Plain "Run" while the controls match the run that is stored; once they do not, the count
 * that a tap would write, because the settings page holds edits that only Run can save and
 * the page has no Save button of its own.
 */
internal fun runButtonLabel(pendingChanges: Int, strings: Strings): String =
    if (pendingChanges <= 0) {
        strings.startRouteShort
    } else {
        String.format(Locale.US, strings.runButtonWithUnsaved, pendingChanges)
    }

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
    private lateinit var noticeAction: Button

    /**
     * When the current notice should disappear, on [SystemClock.elapsedRealtime]; null means
     * no timer, i.e. it is a failure or a progress line and only its result removes it.
     *
     * A deadline rather than a "time left" field, as the hint suggested: the life belongs to
     * the text, so a trip to the background only has to ask how much of it remains, and
     * "already overdue" cannot be confused with "show it again for a moment". A stored
     * remainder would be a second number able to disagree with the timer that was armed.
     *
     * elapsedRealtime rather than uptime, because the requirement is about time spent in the
     * background: uptime stops during sleep, so a phone left in a pocket would come back
     * still showing a four-second confirmation from hours ago. Handler counts delays on
     * uptime, but the two clocks advance at the same rate while awake, so the difference
     * handed to postDelayed still lands correctly; a sleep in between is caught by the
     * deadline check on the next resume.
     */
    private var noticeDeadlineAtMs: Long? = null

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
    private lateinit var positionNoiseToggle: CheckBox

    private lateinit var routeSummary: TextView

    /**
     * What a Run last wrote, as the controls showed it then.
     *
     * Null only before the pages are built: once the controls exist, the baseline is a real
     * snapshot, first of the values the page was built with and then of what a stored run
     * restored. Without a baseline every control would read as "unsaved", which is noise
     * rather than information.
     */
    private var savedRunSettings: RunSettings? = null

    /**
     * True while [restoreFromStore] is filling the controls.
     *
     * Every restored value fires its own change listener, and the plan editor reports one
     * from inside `load`. Without this the count would be recomputed against the previous
     * baseline halfway through the restore.
     */
    private var restoringSettings = false

    /** The line under the Settings page's note that counts what Run has not written yet. */
    private lateinit var settingsPendingLine: TextView

    /** The pinned Run button; its label carries the number of pending changes. */
    private lateinit var runButton: Button

    /**
     * The route line on the run page.
     *
     * Tappable and coloured like a link, because it is one: it opens the map where the
     * waypoints are actually placed. The route page's own summary is plain text beside an
     * explicit Open-map button, but here there is no button to lean on, so the line has to
     * look like something that can be tapped instead of being silently clickable.
     */
    private lateinit var runPageRouteLine: TextView

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
        // Baseline for a device that has never stored a run: the controls as built are what
        // a Run would write on its first tap. A stored run, if the channel binds, replaces
        // this in restoreFromStore. Without a baseline every control would read as unsaved,
        // which is noise rather than information.
        savedRunSettings = currentRunSettings()
        refreshPendingChanges()
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

        // The line this page would run. First, because it is the one thing a user who just
        // opened the app has to decide about before the speed or the plan matters.
        runPageRouteLine = TextView(this).apply {
            textSize = 15f
            setTextColor(TAB_ACTIVE)
            setPadding(0, 0, 0, 12)
            isClickable = true
            setOnClickListener { openRouteMap() }
        }
        root.addView(runPageRouteLine)

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
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!writingSpeed && !customBtn.isChecked) customBtn.isChecked = true
                    refreshPendingChanges()
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
        planEditor = PlanEditor(this, strings) { wire ->
            planWire = wire
            refreshPendingChanges()
        }
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
        watchPendingChecks(loopToggle, stepToggle, bluetoothToggle)
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

        // The sample route and Clear moved to the map's own control row. Both act on the
        // points, they sit with the other actions that do, and clearing a route is exactly
        // the kind of thing the map's own Clear already asks about - having a second copy
        // here meant two places to look for the same edit.

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
                // and a floating window over an idle subject explains nothing. The callback
                // covers the confirmed start, which returns before the run exists.
                startStraight(onStarted = { showJoystick() })
            },
        )
        recordBtn = action(strings.startRecording) { toggleRecording() }
        root.addView(recordBtn)

        root.addView(section(strings.savedRoutes))
        root.addView(action(strings.saveRoute) { saveCurrentRoute() })
        savedRouteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedRouteBox)
        // The list above lives in the app's private storage, which the user cannot open.
        // This writes copies into Download/Pacer so they can be backed up or moved.
        root.addView(action(strings.exportRoutes) { exportRoutes() })
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

        // A standing note, not a hint under one control: this page holds values that are
        // only written when Run is tapped, and the one Save button far below (coordinate
        // overrides) was the reason every other control looked saved already.
        root.addView(
            TextView(this).apply {
                text = strings.settingsSaveNote
                textSize = 13f
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(8), 0, dp(6))
            },
        )
        settingsPendingLine = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
            visibility = View.GONE
        }
        root.addView(settingsPendingLine)

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
        // Everything on this page that Run writes reports its own edits, so the count is
        // recomputed from the controls rather than remembered from when the page was built.
        watchPendingChanges(accuracyInput, altitudeInput, bearingInput)

        // --- per-app coordinate systems --------------------------------------
        // One plain text field. The table is a preference that applies to whatever
        // package it names, and it is saved on its own rather than only at Start.
        root.addView(section(strings.coordinateSystem))
        coordinateInput = EditText(this).apply {
            hint = strings.coordinateSystemExample
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
        positionNoiseToggle = option(
            root,
            strings.positionNoise,
            strings.positionNoiseHint,
        )
        watchPendingChecks(
            bearingHoldToggle,
            altitudeDriftToggle,
            accuracyWarmupToggle,
            satelliteJitterToggle,
            signalDropoutToggle,
            positionNoiseToggle,
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

    /**
     * Where the route line on the run page sends the user: the route tab, with the map on
     * top of it, because the waypoints are placed in that activity and nothing on the run
     * page can add one.
     */
    private fun openRouteMap() {
        selectPage(Page.ROUTE)
        openMap()
    }

    /** Shows what is actually configured. */
    private fun restoreFromStore() {
        // Filling the controls fires their change listeners. A restore is not the user
        // typing, so the count must not be recomputed halfway through it.
        restoringSettings = true
        try {
            fillFromStore()
        } finally {
            restoringSettings = false
        }
        // The baseline is taken after the controls are filled and settled: what is on
        // screen now is exactly what the stored run holds, so nothing is pending.
        savedRunSettings = currentRunSettings()
        refreshPendingChanges()
    }

    private fun fillFromStore() {
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
        positionNoiseToggle.isChecked = stored.realism.positionNoise
        // No message here any more: "a run is active" is what the state line in the pinned
        // bar says, and it says it continuously instead of once when the page resumed.
        updatePauseButton()
    }

    /**
     * The settings as the controls hold them now.
     *
     * Read from the views rather than from the run: this is the other side of the comparison
     * that decides whether the Run button has anything to say.
     */
    private fun currentRunSettings(): RunSettings = RunSettings(
        speed = speedInput.text.toString(),
        accuracy = accuracyInput.text.toString(),
        altitude = altitudeInput.text.toString(),
        bearing = bearingInput.text.toString(),
        plan = planWire,
        loopRoute = loopToggle.isChecked,
        stepSimulation = stepToggle.isChecked,
        mockBluetooth = bluetoothToggle.isChecked,
        bearingHold = bearingHoldToggle.isChecked,
        altitudeDrift = altitudeDriftToggle.isChecked,
        accuracyWarmup = accuracyWarmupToggle.isChecked,
        satelliteJitter = satelliteJitterToggle.isChecked,
        signalDropout = signalDropoutToggle.isChecked,
        positionNoise = positionNoiseToggle.isChecked,
    )

    /**
     * Recounts the pending changes and puts the number where a user will see it: the note on
     * the Settings page, and the Run button itself, which is the control that applies them.
     *
     * Guarded because the plan editor reports a value from its constructor, before the
     * Settings page - and the fields it reads - exist.
     */
    private fun refreshPendingChanges() {
        if (restoringSettings) return
        if (!::accuracyInput.isInitialized || !::loopToggle.isInitialized) return
        if (!::settingsPendingLine.isInitialized || !::runButton.isInitialized) return
        val pending = pendingChangeCount()
        settingsPendingLine.visibility = if (pending > 0) View.VISIBLE else View.GONE
        settingsPendingLine.text = if (pending > 0) {
            String.format(Locale.US, strings.settingsUnsavedCount, pending)
        } else {
            ""
        }
        runButton.text = runButtonLabel(pending, strings)
        // The button's own text is not read while a content description is set, and the
        // description is the one place that still says which run a tap starts.
        runButton.contentDescription = if (pending > 0) {
            strings.startRoute + " " + String.format(Locale.US, strings.settingsUnsavedCount, pending)
        } else {
            strings.startRoute
        }
    }

    /** How many controls a Run would write differently from the stored run. */
    private fun pendingChangeCount(): Int {
        val saved = savedRunSettings ?: return 0
        return currentRunSettings().pendingChanges(saved)
    }

    /**
     * Adopts what a successful Run just wrote as the new baseline.
     *
     * The baseline is the controls, not the stored keys: the count answers "would a Run write
     * something different", and a switch a run did not consume (a straight run writes no
     * laps) is still what the next tap would send. The stored keys are `restoreFromStore`'s
     * business, not this count's.
     *
     * `lastRestoreStartedAt` moves with it, because this page's own Run is now the run the
     * config holds: without that, the next resume would see a start time it had never seen
     * and overwrite edits typed since with the values it just stored.
     */
    private fun markRunSettingsSaved() {
        savedRunSettings = currentRunSettings()
        PacerConfigClient.stored()?.let { lastRestoreStartedAt = it.startedAtMs }
        refreshPendingChanges()
    }

    /** Recounts on every keystroke in a number field, including cleared ones. */
    private fun watchPendingChanges(vararg fields: EditText) {
        for (field in fields) {
            field.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) = refreshPendingChanges()
                },
            )
        }
    }

    /**
     * Recounts on a switch changing.
     *
     * Kept apart from [watchPendingChanges] because a CheckBox has no text watcher, and a
     * shared name with an overload on a different view type would make the call site read as
     * something that also watches typed text.
     */
    private fun watchPendingChecks(vararg boxes: CheckBox) {
        for (box in boxes) {
            box.setOnCheckedChangeListener { _, _ -> refreshPendingChanges() }
        }
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
            // A route advances on its own clock, which a pause freezes; a straight run keeps
            // counting from its start even while steering moves the line's anchor.
            val now = System.currentTimeMillis()
            val seconds = runElapsedSeconds(
                onRoute = stored.onRoute,
                startedAtMs = stored.startedAtMs,
                anchorAtMs = stored.anchorAtMs,
                routeOffsetMs = stored.routeOffsetMs,
                routeResumedAtMs = stored.routeResumedAtMs,
                routePaused = stored.routePaused,
                nowMs = now,
            )
            // One sample for both the position and the speed: point() would take a second one,
            // and the two numbers on the same line must describe the same instant.
            val sample = subject.sample(stored, now)
            val where = sample?.point
                ?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }
                ?: String.format(Locale.US, "%.5f, %.5f", stored.lat, stored.lon)
            statusLineText(
                strings = strings,
                label = label,
                elapsedSeconds = seconds,
                where = where,
                speedMps = reportedSpeedMps(
                    sampleMps = sample?.speedMps,
                    configuredMps = stored.speed,
                    paused = paused,
                ),
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
    private fun setNotice(
        text: String,
        kind: NoticeKind = NoticeKind.INFO,
        action: NoticeAction? = null,
    ) {
        if (!::noticeStrip.isInitialized) return
        showNotice(text, kind, action)
    }

    private fun showNotice(text: String, kind: NoticeKind, action: NoticeAction? = null) {
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
        // The action is offered only by the caller that has somewhere to send the user, so a
        // plain failure must not keep the previous one's button or its old target.
        noticeAction.visibility = if (action != null) View.VISIBLE else View.GONE
        noticeAction.text = action?.label.orEmpty()
        noticeAction.setOnClickListener(null)
        if (action != null) {
            noticeAction.setOnClickListener {
                // The notice describes a refusal that the action answers, so it goes with the
                // tap: coming back from the map to "there is no route" would be a stale claim.
                hideNotice()
                action.onChosen()
            }
        }
        // A failure is the one thing worth interrupting a screen reader for; the rest can
        // wait for the reader to reach them.
        noticeStrip.accessibilityLiveRegion = if (kind == NoticeKind.FAILURE) {
            View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        } else {
            View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val ms = noticeDurationMs(text, kind)
        noticeDeadlineAtMs = noticeDeadlineMs(SystemClock.elapsedRealtime(), ms)
        if (ms > 0L) handler.postDelayed(hideNoticeTask, ms)
    }

    /** Hides the strip; the text goes too so a screen reader cannot still find it. */
    private fun hideNotice() {
        if (!::noticeStrip.isInitialized) return
        noticeDeadlineAtMs = null
        noticeStrip.visibility = View.GONE
        noticeText.text = ""
        // The action belongs to the text above it; leaving it armed would let a stale "Open
        // the map" fire from a strip the user has already dismissed.
        noticeAction.visibility = View.GONE
        noticeAction.setOnClickListener(null)
    }

    /**
     * Removes the floating joystick, if any.
     *
     * Only the activity knows that the user asked to stop; the service only hears about it
     * when its own knob is tapped. Without this the overlay outlives the run it steers and
     * keeps a foreground notification for something that is no longer happening.
     *
     * Deliberately not called from onPause: a paused run resumes, and the overlay is still
     * the control for it.
     */
    private fun hideJoystick() {
        stopService(Intent(this, JoystickOverlayService::class.java))
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
        // Between the sentence and dismiss, and only when the caller offered one: the strip
        // is the one place a failure can put the next step next to the reason it was refused.
        noticeAction = Button(this).apply {
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setTextColor(NOTICE_TEXT_COLOR)
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
        }
        noticeStrip.addView(noticeAction)
        noticeDismiss = Button(this).apply {
            text = strings.dismissNotice
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            // The strip is a dark panel in every notice kind, so the label has to be the
            // strip's text colour rather than the framework's dark-on-light default.
            setTextColor(NOTICE_TEXT_COLOR)
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
        val changedElsewhere = stored != null && stored.startedAtMs != lastRestoreStartedAt
        // Never re-fill the controls over edits a Run has not written yet: losing them on a
        // resume is the exact loss this page now warns about. The count stays on screen
        // saying they are still waiting, which is the truth about that run.
        if (changedElsewhere && pendingChangeCount() == 0) restoreFromStore()
        // A pause toggled on the other screen changes nothing this page adopts, so the
        // control has to be refreshed on its own.
        updatePauseButton()
        updateStatus()
        resumeNoticeTimer()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
    }

    /**
     * Restarts the strip's timer with what is left of the notice's original life.
     *
     * Without this the callbacks removed in onPause are never re-armed, so an INFO notice
     * with no dismiss button stays over the page for the rest of the session. Deadline
     * arithmetic makes the answer independent of how long the page was away: an overdue
     * notice is hidden now, never shown again for another few seconds.
     */
    private fun resumeNoticeTimer() {
        handler.removeCallbacks(hideNoticeTask)
        val remaining = noticeRemainingMs(SystemClock.elapsedRealtime(), noticeDeadlineAtMs) ?: return
        if (remaining <= 0L) {
            hideNotice()
        } else {
            handler.postDelayed(hideNoticeTask, remaining)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTick)
        // A recording cannot outlive the page that owns the draft: the tick would keep
        // collecting points nobody can see or stop. Ending it here keeps the promise the
        // button makes - what was walked is what gets saved.
        //
        // Before the notice timer is cancelled, because finishing writes a notice of its own
        // and that one must not be armed to fire while the page is behind another one.
        recorder?.let { finishRecording(it) }
        // The strip's timer must not fire while the page is behind another one; it would
        // hide a message the user has not seen yet on their way back. The deadline survives,
        // so onResume resumes the same life instead of restarting it.
        handler.removeCallbacks(hideNoticeTask)
    }

    // ---------------------------------------------------------------------
    // Route editing
    // ---------------------------------------------------------------------

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
            setNotice(strings.routeNeedsTwoPoints)
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
            .setTitle(
                if (existing != null) {
                    String.format(Locale.US, strings.saveOverTitle, existing.name)
                } else {
                    strings.saveRouteTitle
                },
            )
            .setView(field)
            .setPositiveButton(strings.saveConfirm) { _, _ ->
                commitSave(field.text.toString(), existing?.id)
            }
            .setNegativeButton(strings.cancel, null)
            // "Save as new" exists because the only other way to avoid overwriting a
            // loaded route would be to edit it first, which is not an answer.
            .setNeutralButton(strings.saveAsNew) { _, _ ->
                commitSave(field.text.toString(), null)
            }
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
                // The engine's own reason is English, and this notice is on screen in
                // whichever language the user picked; the two cases it covers (blank, too
                // long) are one sentence here.
                setNotice(
                    String.format(
                        Locale.US,
                        strings.nameRequiredWithLimit,
                        SavedRoutes.MAX_NAME_LENGTH,
                    ),
                    NoticeKind.FAILURE,
                )
            is SavedRoutes.SaveResult.InvalidRoute ->
                setNotice(strings.routeNeedsTwoPoints, NoticeKind.FAILURE)
        }
    }

    /** Exports every saved route, and the current draft, into Download/Pacer. */
    private fun exportRoutes() {
        val routes = store.savedRoutes().routes
        val draftPoints = if (draft.size >= 2) RouteJson.encode(draft.points) else null
        if (routes.isEmpty() && draftPoints == null) {
            setNotice(strings.nothingToExport, NoticeKind.FAILURE)
            return
        }
        val draftName = strings.currentDraftFileName
        // These are small files, but they are still file writes; keeping them off the UI
        // thread is the same rule every other write in this app follows. thread() starts the
        // thread itself, so naming it as an argument is the only correct form here.
        thread(name = "pacer-route-export", isDaemon = true) {
            val failures = ArrayList<String>()
            var written = 0
            for (route in routes) {
                when (val result = RouteExport.write(this@MainActivity, route.name, route.toJson())) {
                    is RouteExport.Result.Written -> written++
                    is RouteExport.Result.Failed -> failures += "${route.name}: ${result.reason}"
                }
            }
            if (draftPoints != null) {
                val result = RouteExport.write(this@MainActivity, draftName, draftPoints)
                when (result) {
                    is RouteExport.Result.Written -> written++
                    is RouteExport.Result.Failed -> failures += "${draftName}: ${result.reason}"
                }
            }
            val count = written
            val problem = failures.firstOrNull()
            runOnUiThread {
                if (problem != null) {
                    setNotice(
                        String.format(Locale.US, strings.exportFailed, count, problem),
                        NoticeKind.FAILURE,
                    )
                } else {
                    setNotice(String.format(Locale.US, strings.exported, count, RouteExport.FOLDER))
                }
            }
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
                    strings.savedRouteRow,
                    route.name,
                    route.points.size,
                    route.lengthMeters,
                    if (route.id == currentId) strings.savedRouteCurrentMark else "",
                )
                textSize = 14f
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(smallAction(strings.loadConfirm) { loadSavedRoute(route.id) })
            row.addView(smallAction(strings.renameConfirm) { renameSavedRoute(route.id) })
            row.addView(smallAction(strings.deleteShort) { deleteSavedRoute(route.id) })
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
            .setTitle(strings.loadRouteTitle)
            .setMessage(
                String.format(Locale.US, strings.loadRouteMessage, route.name, draft.size),
            )
            .setPositiveButton(strings.loadConfirm) { _, _ -> apply() }
            .setNegativeButton(strings.cancel, null)
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
            .setTitle(strings.renameRouteTitle)
            .setView(field)
            .setPositiveButton(strings.renameConfirm) { _, _ ->
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
            .setNegativeButton(strings.cancel, null)
            .show()
    }

    private fun deleteSavedRoute(id: Long) {
        val route = store.savedRoutes().find(id) ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle(String.format(Locale.US, strings.deleteRouteTitle, route.name))
            .setMessage(strings.deleteRouteMessage)
            .setPositiveButton(strings.deleteConfirm) { _, _ ->
                val routes = store.savedRoutes()
                routes.remove(id)
                store.saveNamedRoutes(routes)
                // The draft stays as it is: deleting the saved copy of a route is not a
                // request to make the map forget what is drawn on it.
                renderSavedRoutes()
                setNotice(strings.deleted)
            }
            .setNegativeButton(strings.cancel, null)
            .show()
    }

    /**
     * The route in one line.
     *
     * The points are placed, dragged and removed on the map, so there is no per-point list
     * here: a second list that also edits them would duplicate the map. What is left is the
     * one thing the map cannot say at a glance - how long the route is.
     *
     * The run page shows the same line, because starting is done there and the route is
     * drawn on the other page; both are refreshed from here so they cannot disagree.
     */
    private fun renderRoute() {
        routeSummary.text = String.format(
            Locale.US,
            strings.routeSummary,
            draft.size,
            draft.totalLengthMeters(),
            if (draft.isValid) "" else strings.routeSummaryIncomplete,
        )
        if (::runPageRouteLine.isInitialized) {
            runPageRouteLine.text = runPageRouteSummary(
                waypointCount = draft.size,
                lengthMeters = draft.totalLengthMeters(),
                complete = draft.isValid,
                strings = strings,
            )
        }
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
     * @param onStarted Runs only on the success path. A deferred confirmation returns before
     *   the run exists, so a caller that needs to react to the start cannot rely on the
     *   return value alone; this is invoked for both the immediate and the confirmed start.
     * @return true when a run was actually written, so the caller knows whether showing the
     *   joystick now would point at something real. Every refusal path returns false.
     */
    private fun startStraight(confirmed: Boolean = false, onStarted: () -> Unit = {}): Boolean {
        if (!confirmed && PacerConfigClient.owner() != MotionOwner.NONE) {
            confirmReplace { startStraight(true, onStarted) }
            return false
        }
        val start = draft.points.firstOrNull()
        if (start == null) {
            setNotice(strings.placeWaypointFirst)
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
        // The stored run now holds what the controls hold, so nothing is pending any more.
        // After the start, because only a start that actually wrote may clear the count.
        markRunSettingsSaved()
        // Last, so the joystick's own notice displaces the start notice rather than being
        // displaced by it. The immediate path kept the same order, because the caller ran
        // showJoystick() only after this had returned true.
        onStarted()
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
        return String.format(Locale.US, strings.planNotice, "$pass$total")
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
        positionNoise = positionNoiseToggle.isChecked,
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
            strings.recordingStatus,
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
            setNotice(strings.planInvalid)
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
            // Two different next steps, so two sentences. Neither reuses needTwoWaypoints:
            // that one is shared with the audit and the preview, which really do need a
            // polyline and must not suggest that the joystick would do instead.
            setNotice(
                if (draft.size == 1) {
                    strings.startNeedsAnotherWaypoint
                } else {
                    strings.startNeedsRouteOnMap
                },
                NoticeKind.FAILURE,
                NoticeAction(strings.openMapAction) { openRouteMap() },
            )
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
                    planSummary(plan),
                )
            },
        )
        updatePauseButton()
        // Same rule as a joystick start: only a start that wrote clears the pending count.
        markRunSettingsSaved()
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
            setNotice(strings.needTwoWaypoints)
            return
        }
        // A plan the editor rejects would silently audit a steady-speed run instead.
        if (!planEditor.isValid) {
            setNotice(strings.planInvalid)
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
            setNotice(strings.needTwoWaypoints)
            return
        }
        // Same rule as Start: a plan the editor rejects must not be previewed as a steady run.
        if (!planEditor.isValid) {
            setNotice(strings.planInvalid)
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
        // Named after what a tap does, including the changes it would write: the Settings
        // page holds edits that only this button saves, and it has no Save of its own.
        runButton = Button(this).apply {
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
        }
        bar.addView(runButton)

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
                } else {
                    hideJoystick()
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
    }
}
