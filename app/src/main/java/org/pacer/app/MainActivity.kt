package org.pacer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

/**
 * Control surface for everything that is not the map: speed, the signal switches, and the
 * two ways to start a run.
 *
 * The route is drawn on the map, not here: there is no coordinate field and no place search,
 * both of which would need a key or an account.
 *
 * Plain framework views on purpose: the engine is pure Kotlin, and only the map needs a
 * canvas.
 */
class MainActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft

    private lateinit var settings: AppSettings
    private lateinit var strings: Strings

    /** startedAtMs of the run whose settings this page last adopted. */
    private var lastRestoreStartedAt = Long.MIN_VALUE

    private lateinit var output: TextView

    /** The run's state, at the top of the page; see the call site. */
    private lateinit var statusView: TextView

    /**
     * The last failure or consequence, above the status line.
     *
     * Held here so the per-second status refresh cannot overwrite it.
     */
    private var notice: String? = null

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
    private lateinit var waypointBox: LinearLayout

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

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 40, 40, 40)

        root.addView(titleView("Pacer"))
        root.addView(hint(strings.appTagline))

        // The run's state, at the top rather than at the bottom of a long column: a Start
        // that changes nothing visible reads as a button that did nothing. Refreshed on a
        // timer, so it also reports a run stopped from the map page.
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 8, 0, 16)
        }
        root.addView(statusView)

        // --- speed preset ----------------------------------------------------
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
        speedInput = numberField(strings.customSpeed, "3.0")
        // Typing a speed is choosing Custom. The guard stops the radio listener's own
        // write from being read back as typing.
        var writingSpeed = false
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
        root.addView(speedInput)

        // --- route ----------------------------------------------------------
        // The route is drawn on the map; this screen only shows what was drawn. There is
        // deliberately no coordinate field and no search box: neither needs a key, but both
        // are a way of choosing a place other than by looking at it.
        root.addView(section(strings.route))
        root.addView(hint(strings.routeHint))
        root.addView(action(strings.openMap) {
            startActivity(Intent(this, MapActivity::class.java))
        })

        routeSummary = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 8) }
        root.addView(routeSummary)

        waypointBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(waypointBox)

        root.addView(action(strings.loadSample) { loadSampleRoute() })
        root.addView(action(strings.clearRoute) {
            if (draft.size < 2) {
                draft.clear()
                persistAndRender()
                renderSavedRoutes()
            } else {
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
        })
        recordBtn = action(strings.startRecording) { toggleRecording() }
        root.addView(recordBtn)

        // --- named routes -----------------------------------------------------
        // Plain buttons and plain rows. The list is the only structure a saved route
        // needs; anything more would be decoration.
        root.addView(section(strings.savedRoutes))
        root.addView(action(strings.saveRoute) { saveCurrentRoute() })
        savedRouteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedRouteBox)

        // --- simulation ------------------------------------------------------
        root.addView(section(strings.simulation))
        accuracyInput = numberField(strings.accuracy, "5.0")
        root.addView(accuracyInput)
        altitudeInput = numberField(strings.altitude, "0.0")
        root.addView(altitudeInput)
        bearingInput = numberField(strings.bearing, "45.0")
        root.addView(bearingInput)
        // The interval plan as steps with steppers and presets. The stored value is still
        // the `3.0@180;1.4@60#5` string; only the input form changed.
        root.addView(section(strings.intervalPlanSection))
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

        // --- per-app coordinate systems --------------------------------------
        // One plain text field. The table is a preference that applies to whatever
        // package it names, and it is saved on its own rather than only at Start.
        root.addView(section(strings.coordinateSystem))
        coordinateInput = EditText(this).apply {
            hint = "com.example.app=GCJ02;com.other.app=WGS84"
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

        root.addView(action(strings.startStraight) { startStraight() })
        root.addView(action(strings.startRoute) { startRoute() })
        pauseBtn = action(strings.pauseRoute) { toggleRoutePause() }
        root.addView(pauseBtn)
        root.addView(action(strings.stopSimulation) {
            if (PacerConfigClient.stop()) {
                setNotice(strings.statusConfigIdle)
            } else {
                setNotice(PacerConfigClient.NOT_READY_MESSAGE)
            }
            updatePauseButton()
        })
        root.addView(action(strings.showJoystick) { showJoystick() })

        previewBtn = action(strings.preview) { previewLocally() }
        root.addView(previewBtn)

        auditBtn = action(strings.audit) { auditRoute() }
        root.addView(auditBtn)

        // --- diagnostics ------------------------------------------------------
        // Plain text in a plain box. The sheet is for reading and for pasting into a
        // chat, not for looking at, so there is no styling to keep in step with the
        // rest of the page.
        root.addView(section(strings.selfCheck))
        root.addView(action(strings.runSelfCheck) { runSelfCheck() })
        root.addView(hint(strings.selfCheckHint))
        selfCheckOutput = TextView(this).apply {
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

        output = TextView(this).apply {
            text = strings.statusIdle
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })

        PacerConfigClient.init()
        renderRoute()
        renderSavedRoutes()
        updatePauseButton()
        // Opens on the stored configuration, not on this screen's defaults: every start
        // writes all of these keys, so touching one switch on defaults would silently
        // restore the others.
        PacerConfigClient.onReady { runOnUiThread { restoreFromStore() } }
    }

    /** Shows what is actually configured. */
    private fun restoreFromStore() {
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
        coordinateInput.setText(stored.coordinateOverrides)
        loopToggle.isChecked = stored.loopRoute
        bearingHoldToggle.isChecked = stored.realism.bearingHold
        altitudeDriftToggle.isChecked = stored.realism.altitudeDrift
        accuracyWarmupToggle.isChecked = stored.realism.accuracyWarmup
        satelliteJitterToggle.isChecked = stored.realism.satelliteJitter
        signalDropoutToggle.isChecked = stored.realism.signalDropout
        output.text = when (stored.owner) {
            MotionOwner.ROUTE -> "Loaded the stored configuration: a route simulation is running."
            MotionOwner.ROUTE_PAUSED -> "Loaded the stored configuration: a route simulation is " +
                "running, paused where it stands."
            MotionOwner.PLAN -> "Loaded the stored configuration: a straight-line run with an " +
                "interval plan is running."
            MotionOwner.HAND -> "Loaded the stored configuration: a hand-steered straight-line " +
                "simulation is running."
            MotionOwner.NONE -> "Loaded the stored configuration; no simulation is running."
        }
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
            setNotice(strings.onlyRunningRouteCanPause)
            updatePauseButton()
            return
        }
        if (!PacerConfigClient.setRoutePaused(pause)) {
            setNotice(PacerConfigClient.NOT_READY_MESSAGE)
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

    /** Shows the pause control only when it would do something. */
    private fun updatePauseButton() {
        if (!::pauseBtn.isInitialized) return
        val owner = PacerConfigClient.owner()
        pauseBtn.visibility = if (owner.canPauseRoute || owner.canResumeRoute) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        pauseBtn.text = if (owner.canResumeRoute) "Resume route" else "Pause route"
    }

    private fun updateStatus() {
        val stored = PacerConfigClient.stored()
        val state = when {
            stored == null -> strings.statusIdle
            !stored.active -> strings.statusConfigIdle
            stored.routePaused -> strings.statusPaused
            stored.onRoute -> strings.statusRunning
            else -> strings.statusRunning
        }
        val detail = when {
            stored == null || !stored.active -> ""
            else -> {
                val seconds = stored.routeSeconds(System.currentTimeMillis()).toLong()
                val point = subject.point(stored)
                val where = point?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }
                    ?: String.format(Locale.US, "%.5f, %.5f", stored.lat, stored.lon)
                String.format(
                    Locale.US,
                    "%d:%02d   %s   %.1f m/s",
                    seconds / 60,
                    seconds % 60,
                    where,
                    stored.speed,
                )
            }
        }
        statusView.text = buildString {
            val n = notice
            if (n != null) {
                appendLine(n)
            }
            append(state)
            if (detail.isNotEmpty()) {
                append('\n')
                append(detail)
            }
        }
    }

    /** Writes a consequence where the user is looking, not at the bottom of the page. */
    private fun setNotice(text: String?) {
        notice = text
        updateStatus()
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
            output.text = PacerConfigClient.NOT_READY_MESSAGE
            return
        }
        val dropped = typed.split(';').count { it.isNotBlank() } - parsed.size
        output.text = when {
            parsed.isEmpty() && typed.isBlank() -> "Coordinate overrides cleared; AUTO applies."
            parsed.isEmpty() -> "No valid entry in that text; nothing was saved."
            dropped > 0 -> "Saved ${parsed.size} override(s); $dropped entr(y/ies) were unreadable " +
                "and are shown dropped."
            else -> "Saved ${parsed.size} coordinate override(s)."
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
                output.text = if (result.replaced) {
                    "Replaced \"${result.route.name}\"."
                } else {
                    "Saved \"${result.route.name}\" (${result.route.points.size} waypoints, " +
                        String.format(Locale.US, "%.0f m", result.route.lengthMeters) + ")."
                }
                renderSavedRoutes()
            }
            is SavedRoutes.SaveResult.InvalidName -> output.text = result.reason
            is SavedRoutes.SaveResult.InvalidRoute -> output.text = result.reason
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
                setNotice(strings.routeGone)
            } else {
                draft = loaded
                persistAndRender()
                renderSavedRoutes()
                output.text =
                    "\"${route.name}\" (${route.points.size} ${strings.waypointCountSuffix})"
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
                    output.text =
                        "${strings.nameRequired} (${SavedRoutes.MAX_NAME_LENGTH})"
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

    private fun renderRoute() {
        routeSummary.text = String.format(
            Locale.US,
            "Waypoints: %d   Length: %.0f m%s",
            draft.size,
            draft.totalLengthMeters(),
            if (draft.isValid) "" else "   (need two distinct points to run a route)",
        )

        waypointBox.removeAllViews()
        draft.points.forEachIndexed { index, point ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = String.format(Locale.US, "%d.  %.5f, %.5f", index + 1, point.lat, point.lon)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            row.addView(smallAction("^") { if (draft.moveUp(index)) persistAndRender() })
            row.addView(smallAction("v") { if (draft.moveDown(index)) persistAndRender() })
            row.addView(smallAction("x") { draft.removeAt(index); persistAndRender() })
            waypointBox.addView(row)
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

    private fun startStraight(confirmed: Boolean = false) {
        if (!confirmed && PacerConfigClient.owner() != MotionOwner.NONE) {
            confirmReplace { startStraight(true) }
            return
        }
        val start = draft.points.firstOrNull()
        if (start == null) {
            setNotice(strings.placeWaypointFirst)
            return
        }
        val lat = start.lat
        val lon = start.lon
        val speed = speedMps()
        val plan = planWire.takeIf { planEditor.isValid } ?: run {
            setNotice(strings.badPlan)
            return
        }
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
            setNotice(PacerConfigClient.NOT_READY_MESSAGE)
            return
        }
        setNotice(
            if (plan.isBlank()) {
                String.format(Locale.US, "Straight line pushed: %.5f, %.5f at %.2f m/s.", lat, lon, speed)
            } else {
                // The plan overrides the speed field, so quoting only the field would
                // describe a run that is not happening.
                String.format(Locale.US, "Straight line pushed: %.5f, %.5f, speed from the plan.", lat, lon) +
                    planSummary(plan)
            },
        )
        updatePauseButton()
    }

    /**
     * A line about the interval plan that is about to run, using the plan's own arithmetic.
     */
    private fun planSummary(plan: String): String {
        val parsed = SpeedPlan.parse(plan) ?: return ""
        val pass = String.format(Locale.US, "%.0f s / %.0f m per pass", parsed.passSeconds, parsed.passMeters)
        val total = parsed.totalMeters?.let { String.format(Locale.US, ", %.0f m in total", it) }
            ?: ", repeating for the whole route"
        return " Plan: $pass$total."
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
                output.text = when (owner) {
                    MotionOwner.ROUTE, MotionOwner.ROUTE_PAUSED ->
                        "Recording follows the joystick, and a route run owns its own motion. " +
                            "Stop the route and start a hand-steered straight line to record one."
                    MotionOwner.PLAN ->
                        "Recording follows the joystick, but the interval plan owns the speed. " +
                            "Clear the plan and start a straight line to record."
                    else -> "Start a hand-steered simulation first, then record it."
                }
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
            setNotice(strings.nothingRecorded)
            return
        }
        draft = recording.toDraft()
        persistAndRender()
        renderSavedRoutes()
        output.text = String.format(
            Locale.US,
            "Recorded %d waypoints over %.0f m in %d s. It is in the editor now.",
            recording.size,
            recording.distanceMeters,
            ((System.currentTimeMillis() - recordStartedAtMs) / 1000L).toInt(),
        )
    }

    /** A numeric field's value, or [fallback] when it is empty or unreadable. */
    private fun numberOr(field: EditText, fallback: Float): Float =
        field.text.toString().toFloatOrNull() ?: fallback

    private fun doubleOr(field: EditText, fallback: Double): Double =
        field.text.toString().toDoubleOrNull() ?: fallback

    private fun startRoute(confirmed: Boolean = false) {
        if (!confirmed && PacerConfigClient.owner() != MotionOwner.NONE) {
            confirmReplace { startRoute(true) }
            return
        }
        if (!draft.isValid) {
            setNotice(strings.needTwoWaypoints)
            return
        }
        // The road path when one was planned on the map screen, the plain polyline otherwise.
        val geometry = store.geometry(draft)
        val route = RoutePath(geometry)
        val first = route.points.first()
        val speed = speedMps()
        val plan = planWire.takeIf { planEditor.isValid } ?: run {
            setNotice(strings.badPlan)
            return
        }
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
            setNotice(PacerConfigClient.NOT_READY_MESSAGE)
            return
        }
        setNotice(
            if (plan.isBlank()) {
                String.format(
                    Locale.US,
                    "Route pushed: %d points, %.0f m, %.2f m/s. The engine humanizes speed along it.",
                    geometry.size,
                    route.totalLengthMeters,
                    speed,
                )
            } else {
                String.format(
                    Locale.US,
                    "Route pushed: %d points, %.0f m; speed follows the plan on top of the base %.2f m/s.",
                    geometry.size,
                    route.totalLengthMeters,
                    speed,
                ) + planSummary(plan)
            },
        )
        updatePauseButton()
    }

    private fun showJoystick() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, JoystickOverlayService::class.java))
            // Saying which input owns the run here is cheaper than letting the user find
            // out by dragging the knob.
            output.text = when (PacerConfigClient.owner()) {
                MotionOwner.ROUTE ->
                    "Joystick shown. A route run supplies its own speed and heading, so the knob " +
                        "is locked until the route is stopped. Drag the ring to move it, tap to stop."
                MotionOwner.ROUTE_PAUSED ->
                    "Joystick shown. The route is paused, so the subject stands still; the knob " +
                        "unlocks only if you stop the route. Drag the ring to move it, tap to stop."
                MotionOwner.PLAN ->
                    "Joystick shown. The interval plan supplies the speed, so the knob is locked " +
                        "until the plan run is stopped. Drag the ring to move it, tap to stop."
                else ->
                    "Joystick shown. Drag the knob to steer (releasing keeps the last " +
                        "heading), drag the ring to move it, tap to stop."
            }
        } else {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            setNotice(strings.grantOverlay)
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
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        // The preview follows the interval plan too, or it would show a steady-speed track
        // for a session that will not be one.
        val plan = SpeedPlan.parse(planWire)
        val speed = speedMps()
        // Two different runs of the same route, so the divergence below is meaningful.
        // Deterministic rather than Random, so a report can be reproduced from its numbers.
        val seedBase = System.currentTimeMillis()
        auditBtn.isEnabled = false
        setNotice(strings.auditing)
        thread {
            fun runOnce(seed: Long) = RouteSimulator(
                route,
                MotionConfig(baseSpeedMps = speed).copy(plan = plan),
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
                output.text = text
                auditBtn.isEnabled = true
            }
        }.also { worker ->
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
                runOnUiThread {
                    auditBtn.isEnabled = true
                    setNotice("${t.javaClass.simpleName}: ${t.message}")
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
                    selfCheckOutput.text = "Self-check crashed: ${t.javaClass.simpleName}: ${t.message}"
                }
                return@thread
            }
            val text = report.copyText()
            runOnUiThread {
                selfCheckOutput.text = text
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Pacer self-check", text))
                output.text = "Self-check: ${report.summaryLine()} (report copied to the clipboard)"
            }
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
        previewBtn.isEnabled = false
        setNotice(strings.simulating)
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        val plan = SpeedPlan.parse(planWire)
        val speed = speedMps()
        thread {
            val route = previewRoute
            val fixes = RouteSimulator(
                route,
                MotionConfig(baseSpeedMps = speed).copy(plan = plan),
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
                output.text = text
                previewBtn.isEnabled = true
            }
        }.also { worker ->
            // A throw would otherwise leave the button greyed out for the rest of the
            // session, frozen on "Simulating..."
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t ->
                runOnUiThread {
                    previewBtn.isEnabled = true
                    setNotice("${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    // The view builders live in Ui.kt: three screens share them, and a per-screen copy is
    // how one page ends up with a 12 sp hint and another with 13 sp.

    private companion object {
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
