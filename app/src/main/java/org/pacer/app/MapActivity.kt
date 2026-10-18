package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionSamples
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteDraft
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan
import org.pacer.motion.StraightLine
import java.io.File
import java.util.Locale

/**
 * The operation page: the route on a map, the simulation controls, and a marker for
 * where the simulated subject currently is.
 *
 * Tapping appends a waypoint, dragging one moves it, and a long press elsewhere moves a
 * running straight-line simulation. The draft is saved on every change so the other
 * screen sees it, and it is the single source of truth for the route.
 *
 * The live marker is DERIVED, not read from the module: it is computed with the same
 * core-motion code the hook side runs, from the same stored inputs, so a marker that
 * disagrees with what the target app is told would be a bug in one shared formula rather
 * than drift between two copies.
 *
 * The map is created inside a try/catch and the screen degrades to a plain message if the
 * tile provider cannot start, so a tile problem can never take the app down.
 */
class MapActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft
    private lateinit var info: TextView
    private var map: MapView? = null

    private lateinit var speedField: EditText
    private lateinit var loopCheck: CheckBox

    /** Where the simulated subject is now, drawn on top of the route. */
    private var liveMarker: Marker? = null

    /** One lap of the stored route, rebuilt only when the stored run changes. */
    private class LiveRoute(val key: String, val samples: List<org.pacer.motion.FixSample>)

    private var liveRoute: LiveRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        draft = store.load()
        PacerConfigClient.init()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 8) }
        root.addView(info)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("Undo last point") {
            if (draft.size > 0) draft.removeAt(draft.size - 1)
            store.save(draft)
            render()
        })
        bar.addView(button("Clear route") {
            draft.clear()
            store.save(draft)
            render()
        })
        root.addView(bar)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Start this route") { startSimulation() })
        controls.addView(button("Stop") {
            PacerConfigClient.stop()
            info.text = "Simulation stopped."
            updateLivePosition()
        })
        controls.addView(TextView(this).apply { text = "  Speed m/s "; textSize = 13f })
        speedField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.0")
            width = 140
        }
        controls.addView(speedField)
        loopCheck = CheckBox(this).apply { text = "Loop" }
        controls.addView(loopCheck)
        root.addView(controls)

        map = try {
            createMap()
        } catch (t: Throwable) {
            TextView(this).apply {
                text = "Map unavailable: ${t.javaClass.simpleName}: ${t.message}"
                textSize = 14f
                setPadding(24, 24, 24, 24)
            }.also { root.addView(it) }
            null
        }
        val mv = map
        if (mv != null) {
            root.addView(
                mv,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        setContentView(root)
        render()
        startLivePolling()
        // The controls open on the stored run, for the same reason the other screen does:
        // a screen that opens on its own defaults silently rewrites the user's settings
        // the next time it starts something.
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

    override fun onResume() {
        super.onResume()
        map?.onResume()
        // Reload in case another screen changed the draft while we were away.
        draft = store.load()
        render()
    }

    override fun onPause() {
        map?.onPause()
        super.onPause()
    }

    private fun createMap(): MapView {
        val ctx = applicationContext
        // osmdroid needs a writable tile cache before the first MapView exists.
        // Point it at app-private storage so no external-storage permission is
        // needed on any supported release.
        val config = Configuration.getInstance()
        config.load(ctx, getSharedPreferences("osmdroid", MODE_PRIVATE))
        config.userAgentValue = USER_AGENT
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles")

        val view = MapView(this)
        view.setTileSource(TileSourceFactory.MAPNIK)
        view.setMultiTouchControls(true)
        view.setBuiltInZoomControls(false)
        view.controller.setZoom(15.0)
        val center = draft.points.lastOrNull() ?: DEFAULT_CENTER
        view.controller.setCenter(OsmGeoPoint(center.lat, center.lon))
        return view
    }

    /** Redraws every overlay from the draft; the draft is the single source. */
    private fun render() {
        updateInfo()
        val view = map ?: return

        view.overlays.clear()
        // Cleared with the overlays, so the next poll adds a fresh one.
        liveMarker = null
        val points = draft.points.map { OsmGeoPoint(it.lat, it.lon) }

        if (points.size >= 2) {
            val line = Polyline()
            line.setPoints(points)
            line.outlinePaint.color = ROUTE_COLOR
            line.outlinePaint.strokeWidth = 8f
            view.overlays.add(line)
        }
        for ((index, p) in points.withIndex()) {
            val marker = Marker(view)
            marker.position = p
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // A waypoint can be dragged to where it really is: the draft is the single
            // source of truth, so the drag writes through it and re-renders. Re-rendering
            // is posted because this runs while osmdroid is still iterating its overlays.
            marker.isDraggable = true
            marker.setOnMarkerDragListener(
                object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) = Unit

                    override fun onMarkerDrag(marker: Marker) = Unit

                    override fun onMarkerDragEnd(marker: Marker) {
                        val pos = marker.position ?: return
                        if (!draft.replaceAt(index, GeoPoint(pos.latitude, pos.longitude))) return
                        store.save(draft)
                        view.post { render() }
                    }
                },
            )
            view.overlays.add(marker)
        }

        // Tap to append a waypoint. Added last so it sits above the markers.
        view.overlays.add(
            MapEventsOverlay(
                object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                        draft.add(GeoPoint(p.latitude, p.longitude))
                        store.save(draft)
                        render()
                        return true
                    }

                    override fun longPressHelper(p: OsmGeoPoint): Boolean {
                        // Two long-press meanings share the map, so they are told apart by
                        // what is under the finger: a waypoint means "drag me", anything
                        // else means "move the running simulation here". Returning false
                        // hands the gesture to the marker underneath; consuming it here
                        // would make markers impossible to drag.
                        if (isOnWaypoint(p) || !PacerConfigClient.canTeleport()) return false
                        PacerConfigClient.teleport(p.latitude, p.longitude)
                        info.text = String.format(
                            Locale.US,
                            "Simulation moved to %.5f, %.5f.",
                            p.latitude,
                            p.longitude,
                        )
                        return true
                    }
                },
            ),
        )
        view.invalidate()
        updateLivePosition()
    }

    // ---------------------------------------------------------------------
    // Simulation control
    // ---------------------------------------------------------------------

    private fun startSimulation() {
        val route = draft.toRoutePath()
        if (route == null) {
            info.text = "Add at least two waypoints before starting."
            return
        }
        val speed = speedField.text.toString().toDoubleOrNull()?.coerceIn(0.1, 30.0) ?: 3.0
        // Everything the app does not own here comes from the stored configuration:
        // starting a route from this screen must not silently reset the signal-realism
        // switches or the interval plan the user chose on the other screen.
        val stored = PacerConfigClient.stored()
        PacerConfigClient.start(
            lat = route.points.first().lat,
            lon = route.points.first().lon,
            speed = speed,
            bearing = 0f,
            accuracy = stored?.accuracy ?: 5f,
            altitudeMeters = stored?.altitude ?: 0.0,
            routeJson = draft.toJson(),
            plan = stored?.plan ?: "",
            loopRoute = loopCheck.isChecked,
            mockBluetooth = stored?.mockBluetooth ?: true,
            stepSimulation = stored?.stepSimulation ?: true,
            realism = stored?.realism ?: RealismToggles(),
        )
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

    private fun startLivePolling() {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    updateLivePosition()
                } catch (_: Throwable) {
                } finally {
                    handler.postDelayed(this, LIVE_POLL_MS)
                }
            }
        }
        handler.postDelayed(tick, LIVE_POLL_MS)
    }

    /**
     * Moves the live marker to the position the simulation is reporting now.
     *
     * Derived with the same core-motion code the hook side runs, from the same stored
     * inputs, so the marker cannot drift from what the target app is being told. The one
     * lap of samples is cached and only rebuilt when the stored run changes.
     */
    private fun updateLivePosition() {
        val view = map ?: return
        val stored = PacerConfigClient.stored()
        if (stored == null || !stored.active) {
            liveMarker?.let { view.overlays.remove(it) }
            liveMarker = null
            view.invalidate()
            return
        }
        val point = livePoint(stored) ?: return
        val marker = liveMarker ?: Marker(view).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Simulated position"
            icon?.setTint(LIVE_COLOR)
        }.also {
            liveMarker = it
            view.overlays.add(it)
        }
        marker.position = OsmGeoPoint(point.lat, point.lon)
        view.invalidate()
    }

    /** Where the subject is, from the stored run: route laps, or the straight line. */
    private fun livePoint(stored: PacerConfigClient.Stored): GeoPoint? {
        val elapsed = ((System.currentTimeMillis() - stored.startedAtMs).coerceAtLeast(0L)) / 1000.0
        val routePath = storedRoute(stored)
        if (routePath != null) {
            val key = liveKey(stored)
            val samples = liveRoute?.takeIf { it.key == key }?.samples
                ?: RouteSimulator(
                    routePath,
                    MotionConfig(
                        baseSpeedMps = if (stored.speed > 0.0) stored.speed else 3.0,
                        plan = SpeedPlan.parse(stored.plan),
                    ),
                    toggles = stored.realism,
                    steadyAccuracyMeters = stored.accuracy,
                ).simulate(dtSec = 1.0).also { liveRoute = LiveRoute(key, it) }
            if (samples.isEmpty()) return null
            val lapSeconds = if (stored.loopRoute) samples.last().tSec else 0.0
            val lap = MotionSamples.lapAt(elapsed, lapSeconds)
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

    private fun storedRoute(stored: PacerConfigClient.Stored): RoutePath? {
        if (stored.routeJson.isBlank()) return null
        return try {
            val points = RouteJson.decode(stored.routeJson)
            if (points.size >= 2) RoutePath(points) else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Everything that changes the samples, so one lap is rebuilt only when it must be. */
    private fun liveKey(stored: PacerConfigClient.Stored): String =
        stored.routeJson + "|" + stored.speed + "|" + stored.plan + "|" + stored.loopRoute + "|" +
            stored.realism + "|" + stored.accuracy

    /**
     * True when [p] is close enough to an existing waypoint to mean "drag that one".
     *
     * Measured in screen pixels, not metres: how close a finger has to be depends on the
     * zoom, and a fixed metre radius would be uselessly tight when zoomed out. The
     * conversion is osmdroid's own and is only exact at the equator, which is fine for a
     * grab radius.
     */
    private fun isOnWaypoint(p: OsmGeoPoint): Boolean {
        val view = map ?: return false
        val pxPerMeter = view.projection.metersToEquatorPixels(1f).toDouble()
        if (pxPerMeter <= 0.0) return false
        val grabMeters = WAYPOINT_GRAB_PIXELS / pxPerMeter
        val here = GeoPoint(p.latitude, p.longitude)
        return draft.points.any { GeoMath.haversine(here, it) <= grabMeters }
    }

    private fun updateInfo() {
        info.text = String.format(
            Locale.US,
            "Tap to append a waypoint; drag a waypoint to move it; long press elsewhere to " +
                "move a running simulation here." +
                "  Waypoints: %d   Length: %.0f m",
            draft.size,
            draft.totalLengthMeters(),
        )
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private companion object {
        const val USER_AGENT = "Pacer/1.0 (Android research tool; https://github.com/925316/Pacer)"
        val ROUTE_COLOR = 0xFF1E88E5.toInt()
        val LIVE_COLOR = 0xFFE53935.toInt()
        val DEFAULT_CENTER = GeoPoint(39.9042, 116.4074)

        /** Finger radius, in screen pixels, that counts as touching a waypoint. */
        const val WAYPOINT_GRAB_PIXELS = 28.0

        /** How often the live marker is moved; the simulation advances in seconds. */
        const val LIVE_POLL_MS = 1000L
    }
}
