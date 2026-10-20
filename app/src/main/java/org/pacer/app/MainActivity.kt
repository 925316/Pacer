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
import org.pacer.motion.RouteSimulator
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
    private lateinit var bearingHoldToggle: CheckBox
    private lateinit var altitudeDriftToggle: CheckBox
    private lateinit var accuracyWarmupToggle: CheckBox
    private lateinit var satelliteJitterToggle: CheckBox
    private lateinit var signalDropoutToggle: CheckBox

    private lateinit var routeSummary: TextView
    private lateinit var waypointBox: LinearLayout

    private lateinit var previewBtn: Button
    private lateinit var auditBtn: Button

    /** Pauses or resumes a route run; hidden whenever neither action applies. */
    private lateinit var pauseBtn: Button

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
        root.addView(action("Clear route") { draft.clear(); persistAndRender() })

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

        output = TextView(this).apply {
            text = "Tip: add the target app to the module scope in LSPosed."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })

        PacerConfigClient.init()
        renderRoute()
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
    }

    private fun persistAndRender() {
        store.save(draft)
        renderRoute()
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
