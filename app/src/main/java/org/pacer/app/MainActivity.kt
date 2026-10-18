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
import org.pacer.motion.CoordinateInput
import org.pacer.motion.FixSample
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteDraft
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan
import org.pacer.motion.TrackAudit
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Control surface. Plain framework views on purpose: the app module has no
 * Compose or map dependency yet, so everything here is a few standard widgets
 * driven by the pure-Kotlin engine. The route being built lives in a
 * [RouteDraft], which is persisted between launches and pushed to the hook in
 * the shared RouteJson wire format.
 */
class MainActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft

    private lateinit var output: TextView

    private lateinit var walkBtn: RadioButton
    private lateinit var runBtn: RadioButton
    private lateinit var rideBtn: RadioButton
    private lateinit var customBtn: RadioButton
    private lateinit var speedInput: EditText

    private lateinit var stepToggle: CheckBox
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

    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var pasteInput: EditText

    private lateinit var routeSummary: TextView
    private lateinit var waypointBox: LinearLayout

    private lateinit var searchInput: EditText
    private lateinit var searchResultLabel: TextView
    private var searchedPlace: Place? = null

    private lateinit var previewBtn: Button
    private lateinit var auditBtn: Button

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

        // --- point / waypoint input -----------------------------------------
        root.addView(section("Route"))
        latInput = numberField("Latitude (WGS84)", "39.9042")
        lonInput = numberField("Longitude (WGS84)", "116.4074")
        root.addView(latInput)
        root.addView(lonInput)
        pasteInput = EditText(this).apply { hint = "Paste a Google Maps link or \"lat,lon\"" }
        root.addView(pasteInput)
        root.addView(action("Use pasted link / coordinates") { usePasted() })
        root.addView(action("Add waypoint at these coordinates") { addWaypointFromInputs() })

        searchInput = EditText(this).apply { hint = "Search a place (OSM Nominatim)" }
        root.addView(searchInput)
        root.addView(action("Search") { doSearch() })
        searchResultLabel = TextView(this).apply {
            text = "No search result yet."
            textSize = 13f
        }
        root.addView(searchResultLabel)
        root.addView(action("Add searched place as waypoint") { addSearchedPlace() })
        root.addView(action("Pick waypoints on the map") {
            startActivity(Intent(this, MapActivity::class.java))
        })

        routeSummary = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 8) }
        root.addView(routeSummary)

        waypointBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(waypointBox)

        root.addView(action("Load sample campus route") { loadSampleRoute() })
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
                    "on the straight line; the reported speed follows it.",
            ),
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

        root.addView(action("Start straight line from coordinates") { startStraight() })
        root.addView(action("Start humanized route simulation") { startRoute() })
        root.addView(action("Stop simulation") {
            PacerConfigClient.stop()
            output.text = "Config: active=false."
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
        // Opens on the stored configuration rather than on this screen's defaults.
        // Every start writes ALL of these keys, so opening on defaults meant that
        // touching one switch silently restored the others - a user who had switched
        // the Bluetooth simulation off got it switched back on by an unrelated tap.
        PacerConfigClient.onReady { runOnUiThread { restoreFromStore() } }
    }

    /** Shows what is actually configured. See the call site for why this exists. */
    private fun restoreFromStore() {
        val stored = PacerConfigClient.stored() ?: return
        if (stored.lat != 0.0 || stored.lon != 0.0) {
            latInput.setText(String.format(Locale.US, "%.5f", stored.lat))
            lonInput.setText(String.format(Locale.US, "%.5f", stored.lon))
        }
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
        bearingHoldToggle.isChecked = stored.realism.bearingHold
        altitudeDriftToggle.isChecked = stored.realism.altitudeDrift
        accuracyWarmupToggle.isChecked = stored.realism.accuracyWarmup
        satelliteJitterToggle.isChecked = stored.realism.satelliteJitter
        signalDropoutToggle.isChecked = stored.realism.signalDropout
        output.text = when {
            stored.onRoute -> "Loaded the stored configuration: a route simulation is configured."
            stored.active -> "Loaded the stored configuration: a straight-line simulation is running."
            else -> "Loaded the stored configuration."
        }
    }

    override fun onResume() {
        super.onResume()
        // The map screen edits the same persisted draft, so re-read it here.
        draft = store.load()
        renderRoute()
    }

    // ---------------------------------------------------------------------
    // Route editing
    // ---------------------------------------------------------------------

    private fun usePasted() {
        val point = CoordinateInput.parse(pasteInput.text.toString())
        if (point == null) {
            output.text = "Could not read a coordinate from that text."
            return
        }
        latInput.setText(point.lat.toString())
        lonInput.setText(point.lon.toString())
        draft.add(point)
        persistAndRender()
        output.text = String.format(Locale.US, "Added waypoint %.5f, %.5f from pasted text.", point.lat, point.lon)
    }

    private fun addWaypointFromInputs() {
        val lat = latInput.text.toString().toDoubleOrNull()
        val lon = lonInput.text.toString().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            output.text = "Enter a valid latitude (-90..90) and longitude (-180..180)."
            return
        }
        draft.add(GeoPoint(lat, lon))
        persistAndRender()
    }

    private fun addSearchedPlace() {
        val place = searchedPlace
        if (place == null) {
            output.text = "Search a place first."
            return
        }
        draft.add(place.point)
        persistAndRender()
    }

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
        val lengthMeters = draft.totalLengthMeters()
        routeSummary.text = String.format(
            Locale.US,
            "Waypoints: %d   Length: %.0f m%s",
            draft.size,
            lengthMeters,
            if (draft.isValid) "" else "   (need at least 2 to run a route)",
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

    private fun startStraight() {
        val lat = latInput.text.toString().toDoubleOrNull() ?: 39.9042
        val lon = lonInput.text.toString().toDoubleOrNull() ?: 116.4074
        val speed = speedMps()
        val plan = planTextOrNull() ?: run {
            output.text = "The interval plan could not be read. Use e.g. 3.0@180;1.4@60#5."
            return
        }
        PacerConfigClient.start(
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
        output.text = String.format(Locale.US, "Straight line pushed: %.5f, %.5f at %.2f m/s.", lat, lon, speed) +
            planSummary(plan)
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
        val route = draft.toRoutePath()
        if (route == null) {
            output.text = "Add at least two waypoints before starting a route."
            return
        }
        val first = route.points.first()
        val speed = speedMps()
        val plan = planTextOrNull() ?: run {
            output.text = "The interval plan could not be read. Use e.g. 3.0@180;1.4@60#5."
            return
        }
        PacerConfigClient.start(
            lat = first.lat,
            lon = first.lon,
            speed = speed,
            bearing = numberOr(bearingInput, 45f),
            accuracy = numberOr(accuracyInput, 5f),
            altitudeMeters = doubleOr(altitudeInput, 0.0),
            routeJson = draft.toJson(),
            plan = plan,
            mockBluetooth = bluetoothToggle.isChecked,
            stepSimulation = stepToggle.isChecked,
            realism = realismToggles(),
        )
        output.text = String.format(
            Locale.US,
            "Route pushed: %d waypoints, %.0f m, %.2f m/s. The engine humanizes speed along it.",
            draft.size,
            draft.totalLengthMeters(),
            speed,
        ) + planSummary(plan)
    }

    private fun showJoystick() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, JoystickOverlayService::class.java))
            output.text = "Joystick shown. Drag the knob to steer (releasing keeps the last " +
                "heading), drag the ring to move it, tap to stop."
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
        val route = draft.toRoutePath() ?: RoutePath(SAMPLE_ROUTE)
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        // The preview follows the interval plan as well, otherwise it would show a
        // steady-speed track for a session that will not be one.
        val plan = SpeedPlan.parse(planInput.text.toString())
        val speed = speedMps()
        auditBtn.isEnabled = false
        output.text = "Auditing the track..."
        thread {
            fun runOnce() = RouteSimulator(
                route,
                MotionConfig(baseSpeedMps = speed).copy(plan = plan),
                toggles = toggles,
                steadyAccuracyMeters = accuracy,
            ).simulate(dtSec = 1.0)

            val first = runOnce()
            val second = runOnce()
            val report = TrackAudit.audit(second)
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

    private fun previewLocally() {
        previewBtn.isEnabled = false
        output.text = "Simulating..."
        // Read the controls here: a background thread must not touch the views.
        val toggles = realismToggles()
        val accuracy = numberOr(accuracyInput, 5f)
        val plan = SpeedPlan.parse(planInput.text.toString())
        val speed = speedMps()
        thread {
            val route = draft.toRoutePath() ?: RoutePath(SAMPLE_ROUTE)
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

    private fun doSearch() {
        val query = searchInput.text.toString()
        if (query.isBlank()) return
        searchResultLabel.text = "Searching..."
        thread {
            val results = PlaceSearch.search(query)
            runOnUiThread {
                val top = results.firstOrNull()
                searchedPlace = top
                searchResultLabel.text = when {
                    top == null -> "No result (offline, or nothing matched)."
                    else -> "${top.displayName}\n${top.point.lat}, ${top.point.lon}"
                }
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
     * A switch that is added to [into] already checked, with a one-line explanation
     * underneath. The explanation is part of the control on purpose: every one of these
     * changes what the simulated track looks like, and none is obvious from its label.
     */
    private fun option(into: LinearLayout, label: String, explanation: String): CheckBox {
        val box = CheckBox(this).apply {
            text = label
            isChecked = true
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

        /** Sample campus polygon; [lat,lon] pairs. */
        val SAMPLE_ROUTE: List<GeoPoint> = listOf(
            GeoPoint(39.9042, 116.4074),
            GeoPoint(39.9052, 116.4090),
            GeoPoint(39.9081, 116.4104),
            GeoPoint(39.9095, 116.4071),
            GeoPoint(39.9080, 116.4040),
            GeoPoint(39.9050, 116.4035),
        )
    }
}
