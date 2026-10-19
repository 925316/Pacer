package org.pacer.app

import android.content.Context
import android.os.Bundle
import android.view.View
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.pacer.motion.GeoMath
import org.pacer.motion.GeoPoint
import java.io.File

/**
 * The OpenStreetMap backend: osmdroid tiles, WGS-84, which is the datum the routes are
 * already in, so nothing is converted at this boundary.
 *
 * No key and no proprietary dependency, which is why it is the fallback for a build that
 * carries no AMap key. The tile store is app-private, so no storage permission is needed.
 */
internal class OsmRouteMap(context: Context) : RouteMap {

    override val name: String get() = "osmdroid"

    private val mapView: MapView

    override val view: View get() = mapView

    override var onTap: ((GeoPoint) -> Unit)? = null
    override var onWaypointDragEnd: ((Int, GeoPoint) -> Unit)? = null
    override var onWaypointTap: ((Int) -> Unit)? = null
    override var onLongPress: ((GeoPoint) -> Unit)? = null

    private var waypoints: List<GeoPoint> = emptyList()

    /** The drawn path; the waypoint handles sit on a subset of it. */
    private var geometry: List<GeoPoint> = emptyList()

    /** Created on demand: clearing the overlays throws it away with them. */
    private var liveMarker: Marker? = null

    init {
        // Configure before the view exists: osmdroid reads the tile cache path while the
        // first MapView is being constructed.
        val config = Configuration.getInstance()
        config.load(context, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        config.userAgentValue = USER_AGENT
        val base = File(context.cacheDir, "osmdroid").apply { mkdirs() }
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles")

        mapView = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(DEFAULT_ZOOM)
        }
    }

    override fun showRoute(geometry: List<GeoPoint>, waypoints: List<GeoPoint>) {
        this.geometry = geometry
        this.waypoints = waypoints
        mapView.overlays.clear()
        // It goes with the overlays; the page asks for it again on the next poll.
        liveMarker = null

        val positions = geometry.map { OsmGeoPoint(it.lat, it.lon) }
        if (positions.size >= 2) {
            mapView.overlays.add(
                Polyline().apply {
                    setPoints(positions)
                    outlinePaint.color = ROUTE_COLOR
                    outlinePaint.strokeWidth = 8f
                },
            )
        }
        for ((index, waypoint) in waypoints.withIndex()) {
            val marker = Marker(mapView)
            marker.position = OsmGeoPoint(waypoint.lat, waypoint.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.isDraggable = true
            marker.setOnMarkerClickListener { _, _ ->
                onWaypointTap?.invoke(index)
                true
            }
            marker.setOnMarkerDragListener(
                object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) = Unit

                    override fun onMarkerDrag(marker: Marker) = Unit

                    override fun onMarkerDragEnd(marker: Marker) {
                        val landed = marker.position ?: return
                        val point = GeoPoint(landed.latitude, landed.longitude)
                        // Posted, because this runs while osmdroid is still iterating its
                        // overlays and the page answers by drawing the route again.
                        mapView.post { onWaypointDragEnd?.invoke(index, point) }
                    }
                },
            )
            mapView.overlays.add(marker)
        }

        // Added last so it receives touches before the markers.
        mapView.overlays.add(
            MapEventsOverlay(
                object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                        // Same rule as the long press below: on a waypoint the tap is the
                        // marker's, which is what opens its label instead of appending yet
                        // another point next to the one under the finger.
                        if (isOnWaypoint(p)) return false
                        val handler = onTap ?: return false
                        handler(GeoPoint(p.latitude, p.longitude))
                        return true
                    }

                    override fun longPressHelper(p: OsmGeoPoint): Boolean {
                        // A long press on a waypoint belongs to that marker's drag, so it
                        // is handed to the overlay underneath; consuming it here would make
                        // markers impossible to move at all.
                        if (isOnWaypoint(p)) return false
                        val handler = onLongPress ?: return false
                        handler(GeoPoint(p.latitude, p.longitude))
                        return true
                    }
                },
            ),
        )
        mapView.invalidate()
    }

    override fun showLive(point: GeoPoint?) {
        if (point == null) {
            liveMarker?.let { mapView.overlays.remove(it) }
            liveMarker = null
            mapView.invalidate()
            return
        }
        val marker = liveMarker ?: Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Simulated position"
            icon?.setTint(LIVE_COLOR)
        }.also {
            liveMarker = it
            mapView.overlays.add(it)
        }
        marker.position = OsmGeoPoint(point.lat, point.lon)
        mapView.invalidate()
    }

    override fun centerOn(point: GeoPoint) {
        mapView.controller.setCenter(OsmGeoPoint(point.lat, point.lon))
    }

    override fun fitRoute() {
        val positions = geometry.map { OsmGeoPoint(it.lat, it.lon) }.ifEmpty {
            waypoints.map { OsmGeoPoint(it.lat, it.lon) }
        }
        if (positions.size < 2) return
        val box = BoundingBox.fromGeoPoints(positions)
        // A degenerate box (two points on top of each other) has no size to fit, and
        // zoomToBoundingBox answers that by jumping to the maximum zoom.
        if (box.latNorth - box.latSouth < MIN_FIT_SPAN_DEG && box.lonEast - box.lonWest < MIN_FIT_SPAN_DEG) {
            centerOn(GeoPoint(box.latNorth, box.lonEast))
            return
        }
        mapView.zoomToBoundingBox(box, true, FIT_PADDING_PIXELS)
    }

    /** osmdroid needs nothing here: it has no native resources to restore. */
    override fun onCreate(savedInstanceState: Bundle?) = Unit

    override fun onResume() {
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
    }

    override fun onDestroy() {
        mapView.onDetach()
    }

    /**
     * True when [p] is close enough to a waypoint to mean "drag that one".
     *
     * Measured in screen pixels, not metres: how close a finger has to be depends on the
     * zoom, and a fixed metre radius would be uselessly tight when zoomed out. The
     * conversion is osmdroid's own and is only exact at the equator, which is fine for a
     * grab radius.
     */
    private fun isOnWaypoint(p: OsmGeoPoint): Boolean {
        val pxPerMeter = mapView.projection.metersToEquatorPixels(1f).toDouble()
        if (pxPerMeter <= 0.0) return false
        val grabMeters = WAYPOINT_GRAB_PIXELS / pxPerMeter
        val here = GeoPoint(p.latitude, p.longitude)
        return waypoints.any { GeoMath.haversine(here, it) <= grabMeters }
    }

    private companion object {
        const val USER_AGENT = "Pacer/1.0 (Android research tool; https://github.com/925316/Pacer)"
        const val PREFS = "osmdroid"
        const val DEFAULT_ZOOM = 15.0

        /** Finger radius, in screen pixels, that counts as touching a waypoint. */
        const val WAYPOINT_GRAB_PIXELS = 28.0

        /** Breathing room around a fitted route, in pixels. */
        const val FIT_PADDING_PIXELS = 64

        /** Below this span (about a metre) a route box has nothing to zoom to. */
        const val MIN_FIT_SPAN_DEG = 1e-5

        val ROUTE_COLOR = 0xFF1E88E5.toInt()
        val LIVE_COLOR = 0xFFE53935.toInt()
    }
}
