package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import org.pacer.motion.MapDefaults
import org.pacer.motion.MotionOwner
import org.pacer.motion.RouteDraft
import java.util.Locale

/**
 * The operation page: a map, the waypoints placed on it, and the run's own controls.
 *
 * A route is made by dragging the map, tapping to append and dragging a handle to move one;
 * there is no coordinate field, no search box and no road routing, each of which needs a key
 * or a network dependency.
 *
 * The live marker is derived from the same stored run the hook reads, not read back from the
 * module, so the map cannot drift from what the target app is told.
 */
class MapActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft
    private lateinit var settings: AppSettings
    private lateinit var strings: Strings

    private lateinit var info: TextView
    private lateinit var mapHolder: LinearLayout
    private lateinit var styleRow: LinearLayout
    private lateinit var layerRow: LinearLayout

    private var style: MapStyle = MapStyle.DEFAULT
    private var layer: MapLayer = MapLayer.ROAD
    private var map: TileMapView? = null

    /** Enabled only while a waypoint is selected. */
    private lateinit var removeButton: Button

    /**
     * The waypoint last tapped, or -1.
     *
     * An index rather than a point, so a drag cannot leave the selection naming coordinates
     * that no longer exist.
     */
    private var selectedWaypoint = -1

    /**
     * A transient line above the state line.
     *
     * Separate from the state line because [updateInfo] runs once a second and would erase a
     * message before it could be read.
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
            // Edge-to-edge on API 35: the map would otherwise run under the status bar.
            fitsSystemWindows = true
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
        editRow.addView(button(strings.clear) { clearDraftRoute() })
        removeButton = button(strings.remove) { removeSelectedWaypoint() }
        removeButton.isEnabled = false
        editRow.addView(removeButton)
        column.addView(editRow)

        // The sample route belongs here rather than on the route page: it is a way to put
        // points down, which is what this page does.
        val sampleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sampleRow.addView(button(strings.loadSample) {
            editDraft {
                draft.clear()
                SAMPLE_ROUTE.forEach { draft.add(it) }
            }
        })
        column.addView(sampleRow)

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

        // NO START, STOP, PAUSE, SPEED OR LOOP HERE.
        //
        // This page places points; the control page starts the run. Both used to offer the
        // same actions, which meant the same run could be started from two places and the
        // speed shown here could disagree with the speed actually used. One action, one
        // home is the rule this page now follows.
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
        PacerConfigClient.onReady { runOnUiThread { updateInfo() } }
    }

    /**
     * Creates the map for the chosen basemap, or replaces the current one.
     *
     * The camera is carried across a switch: the user picked a place, not a projection.
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
            initialCenter = center ?: draft.points.lastOrNull() ?: MapDefaults.CENTER,
            initialZoom = previous?.zoom ?: MapDefaults.ZOOM,
        )
        created.onTap = { point -> editDraft { draft.add(point) } }
        created.onWaypointTap = { index -> selectWaypoint(index) }
        created.onWaypointMoving = { index, point ->
            // Nothing saved here: a save per motion event would write prefs hundreds of
            // times a second. The drop is the moment the route changed.
            if (index < draft.size) {
                draft.replaceAt(index, point)
                created.setRoute(draft.points.toList(), draft.points.toList())
                updateInfo()
            }
        }
        created.onWaypointDropped = { index, point ->
            // Forced: the drag has been writing the draft all along so the map and the
            // length follow the finger, so the draft already holds this point and a
            // difference check would find nothing to save. The drop is what makes it real.
            editDraft(force = true) { if (index < draft.size) draft.replaceAt(index, point) }
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
     * Rebuilt rather than disabled in place: the set differs by source, so a dead Satellite
     * button would promise a layer no tap can reach.
     */
    private fun buildLayerRow() {
        layerRow.removeAllViews()
        layerRow.visibility = if (style.layers.size > 1) View.VISIBLE else View.GONE
        for (candidate in style.layers) {
            val b = button(candidate.label) {
                if (candidate == layer) return@button
                layer = candidate
                store.saveMapLayer(candidate)
                installMap()
                render()
            }
            // Equal shares: the labels are long enough to push a third button off screen.
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
     *
     * [force] saves even when the JSON is unchanged, for a caller that has already mutated
     * the draft in place and now only wants it written.
     */
    private fun editDraft(force: Boolean = false, change: () -> Unit) {
        val before = draft.toJson()
        change()
        if (!force && draft.toJson() == before) return
        // An index read before the edit may now name a different point.
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
     * A second tap deselects, so a mis-tap cannot cost the point.
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

    /**
     * Clears every waypoint, asking first when there is more than a stray point to lose.
     *
     * Moved here from the route page, which had its own copy: clearing is an edit to the
     * points, and this page is where the points are.
     */
    private fun clearDraftRoute() {
        if (draft.size < 2) {
            editDraft { draft.clear() }
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(strings.clearRouteTitle)
            .setMessage(String.format(Locale.US, strings.clearRouteMessage, draft.size))
            .setPositiveButton(strings.clear) { _, _ -> editDraft { draft.clear() } }
            .setNegativeButton(strings.cancel, null)
            .show()
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
            setNotice(strings.nothingToCentre)
            return
        }
        map?.fitRoute(points)
    }

    /** Centres on the subject, or on the last waypoint when nothing is running. */
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
     * A route run is refused: its position comes from its geometry, so moving the anchor
     * would change nothing.
     */
    private fun relocate(point: GeoPoint) {
        when (PacerConfigClient.owner()) {
            MotionOwner.ROUTE, MotionOwner.ROUTE_PAUSED -> setNotice(strings.routeCannotMove)
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
        // The marker walks a route; the line above it has to describe the same point, or the
        // page shows the run's start while the dot is elsewhere. The stored lat/lon is only
        // the anchor a straight run was launched from.
        val live = stored?.let { livePoint(it) }
        val point = live ?: stored?.let { GeoPoint(it.lat, it.lon) }
        val state = when {
            stored == null -> strings.statusIdle
            !stored.active -> strings.statusConfigIdle
            stored.routePaused -> "${strings.statusPaused}  ${formatPoint(point)}"
            else -> "${strings.statusRunning}  ${formatPoint(point)}"
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
                    strings.mapRouteSummary,
                    draft.size,
                    if (draft.size < 2) "-" else String.format(Locale.US, "%.0f m", lengthOf(draft.points)),
                    style.label + " " + layer.label.lowercase(),
                ),
            )
        }
    }

    private fun formatPoint(point: GeoPoint?): String =
        if (point == null) "-" else String.format(Locale.US, "%.5f, %.5f", point.lat, point.lon)

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
        /** How often the live marker is moved; the simulation advances in seconds. */
        const val LIVE_POLL_MS = 1000L

        /**
         * A sample loop in Urumqi, the same place the map opens on.
         *
         * Somewhere almost no user is, so it cannot be mistaken for their own position.
         * Inside China because AMap and Baidu serve blank tiles outside it. Lives here now
         * that the button that uses it is on this page.
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
