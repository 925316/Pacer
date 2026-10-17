package org.pacer.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.pacer.motion.CoordinateInput
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.RouteDraft
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
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

    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var pasteInput: EditText

    private lateinit var routeSummary: TextView
    private lateinit var waypointBox: LinearLayout

    private lateinit var searchInput: EditText
    private lateinit var searchResultLabel: TextView
    private var searchedPlace: Place? = null

    private lateinit var previewBtn: Button

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
        root.addView(action("Start straight line from coordinates") { startStraight() })
        root.addView(action("Start humanized route simulation") { startRoute() })
        root.addView(action("Stop simulation") {
            PacerConfigClient.stop()
            output.text = "Config: active=false."
        })
        root.addView(action("Show floating joystick") { showJoystick() })

        previewBtn = action("Preview engine locally (no hooks)") { previewLocally() }
        root.addView(previewBtn)

        output = TextView(this).apply {
            text = "Tip: add the target app to the module scope in LSPosed."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })

        PacerConfigClient.init()
        renderRoute()
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
        customBtn.isChecked -> speedInput.text.toString().toDoubleOrNull()?.coerceIn(0.1, 30.0) ?: 3.0
        rideBtn.isChecked -> 6.0
        walkBtn.isChecked -> 1.4
        else -> 3.0
    }

    private fun startStraight() {
        val lat = latInput.text.toString().toDoubleOrNull() ?: 39.9042
        val lon = lonInput.text.toString().toDoubleOrNull() ?: 116.4074
        val speed = speedMps()
        PacerConfigClient.start(lat = lat, lon = lon, speed = speed, bearing = 45f)
        output.text = String.format(Locale.US, "Straight line pushed: %.5f, %.5f at %.2f m/s.", lat, lon, speed)
    }

    private fun startRoute() {
        val route = draft.toRoutePath()
        if (route == null) {
            output.text = "Add at least two waypoints before starting a route."
            return
        }
        val first = route.points.first()
        val speed = speedMps()
        PacerConfigClient.start(
            lat = first.lat,
            lon = first.lon,
            speed = speed,
            bearing = 45f,
            routeJson = draft.toJson(),
        )
        output.text = String.format(
            Locale.US,
            "Route pushed: %d waypoints, %.0f m, %.2f m/s. The engine humanizes speed along it.",
            draft.size,
            draft.totalLengthMeters(),
            speed,
        )
    }

    private fun showJoystick() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, JoystickOverlayService::class.java))
            output.text = "Joystick shown. Drag the knob to steer, tap centre to stop."
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

    private fun previewLocally() {
        previewBtn.isEnabled = false
        output.text = "Simulating..."
        thread {
            val route = draft.toRoutePath() ?: RoutePath(SAMPLE_ROUTE)
            val fixes = RouteSimulator(route, MotionConfig.run()).simulate(dtSec = 1.0)
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
