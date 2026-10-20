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
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionOwner
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteDraft
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
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
    private lateinit var settings: AppSettings
    private lateinit var strings: Strings

    private lateinit var info: TextView
    private lateinit var speedField: EditText
    private lateinit var loopCheck: CheckBox
    private lateinit var mapHolder: LinearLayout
    private lateinit var styleRow: LinearLayout
    private lateinit var layerRow: LinearLayout

    private var style: MapStyle = MapStyle.DEFAULT
    private var layer: MapLayer = MapLayer.ROAD
    private var map: TileMapView? = null

    /** startedAtMs of the run whose speed/loop fields this page last adopted. */
    private var lastSyncedStartedAt = Long.MIN_VALUE

    /** Enabled only while a waypoint is selected. */
    private lateinit var removeButton: Button

    /** Pauses or resumes a route run; its label follows the stored state. */
    private lateinit var pauseButton: Button
    private lateinit var pauseRow: LinearLayout

    /**
     * The waypoint last tapped on the map, or -1.
     *
     * Selection exists because the only per-waypoint edit the map can offer is "remove this
     * one": reordering already has a list on the other screen. An index rather than a point,
     * so a drag cannot leave the selection naming coordinates that no longer exist.
     */
    private var selectedWaypoint = -1

    /**
     * A transient line above the state line, cleared when the selection changes.
     *
     * Kept separate from [updateInfo] because that method runs once a second from the
     * poll, and a message written into the same TextView would disappear before it was
     * read. This is the one place on the page that reports a consequence of a tap.
     */
    private var notice: String? = null

    private fun setNotice(text: String?) {
        notice = text
        updateInfo()
    }

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
                // The state line follows the stored run, not the last tap: a run can be
                // stopped from the other page, and a line that kept saying "Running"
                // would be the very kind of stale status this page exists to avoid.
                updateInfo()
                // A pause can be toggled from this page or from the other one; the
                // control has to follow the state rather than the last tap.
                updatePauseControl()
            } catch (_: Throwable) {
            } finally {
                if (polling) handler.postDelayed(this, LIVE_POLL_MS)
            }
        }
    }

    /**
     * The stored run's position, shared with anything else that has to know where the
     * subject is. One instance per poller: it caches the lap it last built.
     */
    private val subject = LiveSubject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        draft = store.load()
        settings = AppSettings(this)
        strings = stringsFor(settings.language)
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
        editRow.addView(button(strings.undo) {
            editDraft { if (draft.size > 0) draft.removeAt(draft.size - 1) }
        })
        editRow.addView(button(strings.clear) {
            // Clearing is the one action here that cannot be undone point by point, and
            // the button sits next to Undo. Asking once costs a tap; not asking costs a
            // route the user has to redraw from memory.
            if (draft.size < 2) {
                editDraft { draft.clear() }
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle(strings.clearRouteTitle)
                    .setMessage(String.format(Locale.US, strings.clearRouteMessage, draft.size))
                    .setPositiveButton(strings.clear) { _, _ -> editDraft { draft.clear() } }
                    .setNegativeButton(strings.cancel, null)
                    .show()
            }
        })
        removeButton = button(strings.remove) { removeSelectedWaypoint() }
        removeButton.isEnabled = false
        editRow.addView(removeButton)
        column.addView(editRow)

        val viewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        viewRow.addView(button(strings.fit) { fitRoute() })
        viewRow.addView(button(strings.centre) { centreOnSubject() })
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
        controls.addView(button(strings.start) { startSimulation() })
        controls.addView(button(strings.stop) {
            if (PacerConfigClient.stop()) {
                setNotice(strings.statusConfigIdle)
            } else {
                setNotice(PacerConfigClient.NOT_READY_MESSAGE)
            }
            updatePauseControl()
            updateLivePosition()
        })
        controls.addView(TextView(this).apply { text = " ${strings.speedMpsLabel} "; textSize = 13f })
        speedField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        controls.addView(speedField)
        loopCheck = CheckBox(this).apply { text = strings.loopShort }
        controls.addView(loopCheck)
        column.addView(controls)

        // Pause is a second row because it is a different kind of action: it holds the run
        // where it is, while Start replaces it and Stop ends it.
        pauseRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = button(strings.pauseRoute) { toggleRoutePause() }
        pauseRow.addView(pauseButton)
        column.addView(pauseRow)

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
                syncRunFields()
                updatePauseControl()
            }
        }
    }

    /** The run settings this page shares with the main screen. */
    private fun syncRunFields() {
        val stored = PacerConfigClient.stored() ?: return
        lastSyncedStartedAt = stored.startedAtMs
        if (stored.speed > 0.0) {
            speedField.setText(String.format(Locale.US, "%.1f", stored.speed))
        }
        loopCheck.isChecked = stored.loopRoute
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
                    setNotice(
                        String.format(
                            Locale.US,
                            strings.noTilesForLayer,
                            style.label,
                            dropped.label.lowercase(),
                        ),
                    )
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
        // A waypoint index from before the reload no longer names the same point when the
        // other screen removed or reordered one; a stale Remove button would delete the
        // wrong waypoint.
        clearSelection()
        // Another screen may have started a run with different speed/loop settings. Only a
        // NEW run is adopted, so a value being typed here is not wiped by a permission
        // dialog pausing and resuming the page.
        val stored = PacerConfigClient.stored()
        if (stored != null && stored.startedAtMs != lastSyncedStartedAt) syncRunFields()
        updatePauseControl()
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
        setNotice(
            String.format(
                Locale.US,
                strings.waypointSelected,
                index + 1,
                draft.size,
            ),
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

    /**
     * Freezes the running route where it stands, or lets it continue.
     *
     * Pausing is a clock operation, not a position write: the module keeps reading the
     * same run and stops advancing it, so the coordinates, the step count, the distance
     * and the lap all remain what that one run says. Nothing is teleported and nothing
     * restarts when it is resumed.
     */
    private fun toggleRoutePause() {
        val owner = PacerConfigClient.owner()
        val pause = owner.canPauseRoute
        if (!pause && !owner.canResumeRoute) {
            setNotice(strings.onlyRunningRouteCanPause)
            updatePauseControl()
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
        updatePauseControl()
        updateLivePosition()
    }

    /**
     * Shows the pause control only when it can do something.
     *
     * A button that is always present but usually inert is the same class of lie as a
     * button that does nothing: it makes the user check whether the run is a route by
     * tapping it. The label states which way the next tap goes.
     */
    private fun updatePauseControl() {
        if (!::pauseButton.isInitialized) return
        val owner = PacerConfigClient.owner()
        pauseRow.visibility = if (owner.canPauseRoute || owner.canResumeRoute) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        pauseButton.text = if (owner.canResumeRoute) strings.resumeRoute else strings.pauseRoute
    }

    // ---------------------------------------------------------------------
    // Map commands
    // ---------------------------------------------------------------------

    private fun fitRoute() {
        val points = draft.points.toList()
        if (points.isEmpty()) {
            setNotice(strings.placeWaypointFirst)
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
            setNotice(strings.nothingToCentre)
            return
        }
        map.centerOn(target)
        setNotice(
            if (subject != null) {
                String.format(Locale.US, strings.centredOnSubject, target.lat, target.lon)
            } else {
                strings.centredOnLast
            },
        )
    }

    /**
     * Moves a running straight-line simulation to the point under a long press.
     *
     * A route run is refused rather than moved: its position comes from its geometry, so
     * moving the anchor would change nothing, and a press that quietly does nothing is
     * worse than one that says why.
     */
    private fun relocate(point: GeoPoint) {
        when (PacerConfigClient.owner()) {
            MotionOwner.ROUTE -> setNotice(strings.routeCannotMove)
            MotionOwner.PLAN, MotionOwner.HAND -> {
                PacerConfigClient.teleport(point.lat, point.lon)
                setNotice(
                    String.format(
                        Locale.US,
                        strings.movedTo,
                        point.lat,
                        point.lon,
                    ),
                )
                updateLivePosition()
            }
            else -> setNotice(strings.longPressIdle)
        }
    }

    // ---------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------

    private fun startSimulation(confirmed: Boolean = false) {
        if (!confirmed && PacerConfigClient.owner() != MotionOwner.NONE) {
            android.app.AlertDialog.Builder(this)
                .setTitle(strings.replaceRunTitle)
                .setMessage(strings.replaceRunMessage)
                .setPositiveButton(strings.replaceRunConfirm) { _, _ -> startSimulation(true) }
                .setNegativeButton(strings.cancel, null)
                .show()
            return
        }
        if (!draft.isValid) {
            setNotice(strings.needTwoWaypoints)
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
            setNotice(PacerConfigClient.NOT_READY_MESSAGE)
            return
        }
        val speedFromPlan = !stored?.plan.isNullOrBlank()
        setNotice(
            if (speedFromPlan) {
                String.format(
                    Locale.US,
                    strings.simulatingWithPlan,
                    draft.size,
                    if (loopCheck.isChecked) strings.repeatingSuffix else "",
                )
            } else {
                String.format(
                    Locale.US,
                    strings.simulatingAtSpeed,
                    draft.size,
                    speed,
                    if (loopCheck.isChecked) strings.repeatingSuffix else "",
                )
            },
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
    private fun livePoint(stored: PacerConfigClient.Stored): GeoPoint? = subject.point(stored)

    private fun updateInfo() {
        val stored = PacerConfigClient.stored()
        val state = when {
            stored == null -> strings.statusIdle
            !stored.active -> strings.statusConfigIdle
            stored.routePaused -> "${strings.statusPaused}  ${stored.lat}, ${stored.lon}"
            else -> "${strings.statusRunning}  ${String.format(Locale.US, "%.5f", stored.lat)}, " +
                String.format(Locale.US, "%.5f", stored.lon)
        }
        info.text = buildString {
            val n = notice
            if (n != null) {
                appendLine(n)
            }
            append(state)
            append('\n')
            appendLine(strings.mapGestureHint)
            append(
                String.format(
                    Locale.US,
                    "Waypoints: %d   Length: %s   Map: %s",
                    draft.size,
                    if (draft.size < 2) "-" else String.format(Locale.US, "%.0f m", lengthOf(draft.points)),
                    style.label + " " + layer.label.lowercase(),
                ),
            )
        }
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
