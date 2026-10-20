package org.pacer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
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
 * THE ROUTE IS BUILT ON THE MAP, NOT HERE. There is no coordinate field, no paste parser and
 * no place search: an API key, an account and a geocoding service are all things this app
 * deliberately does not have, and typing a coordinate is not how a route is chosen anyway.
 * The map page is where a route is drawn, by panning to a place and tapping it; this screen
 * lists what was drawn so it can be reordered or removed, and starts the run.
 *
 * Plain framework views on purpose: the engine is pure Kotlin, and the only thing that needs
 * a canvas is the map.
 */
class MainActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft

    /** startedAtMs of the run whose settings this page last adopted. */
    private var lastRestoreStartedAt = Long.MIN_VALUE

    private lateinit var output: TextView

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
    private lateinit var planInput: EditText

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

    /** The saved route currently loaded, so saving again replaces it instead of forking. */
    /**
     * The saved route whose geometry matches the draft exactly, if any.
     *
     * Derived from the content rather than from a "loaded this id" flag. Every edit path
     * - tapping the map, undoing, recording, clearing, reloading a draft from the store -
     * would otherwise have to remember to reset that flag, and one missed reset makes the
     * next save silently overwrite an unrelated route. Comparing the points has no such
     * failure mode: the draft is the saved route exactly when it is the same geometry.
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
     * The recording in progress, or null when nothing is being recorded.
     *
     * Held here rather than in the overlay service: this page is the only thing that
     * writes the draft, and a recording that survived the page going away would keep
     * collecting points nobody could stop except by returning to it.
     */
    private var recorder: RouteRecorder? = null
    private var recordStartedAtMs = 0L

    /**
     * The sampling tick.
     *
     * Main thread on purpose: it reads the stored config and writes the draft, and both
     * are cheap. A background thread would have to marshal every point back to this one
     * anyway, since the draft is a view model of the list on screen.
     */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val subject = LiveSubject()
    private val recordTick = object : Runnable {
        override fun run() {
            sampleRecording()
            if (recorder != null) handler.postDelayed(this, RECORD_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = RouteStore(this)
        draft = store.load()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 40, 40, 40)

        root.addView(titleView("Pacer"))
        root.addView(hint("Simulate movement for testing. Requires the Pacer LSPosed module in scope."))

        // --- speed preset ----------------------------------------------------
        root.addView(section("Speed"))
        val speedGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        walkBtn = radio("Walk 1.4")
        runBtn = radio("Run 3.0")
        rideBtn = radio("Ride 6.0")
        customBtn = radio("Custom")
        speedGroup.addView(walkBtn)
        speedGroup.addView(runBtn)
        speedGroup.addView(rideBtn)
        speedGroup.addView(customBtn)
        runBtn.isChecked = true
        root.addView(speedGroup)
        speedInput = numberField("Custom speed m/s", "3.0")
        root.addView(speedInput)

        // --- route ----------------------------------------------------------
        // The route is drawn on the map; this screen only shows what was drawn. There is
        // deliberately no coordinate field and no search box: neither needs a key, but both
        // are a way of choosing a place other than by looking at it.
        root.addView(section("Route"))
        root.addView(
            hint(
                "Waypoints are placed on the map: pan to a place, tap to append one, drag a " +
                    "handle to move it. This list is what the map has drawn.",
            ),
        )
        root.addView(action("Open the map and controls") {
            startActivity(Intent(this, MapActivity::class.java))
        })

        routeSummary = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 8) }
        root.addView(routeSummary)

        waypointBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(waypointBox)

        root.addView(action("Load sample route (Urumqi)") { loadSampleRoute() })
        root.addView(action("Clear route") {
            draft.clear()
            persistAndRender()
            renderSavedRoutes()
        })
        recordBtn = action("Start recording from the joystick") { toggleRecording() }
        root.addView(recordBtn)

        // --- named routes -----------------------------------------------------
        // Plain buttons and plain rows. The list is the only structure a saved route
        // needs; anything more would be decoration.
        root.addView(section("Saved routes"))
        root.addView(action("Save current route as...") { saveCurrentRoute() })
        savedRouteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(savedRouteBox)

        // --- simulation ------------------------------------------------------
        root.addView(section("Simulation"))
        accuracyInput = numberField("Steady accuracy (m)", "5.0")
        root.addView(accuracyInput)
        altitudeInput = numberField("Start altitude (m)", "0.0")
        root.addView(altitudeInput)
        bearingInput = numberField("Straight-line bearing (deg, 0 = north)", "45.0")
        root.addView(bearingInput)
        planInput = EditText(this).apply {
            hint = "Interval plan (blank = steady speed)"
            setText("")
        }
        root.addView(planInput)
        root.addView(
            hint(
                "Interval plan, for example \"3.0@180;1.4@60#5\": speed in m/s, @, seconds, " +
                    "\";\" between steps, \"#N\" repeats (0 = forever). Works on a route and " +
                    "on the straight line; the reported speed follows it. While a plan is set, " +
                    "the plan owns the speed and the floating joystick cannot change it, so " +
                    "clear this field to steer by hand.",
            ),
        )
        loopToggle = option(
            root,
            "Repeat the route (laps)",
            "A route run starts over at the first waypoint instead of stopping at the end. " +
                "Distance and steps keep growing across laps, and the whole track is humanized " +
                "again each lap, so no two laps are identical.",
            checked = false,
        )
        stepToggle = option(
            root,
            "Step and sensor simulation",
            "The step counter, the step detector and the accelerometer follow the route.",
        )
        bluetoothToggle = option(
            root,
            "Simulate the Bluetooth surroundings",
            "Without this, Bluetooth scans still report the real surroundings while WiFi and cells " +
                "are simulated. It also hides paired devices for the apps in scope, so switch it off " +
                "while pairing hardware.",
        )

        // --- per-app coordinate systems --------------------------------------
        // One plain text field. The table is a preference that applies to whatever
        // package it names, and it is saved on its own rather than only at Start.
        root.addView(section("Per-app coordinate system"))
        coordinateInput = EditText(this).apply {
            hint = "com.example.app=GCJ02;com.other.app=WGS84"
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        }
        root.addView(coordinateInput)
        root.addView(
            hint(
                "A fix carries no datum, so an app that draws it on a Chinese base map " +
                    "assumes GCJ-02 while one that draws it on a Google base map assumes " +
                    "WGS-84; the two differ by about half a kilometre. AUTO follows the " +
                    "API being hooked. This table overrides that per package; semicolons " +
                    "separate entries.",
            ),
        )
        root.addView(action("Save coordinate overrides") { saveCoordinateOverrides() })

        // --- signal realism --------------------------------------------------
        root.addView(section("Signal realism"))
        root.addView(
            hint(
                "Each of these makes the track look less machine-made. Switch one off to keep that " +
                    "part of the signal steady.",
            ),
        )
        bearingHoldToggle = option(
            root,
            "Hold the heading while standing still",
            "A stationary receiver keeps reporting its last course instead of snapping to north.",
        )
        altitudeDriftToggle = option(
            root,
            "Drift the altitude",
            "A slow random walk of a few centimetres per second.",
        )
        accuracyWarmupToggle = option(
            root,
            "Warm up the accuracy",
            "30 m converging to 5 m over the first half minute, as a cold fix does.",
        )
        satelliteJitterToggle = option(
            root,
            "Vary the satellite count",
            "A plausible 7 to 14 instead of one fixed number.",
        )
        signalDropoutToggle = option(
            root,
            "Lose the signal occasionally",
            "A few seconds of dropout now and then, which is the one a strict receiver may reject.",
        )

        root.addView(action("Start straight line from the first waypoint") { startStraight() })
        root.addView(action("Start humanized route simulation") { startRoute() })
        pauseBtn = action("Pause route") { toggleRoutePause() }
        root.addView(pauseBtn)
        root.addView(action("Stop simulation") {
            PacerConfigClient.stop()
            output.text = "Config: active=false."
            updatePauseButton()
        })
        root.addView(action("Show floating joystick") { showJoystick() })

        previewBtn = action("Preview engine locally (no hooks)") { previewLocally() }
        root.addView(previewBtn)

        auditBtn = action("Audit this track (server-side heuristics)") { auditRoute() }
        root.addView(auditBtn)

        // --- diagnostics ------------------------------------------------------
        // Plain text in a plain box. The sheet is for reading and for pasting into a
        // chat, not for looking at, so there is no styling to keep in step with the
        // rest of the page.
        root.addView(section("Self-check"))
        root.addView(action("Run self-check") { runSelfCheck() })
        root.addView(
            hint(
                "Reads this app's own location, WiFi, cell, Bluetooth and step counter back " +
                    "through the public APIs and compares them with what the engine says they " +
                    "must be. This app is deliberately outside the module's scope, so what it " +
                    "sees is the system-wide result, not a private copy. To check a target app, " +
                    "open that app and look at its map. Run this while a simulation is active. " +
                    "Copy puts the whole report on the clipboard.",
            ),
        )
        selfCheckOutput = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 0)
        }
        root.addView(selfCheckOutput)

        output = TextView(this).apply {
            text = "Tip: add the target app to the module scope in LSPosed."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })

        PacerConfigClient.init()
        renderRoute()
        renderSavedRoutes()
        updatePauseButton()
        // Opens on the stored configuration rather than on this screen's defaults.
        // Every start writes ALL of these keys, so opening on defaults meant that
        // touching one switch silently restored the others - a user who had switched
        // the Bluetooth simulation off got it switched back on by an unrelated tap.
        PacerConfigClient.onReady { runOnUiThread { restoreFromStore() } }
    }

    /** Shows what is actually configured. See the call site for why this exists. */
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
        planInput.setText(stored.plan)
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
     * The clock is what stops: the module keeps reading the same run, so the position,
     * the step count, the distance and the lap stay consistent with each other instead
     * of being re-derived from a separately stored point.
     */
    private fun toggleRoutePause() {
        val owner = PacerConfigClient.owner()
        val pause = owner.canPauseRoute
        if (!pause && !owner.canResumeRoute) {
            output.text = "Only a running route can be paused."
            updatePauseButton()
            return
        }
        if (!PacerConfigClient.setRoutePaused(pause)) {
            output.text = PacerConfigClient.NOT_READY_MESSAGE
            return
        }
        output.text = if (pause) {
            "Route paused where it stands; the subject reports no speed until it resumes."
        } else {
            "Route resumed from the point it stopped at."
        }
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
    }

    // ---------------------------------------------------------------------
    // Route editing
    // ---------------------------------------------------------------------

    /**
     * Replaces the route with a fixed one, for trying the engine without drawing anything.
     *
     * Kept because the map is the only other way in, and a route that depends on a tile
     * service being reachable is not a good first experience of the engine.
     */
    private fun loadSampleRoute() {
        draft.clear()
        SAMPLE_ROUTE.forEach { draft.add(it) }
        persistAndRender()
        renderSavedRoutes()
    }

    override fun onPause() {
        super.onPause()
        // A recording cannot outlive the page that owns the draft: the tick would keep
        // collecting points nobody can see or stop. Ending it here keeps the promise the
        // button makes - what was walked is what gets saved.
        recorder?.let { finishRecording(it) }
    }

    /**
     * Saves the per-app datum table.
     *
     * Parsed before it is stored, and the normalised form is written back into the field:
     * a typo then shows up as a dropped entry immediately, rather than as a fix in the
     * wrong datum on the next run.
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
            output.text = "A named route needs two distinct waypoints; this draft is not one yet."
            return
        }
        val stored = store.savedRoutes()
        val existing = currentSavedRouteId(stored)?.let { stored.find(it) }
        val field = EditText(this).apply {
            hint = "Route name"
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
            savedRouteBox.addView(hint("No saved routes yet."))
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
                output.text = "That route is no longer in the store."
            } else {
                draft = loaded
                persistAndRender()
                renderSavedRoutes()
                output.text = "Loaded \"${route.name}\" (${route.points.size} waypoints)."
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
                    output.text = "Renamed."
                } else {
                    output.text = "A name is required, under ${SavedRoutes.MAX_NAME_LENGTH} characters."
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
                output.text = "Deleted \"${route.name}\"."
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
    private fun startStraight() {
        val start = draft.points.firstOrNull()
        if (start == null) {
            output.text = "Place a waypoint on the map first; a straight line needs a start."
            return
        }
        val lat = start.lat
        val lon = start.lon
        val speed = speedMps()
        val plan = planTextOrNull() ?: run {
            output.text = "The interval plan could not be read. Use e.g. 3.0@180;1.4@60#5."
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
            output.text = PacerConfigClient.NOT_READY_MESSAGE
            return
        }
        output.text = if (plan.isBlank()) {
            String.format(Locale.US, "Straight line pushed: %.5f, %.5f at %.2f m/s.", lat, lon, speed)
        } else {
            // The plan overrides the speed field, so quoting only the field would
            // describe a run that is not happening.
            String.format(Locale.US, "Straight line pushed: %.5f, %.5f, speed from the plan.", lat, lon) +
                planSummary(plan)
        }
        updatePauseButton()
    }

    /**
     * A line about the interval plan that is about to run, using the plan's own
     * arithmetic rather than a second calculation: a mistyped step is then visible
     * before the run instead of only in its speed curve.
     */
    private fun planSummary(plan: String): String {
        val parsed = SpeedPlan.parse(plan) ?: return ""
        val pass = String.format(Locale.US, "%.0f s / %.0f m per pass", parsed.passSeconds, parsed.passMeters)
        val total = parsed.totalMeters?.let { String.format(Locale.US, ", %.0f m in total", it) }
            ?: ", repeating for the whole route"
        return " Plan: $pass$total."
    }

    /**
     * The interval plan as typed, or null when it cannot be read.
     *
     * Refusing to start beats starting with the text silently ignored: the user would
     * see one steady speed and have no way to tell whether the plan's syntax or the
     * engine was at fault.
     */
    private fun planTextOrNull(): String? {
        val text = planInput.text.toString().trim()
        if (text.isEmpty()) return ""
        return if (SpeedPlan.parse(text) != null) text else null
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
     * Recording reads the subject's position from the stored run, so it needs a run that
     * the joystick can steer: a hand-driven straight line. A route or an interval plan
     * owns its own motion, and recording one would just re-record the route it is
     * following, which is what the map already has.
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
            recordBtn.text = "Stop recording"
            output.text = "Recording. Steer with the floating joystick; the route is saved " +
                "to the editor when you stop."
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
            "Stop recording (%d points, %.0f m)",
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
        recordBtn.text = "Start recording from the joystick"
        val stored = PacerConfigClient.stored()
        val last = stored?.takeIf { it.active }?.let { subject.point(it) }
        if (!recording.finish(last)) {
            output.text = "Nothing was recorded: the subject did not move far enough."
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

    private fun startRoute() {
        if (!draft.isValid) {
            output.text = "Add at least two waypoints that are not the same place before starting a route."
            return
        }
        // The road path when one was planned on the map screen, the plain polyline
        // otherwise: both screens push the geometry that the map is drawing.
        val geometry = store.geometry(draft)
        val route = RoutePath(geometry)
        val first = route.points.first()
        val speed = speedMps()
        val plan = planTextOrNull() ?: run {
            output.text = "The interval plan could not be read. Use e.g. 3.0@180;1.4@60#5."
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
            output.text = PacerConfigClient.NOT_READY_MESSAGE
            return
        }
        output.text = if (plan.isBlank()) {
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
        }
        updatePauseButton()
    }

    private fun showJoystick() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, JoystickOverlayService::class.java))
            // Saying which input owns the run here is cheaper than letting the user
            // find out by dragging the knob.
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
            output.text = "Grant overlay permission, then tap again."
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
        val route = routeForPreview() ?: RoutePath(SAMPLE_ROUTE)
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        // The preview follows the interval plan as well, otherwise it would show a
        // steady-speed track for a session that will not be one.
        val plan = SpeedPlan.parse(planInput.text.toString())
        val speed = speedMps()
        // Two DIFFERENT runs of the same route: the comparison below is only meaningful
        // if the second run is a second run, so its seed advances by one. The shared
        // derivation is used rather than Random.nextLong() so a report can be reproduced
        // from the two numbers it names.
        val seedBase = System.currentTimeMillis()
        auditBtn.isEnabled = false
        output.text = "Auditing the track..."
        thread {
            fun runOnce(seed: Long) = RouteSimulator(
                route,
                MotionConfig(baseSpeedMps = speed).copy(plan = plan),
                toggles = toggles,
                steadyAccuracyMeters = accuracy,
            ).simulate(dtSec = 1.0, seed = seed)

            val first = runOnce(MotionSeed.forRun(seedBase))
            val second = runOnce(MotionSeed.forRun(seedBase + 1))
            // The screen can only supply the duplicate check: it has the previous run
            // of this route. Zone and checkpoint rules need a server's geometry, so
            // they stay available to callers instead of running on invented defaults.
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
        selfCheckOutput.text = "Checking..."
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

    /** Mean distance between two runs' positions at the same sample index. */    private fun meanDisplacementMeters(a: List<FixSample>, b: List<FixSample>): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var total = 0.0
        for (i in 0 until n) total += GeoMath.haversine(a[i].point, b[i].point)
        return total / n
    }

    /**
     * The route the previews and the audit work on: the stored road path when the map
     * planned one, the draft's own polyline otherwise.
     *
     * This screen edits the same draft as the map, so it has to show what would actually
     * be simulated there rather than a different line through the same waypoints.
     */
    private fun routeForPreview(): RoutePath? {
        if (!draft.isValid) return null
        val geometry = store.geometry(draft)
        return if (geometry.size >= 2) RoutePath(geometry) else null
    }

    private fun previewLocally() {
        previewBtn.isEnabled = false
        output.text = "Simulating..."
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        val plan = SpeedPlan.parse(planInput.text.toString())
        val speed = speedMps()
        thread {
            val route = routeForPreview() ?: RoutePath(SAMPLE_ROUTE)
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
        }
    }

    // ---------------------------------------------------------------------
    // Small view builders
    // ---------------------------------------------------------------------

    private fun titleView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        gravity = Gravity.CENTER
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 4, 0, 16)
    }

    /**
     * A switch that is added to [into], with a one-line explanation underneath. The
     * explanation is part of the control on purpose: every one of these changes what the
     * simulated track looks like, and none is obvious from its label.
     *
     * [checked] is the initial state; the realism switches default to on (a device whose
     * track looks machine-made is the thing this project exists to avoid), while the
     * ones that change what a run IS default to off.
     */
    private fun option(into: LinearLayout, label: String, explanation: String, checked: Boolean = true): CheckBox {
        val box = CheckBox(this).apply {
            text = label
            isChecked = checked
        }
        into.addView(box)
        into.addView(hint(explanation))
        return box
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setPadding(0, 24, 0, 4)
    }

    private fun radio(label: String): RadioButton = RadioButton(this).apply { text = label }

    private fun numberField(hint: String, initial: String): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(initial)
    }

    private fun action(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun smallAction(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(12, 0, 12, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

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

        /**
         * A sample loop in Urumqi.
         *
         * Deliberately the default the map also opens on: a sample somewhere almost no user
         * is, so it cannot be mistaken for their own position, and the same place so the two
         * screens agree about where "the sample" is. Inside China because AMap and Baidu
         * serve blank tiles outside it.
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
