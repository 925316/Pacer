package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
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
import org.pacer.motion.RouteDraft
import java.io.File
import java.util.Locale

/**
 * Route picker on OpenStreetMap tiles via osmdroid (no API key, fits the
 * no-backend rule). Tapping the map appends a waypoint to the shared
 * [RouteDraft]; the draft is saved on every change so [MainActivity] picks it up
 * when this screen closes.
 *
 * The map is created inside a try/catch and the screen degrades to a plain
 * message if the tile provider cannot start, so a tile problem can never take
 * the app down. All state lives in the draft, never in the map.
 */
class MapActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft
    private lateinit var info: TextView
    private var map: MapView? = null

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
    }

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
        val DEFAULT_CENTER = GeoPoint(39.9042, 116.4074)

        /** Finger radius, in screen pixels, that counts as touching a waypoint. */
        const val WAYPOINT_GRAB_PIXELS = 28.0
    }
}
