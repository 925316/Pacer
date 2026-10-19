package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.pacer.motion.FixSample
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionSamples
import org.pacer.motion.MotionSeed
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteDraft
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan
import org.pacer.motion.StraightLine
import java.util.Locale

/**
 * The operation page: a map, the waypoints placed on it, and the run's own controls.
 *
 * HOW A ROUTE IS MADE. Drag the map to the place, tap to append a waypoint, drag a handle to
 * move one. That is the whole editing model - there is no coordinate field, no search box and
 * no road-routing service, because each of those needs either an API key or a network
 * dependency, and none of them is how someone actually chooses a place on a map.
 *
 * THREE BASEMAPS, ONE INTERACTION. AMap, Baidu and Google are all drawn by [TileMapView] over
 * a [org.pacer.motion.TileGrid], so panning, zooming, tapping and dragging behave the same
 * whichever one is showing. They differ in datum and, for Baidu, in projection; that is the
 * grid's problem, not this page's.
 *
 * The live marker is DERIVED, not read from the module: it is computed with the same
 * core-motion code and the same run seed the hook uses, from the same stored inputs, so a
 * marker that disagreed with what the target app is told would be a bug in one shared
 * formula rather than drift between two copies.
 */
class MapActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft

    private lateinit var info: TextView
    private lateinit var speedField: EditText
    private lateinit var loopCheck: CheckBox
    private lateinit var mapHolder: LinearLayout
    private lateinit var styleRow: LinearLayout
    private lateinit var layerRow: LinearLayout

    private var style: MapStyle = MapStyle.DEFAULT
    private var layer: MapLayer = MapLayer.ROAD
    private var map: TileMapView? = null

    /** Enabled only while a waypoint is selected. */
    private lateinit var removeButton: Button

    /**
     * The waypoint last tapped on the map, or -1.
     *
     * Selection exists because the only per-waypoint edit the map can offer is "remove this
     * one": reordering already has a list on the other screen. An index rather than a point,
     * so a drag cannot leave the selection naming coordinates that no longer exist.
     */
    private var selectedWaypoint = -1

    /** Drives the live marker, and only while this page is visible. */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Set between onResume and onPause. The tick clears its own re-post from this rather
     * than relying on removeCallbacks alone: a tick already running when the page is
     * paused would otherwise schedule the next one afterwards.
     */
    private var polling = false

    private val liveTick = object : Runnable {
        override fun run() {
            try {
                updateLivePosition()
            } catch (_: Throwable) {
            } finally {
                if (polling) handler.postDelayed(this, LIVE_POLL_MS)
            }
        }
    }

    /** One lap of the stored run, rebuilt only when the stored run changes. */
    private class LiveLap(val key: String, val samples: List<FixSample>)

    private var liveLap: LiveLap? = null
    private var liveLapRunStartedAt: Long = 0L
    private var liveLapSeconds: Double = 0.0

    /** The stored route as a [RoutePath], cached because the poll asks once a second. */
    private var parsedRouteJson: String? = null
    private var parsedRoute: RoutePath? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        draft = store.load()
        style = store.mapStyle() ?: MapStyle.DEFAULT
        // A stored layer that the stored source cannot draw (satellite on Baidu) falls back
        // to roads rather than putting the page on a layer it will never show.
        layer = store.mapLayer()?.takeIf { style.supports(it) } ?: MapLayer.ROAD
        PacerConfigClient.init()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        info = TextView(this).apply { textSize = 14f }
        column.addView(info)

        // Basemaps are a row of buttons rather than a spinner: there are three, and a tap is
        // faster than opening a list to pick one of three.
        styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(styleRow)

        // Road / satellite / hybrid, for the sources that have them. On its own row because
        // three basemaps and three layers would not fit one line on a phone.
        layerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(layerRow)

        val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editRow.addView(button("Undo") {
            editDraft { if (draft.size > 0) draft.removeAt(draft.size - 1) }
        })
        editRow.addView(button("Clear") { editDraft { draft.clear() } })
        removeButton = button("Remove") { removeSelectedWaypoint() }
        removeButton.isEnabled = false
        editRow.addView(removeButton)
        column.addView(editRow)

        val viewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        viewRow.addView(button("Fit") { fitRoute() })
        viewRow.addView(button("Centre") { centreOnSubject() })
        column.addView(viewRow)

        // The map gets the remaining height. It is the point of the page, and a map that
        // shares its space with the controls ends up too short to place a point on.
        mapHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        column.addView(mapHolder)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Start") { startSimulation() })
        controls.addView(button("Stop") {
            PacerConfigClient.stop()
            info.text = "Simulation stopped."
            updateLivePosition()
        })
        controls.addView(TextView(this).apply { text = " Speed m/s "; textSize = 13f })
        speedField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        controls.addView(speedField)
        loopCheck = CheckBox(this).apply { text = "Loop" }
        controls.addView(loopCheck)
        column.addView(controls)

        // The map keeps its weight because the page is a column, not a scroll view: controls
        // that scrolled away would leave a map whose height changed while it was being used.
        setContentView(column)

        buildStyleRow()
        buildLayerRow()
        installMap()

        render()
        // A route that came back from the last session is fitted once the view has a size;
        // otherwise the page opens looking at one arbitrary waypoint.
        if (draft.size >= 2) {
            mapHolder.post { if (!isFinishing && !isDestroyed) fitRoute() }
        }
        // The controls open on the stored run, for the same reason the other screen does: a
        // screen that opens on its own defaults silently rewrites the user's settings the
        // next time it starts something.
        PacerConfigClient.onReady {
            runOnUiThread {
                val stored = PacerConfigClient.stored() ?: return@runOnUiThread
                if (stored.speed > 0.0) {
                    speedField.setText(String.format(Locale.US, "%.1f", stored.speed))
                }
                loopCheck.isChecked = stored.loopRoute
            }
        }
    }

    /**
     * Creates the map for the chosen basemap, or replaces the current one.
     *
     * The camera is carried across a basemap switch: the user picked a place, not a
     * projection, and being sent back to the default view for changing which tiles draw it
     * would make the three sources useless for comparing them.
     */
    private fun installMap() {
        val previous = map
        val center = previous?.let { holder ->
            // Recover the centre by asking the current grid where the middle of the view is.
            holder.centerPoint()
        }
        val created = TileMapView(
            this,
            TileMapView.TileSource(
                label = "${style.label} ${layer.label}",
                grid = style.grid,
                url = { x, y, z -> style.url(x, y, z, layer) },
                overlay = { x, y, z -> style.overlay(x, y, z, layer) },
            ),
            initialCenter = center ?: draft.points.lastOrNull() ?: DEFAULT_CENTER,
            initialZoom = previous?.zoom ?: DEFAULT_ZOOM,
        )
        created.onTap = { point -> editDraft { draft.add(point) } }
        created.onWaypointTap = { index -> selectWaypoint(index) }
        created.onWaypointMoving = { index, point ->
            // Follow the drag live so the polyline tracks the finger. Nothing is saved here:
            // a save per motion event would write prefs hundreds of times a second, and the
            // drop below is the moment the route actually changed.
            if (index < draft.size) {
                draft.replaceAt(index, point)
                created.setRoute(draft.points.toList(), draft.points.toList())
                updateInfo()
            }
        }
        created.onWaypointDropped = { index, point ->
            editDraft { if (index < draft.size) draft.replaceAt(index, point) }
        }
        created.onLongPress = { point -> relocate(point) }
        created.setRoute(draft.points.toList(), draft.points.toList())
        mapHolder.removeAllViews()
        mapHolder.addView(
            created,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        map = created
        updateStyleButtons()
        updateLayerButtons()
    }

    private fun buildStyleRow() {
        for (candidate in MapStyle.entries) {
            styleRow.addView(button(candidate.label) {
                if (candidate == style) return@button
                style = candidate
                store.saveMapStyle(candidate)
                // The new source may not draw the layer that was on screen. Saying so beats
                // leaving a Satellite button lit over a road map.
                val dropped = layer.takeIf { !style.supports(it) }
                if (dropped != null) {
                    layer = MapLayer.ROAD
                    store.saveMapLayer(layer)
                }
                buildLayerRow()
                installMap()
                render()
                if (dropped != null) {
                    info.text = "${style.label} has no ${dropped.label.lowercase()} tiles on " +
                        "this build; showing ${layer.label.lowercase()}."
                }
            })
        }
    }

    private fun updateStyleButtons() {
        for (i in 0 until styleRow.childCount) {
            val b = styleRow.getChildAt(i) as? Button ?: continue
            val candidate = MapStyle.entries.getOrNull(i) ?: continue
            // The active one is disabled: a button that does nothing when tapped should look
            // like it does nothing.
            b.isEnabled = candidate != style
            b.alpha = if (candidate == style) 0.5f else 1f
        }
    }

    /**
     * Rebuilds the layer buttons for the current source.
     *
     * Rebuilt rather than disabled in place because the set differs by source: Baidu has
     * roads only, so a dead Satellite button would promise a layer no tap can reach.
     */
    private fun buildLayerRow() {
        layerRow.removeAllViews()
        // A row with one dead button says less than no row at all; the message shown when a
        // source without imagery is selected explains the absence.
        layerRow.visibility = if (style.layers.size > 1) View.VISIBLE else View.GONE
        for (candidate in style.layers) {
            val b = button(candidate.label) {
                if (candidate == layer) return@button
                layer = candidate
                store.saveMapLayer(candidate)
                installMap()
                render()
            }
            // Equal shares: "Satellite" and "Hybrid" are long enough to push a third button
            // off a narrow screen at their natural width.
            b.textSize = 12f
            layerRow.addView(
                b,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        updateLayerButtons()
    }

    private fun updateLayerButtons() {
        for (i in 0 until layerRow.childCount) {
            val b = layerRow.getChildAt(i) as? Button ?: continue
            val candidate = style.layers.getOrNull(i) ?: continue
            b.isEnabled = candidate != layer
            b.alpha = if (candidate == layer) 0.5f else 1f
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload in case another screen changed the draft while we were away.
        draft = store.load()
        render()
        // The marker is only worth computing while this page is in front of the user; a
        // tick left running behind another screen burns a full run's rebuild for nothing.
        polling = true
        handler.removeCallbacks(liveTick)
        handler.postDelayed(liveTick, LIVE_POLL_MS)
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(liveTick)
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(liveTick)
        super.onDestroy()
    }

    /**
     * Every edit to the draft goes through here.
     *
     * One place, because saving and redrawing must happen together: a saved draft the map has
     * not drawn is invisible, and a drawn route the other screen cannot read is worse.
     */
    private fun editDraft(change: () -> Unit) {
        val before = draft.toJson()
        change()
        if (draft.toJson() == before) return
        // The points changed, so an index read before the edit may now name a different
        // point: a selection does not survive an edit it was not part of.
        clearSelection()
        store.save(draft)
        render()
    }

    private fun render() {
        val geometry = draft.points.toList()
        map?.setRoute(geometry, geometry)
        updateInfo()
        updateLivePosition()
    }

    // ---------------------------------------------------------------------
    // Waypoint selection
    // ---------------------------------------------------------------------

    /**
     * Selects or deselects a waypoint, as the first half of "remove this one".
     *
     * A second tap on the same point deselects, so the state can always be left without
     * removing anything: a tap that removed the point outright would make a mis-tap cost the
     * point, and clearing the route is the only undo there is.
     */
    private fun selectWaypoint(index: Int) {
        if (index == selectedWaypoint) {
            clearSelection()
            map?.setSelectedWaypoint(null)
            return
        }
        selectedWaypoint = index
        removeButton.isEnabled = true
        map?.setSelectedWaypoint(index)
        info.text = String.format(
            Locale.US,
            "Waypoint %d of %d selected. Remove deletes it; tap it again to deselect.",
            index + 1,
            draft.size,
        )
    }

    private fun clearSelection() {
        selectedWaypoint = -1
        removeButton.isEnabled = false
        map?.setSelectedWaypoint(null)
    }

    private fun removeSelectedWaypoint() {
        val index = selectedWaypoint
        if (index < 0 || index >= draft.size) return
        clearSelection()
        editDraft { draft.removeAt(index) }
    }

    // ---------------------------------------------------------------------
    // Map commands
    // ---------------------------------------------------------------------

    private fun fitRoute() {
        val points = draft.points.toList()
        if (points.isEmpty()) {
            info.text = "Place a waypoint on the map first."
            return
        }
        map?.fitRoute(points)
    }

    /**
     * Centres on the simulated subject, or on the last waypoint when nothing is running.
     *
     * Two different targets on purpose: "centre" on this page means "show me where the run
     * is", and with no run the only meaningful thing to show is where the route ends.
     */
    private fun centreOnSubject() {
        val map = this.map ?: return
        val stored = PacerConfigClient.stored()
        val subject = if (stored != null && stored.active) livePoint(stored) else null
        val target = subject ?: draft.points.lastOrNull()
        if (target == null) {
            info.text = "Nothing to centre on yet."
            return
        }
        map.centerOn(target)
        info.text = if (subject != null) {
            String.format(Locale.US, "Centred on the subject at %.5f, %.5f.", target.lat, target.lon)
        } else {
            "Centred on the last waypoint."
        }
    }

    /**
     * Moves a running straight-line simulation to the point under a long press.
     *
     * A route run is refused rather than moved: its position comes from its geometry, so
     * moving the anchor would change nothing, and a press that quietly does nothing is
     * worse than one that says why.
     */
    private fun relocate(point: GeoPoint) {
        val stored = PacerConfigClient.stored()
        when {
            stored != null && stored.active && stored.onRoute ->
                info.text = "This run follows a route, so it cannot be moved with a long press. " +
                    "Edit its waypoints instead, or stop it and start a straight-line run."
            PacerConfigClient.canTeleport() -> {
                PacerConfigClient.teleport(point.lat, point.lon)
                info.text = String.format(
                    Locale.US,
                    "Simulation moved to %.5f, %.5f.",
                    point.lat,
                    point.lon,
                )
                updateLivePosition()
            }
            else -> info.text =
                "Long press moves a running straight-line simulation here; none is running."
        }
    }

    // ---------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------

    private fun startSimulation() {
        if (!draft.isValid) {
            info.text = "Place at least two waypoints that are not the same place before starting."
            return
        }
        val geometry = store.geometry(draft)
        val route = RoutePath(geometry)
        val speed = speedField.text.toString().toDoubleOrNull()?.coerceIn(0.1, 30.0) ?: 3.0
        // Everything the app does not own here comes from the stored configuration:
        // starting a route from this screen must not silently reset the signal-realism
        // switches or the interval plan the user chose on the other screen.
        val stored = PacerConfigClient.stored()
        val started = PacerConfigClient.start(
            lat = route.points.first().lat,
            lon = route.points.first().lon,
            speed = speed,
            bearing = 0f,
            accuracy = stored?.accuracy ?: 5f,
            altitudeMeters = stored?.altitude ?: 0.0,
            routeJson = RouteJson.encode(geometry),
            plan = stored?.plan ?: "",
            loopRoute = loopCheck.isChecked,
            mockBluetooth = stored?.mockBluetooth ?: true,
            stepSimulation = stored?.stepSimulation ?: true,
            realism = stored?.realism ?: RealismToggles(),
        )
        if (!started) {
            info.text = PacerConfigClient.NOT_READY_MESSAGE
            return
        }
        info.text = String.format(
            Locale.US,
            "Simulating %d waypoints at %.1f m/s%s.",
            draft.size,
            speed,
            if (loopCheck.isChecked) ", repeating" else "",
        )
        updateLivePosition()
    }

    // ---------------------------------------------------------------------
    // Live position
    // ---------------------------------------------------------------------

    /** Moves the live marker to the position the simulation is reporting now. */
    private fun updateLivePosition() {
        val map = this.map ?: return
        val stored = PacerConfigClient.stored()
        if (stored == null || !stored.active) {
            map.setLive(null)
            return
        }
        map.setLive(livePoint(stored))
    }

    /** Where the subject is, from the stored run: route passes, or the straight line. */
    private fun livePoint(stored: PacerConfigClient.Stored): GeoPoint? {
        val elapsed = ((System.currentTimeMillis() - stored.startedAtMs).coerceAtLeast(0L)) / 1000.0
        val routePath = storedRoute(stored)
        if (routePath != null) {
            // The SAME arithmetic the module runs (RouteEngine), including the seed: the
            // module rebuilds each lap from MotionSeed.forRun(start, lap), so a marker that
            // used any other seed would show a position the target app was never given.
            // The lap length is fixed on the first build of a run, exactly as there.
            if (stored.startedAtMs != liveLapRunStartedAt) {
                liveLapRunStartedAt = stored.startedAtMs
                liveLapSeconds = 0.0
                liveLap = null
            }
            val lap = MotionSamples.lapAt(elapsed, if (stored.loopRoute) liveLapSeconds else 0.0)
            val key = liveKey(stored) + "|lap=" + lap.index
            val samples = liveLap?.takeIf { it.key == key }?.samples
                ?: buildLap(stored, routePath, key, lap.index)
            if (samples.isEmpty()) return null
            val base = MotionSamples.at(samples, lap.offsetSec) ?: return null
            return MotionSamples.afterLaps(
                base,
                lap.index,
                samples.last().distanceMeters,
                samples.last().cumulativeSteps,
                elapsed,
            ).point
        }
        val meters = SpeedPlan.parse(stored.plan)?.metersAt(elapsed) ?: (stored.speed * elapsed)
        return StraightLine.byDistance(GeoPoint(stored.lat, stored.lon), stored.bearing.toDouble(), meters)
    }

    /**
     * Builds one lap of the stored run, seeded exactly as the module seeds it.
     *
     * The lap length is learned from the first build and then kept for the run, matching
     * RouteEngine: a later lap is humanized separately and would otherwise move its own
     * ending.
     */
    private fun buildLap(
        stored: PacerConfigClient.Stored,
        routePath: RoutePath,
        key: String,
        lapIndex: Long,
    ): List<FixSample> {
        val samples = RouteSimulator(
            routePath,
            MotionConfig(
                baseSpeedMps = if (stored.speed > 0.0) stored.speed else 3.0,
                plan = SpeedPlan.parse(stored.plan),
            ),
            toggles = stored.realism,
            steadyAccuracyMeters = stored.accuracy,
        ).simulate(dtSec = 1.0, seed = MotionSeed.forRun(stored.startedAtMs, lapIndex))
        if (stored.loopRoute && liveLapSeconds <= 0.0) {
            liveLapSeconds = samples.lastOrNull()?.tSec ?: 0.0
        }
        liveLap = LiveLap(key, samples)
        return samples
    }

    private fun storedRoute(stored: PacerConfigClient.Stored): RoutePath? {
        if (stored.routeJson.isBlank()) return null
        if (stored.routeJson === parsedRouteJson) return parsedRoute
        val parsed = try {
            val points = RouteJson.decode(stored.routeJson)
            if (points.size >= 2) RoutePath(points) else null
        } catch (_: Throwable) {
            null
        }
        parsedRouteJson = stored.routeJson
        parsedRoute = parsed
        return parsed
    }

    /**
     * Everything that changes the samples, so one lap is rebuilt only when it must be.
     *
     * The route is named by its length and hash rather than its text: this runs once a
     * second on the poll, and a long route is a long string.
     */
    private fun liveKey(stored: PacerConfigClient.Stored): String =
        stored.startedAtMs.toString() + "|" + stored.routeJson.length + ":" +
            stored.routeJson.hashCode() + "|" + stored.speed + "|" +
            stored.plan + "|" + stored.loopRoute + "|" + stored.realism + "|" + stored.accuracy

    private fun updateInfo() {
        info.text = String.format(
            Locale.US,
            "Drag to pan, pinch to zoom, tap to append a waypoint, drag a handle to move it.\n" +
                "Waypoints: %d   Length: %s   Map: %s",
            draft.size,
            if (draft.size < 2) "-" else String.format(Locale.US, "%.0f m", lengthOf(draft.points)),
            style.label + " " + layer.label.lowercase(),
        )
    }

    /** Total length of the waypoints, for the info line. */
    private fun lengthOf(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += GeoMath.haversine(points[i - 1], points[i])
        return total
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private companion object {
        /** Where the map opens when the guard rails are needed; the store usually answers. */
        val DEFAULT_CENTER = GeoPoint(43.8256, 87.6168)
        const val DEFAULT_ZOOM = 13

        /** How often the live marker is moved; the simulation advances in seconds. */
        const val LIVE_POLL_MS = 1000L
    }
}
