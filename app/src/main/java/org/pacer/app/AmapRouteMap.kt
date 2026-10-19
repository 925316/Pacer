package org.pacer.app

import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import org.pacer.motion.CoordinateTransform
import org.pacer.motion.GeoPoint
import kotlin.math.hypot

/**
 * The AMap backend: AMap's own tiles, and therefore GCJ-02.
 *
 * Two things are specific to this SDK and are contained here rather than leaking into the
 * page:
 *
 * - THE DATUM. AMap draws GCJ-02, so every coordinate crossing this boundary is converted:
 *   routes go out as GCJ-02, and taps and marker drags come back as WGS-84. Skipping this
 *   would place the whole route a few hundred metres from where the app thinks it is, and
 *   the app and the module would then disagree about the route they share.
 * - THE PRIVACY SWITCH. The SDK refuses to draw until its privacy flags are set, and the
 *   notice those flags refer to is the app's own: this class only states what the app has
 *   already told the user.
 *
 * Its key is per package and signing certificate, so it comes from the manifest (see
 * app/build.gradle.kts) and an unkeyed build never reaches this class.
 */
internal class AmapRouteMap(context: Context) : RouteMap {

    override val name: String get() = "AMap"

    private val mapView: MapView
    private val aMap: AMap

    override val view: View get() = mapView

    override var onTap: ((GeoPoint) -> Unit)? = null
    override var onWaypointDragEnd: ((Int, GeoPoint) -> Unit)? = null
    override var onLongPress: ((GeoPoint) -> Unit)? = null

    private var waypoints: List<GeoPoint> = emptyList()

    /** The drawn path; the waypoint handles sit on a subset of it. */
    private var geometry: List<GeoPoint> = emptyList()

    /** Parallel to [waypoints], so a dragged marker can be told which waypoint it is. */
    private var waypointMarkers: MutableList<Marker> = ArrayList()

    private var liveMarker: Marker? = null

    /** Whether the map has had its initial camera; see [centerOn]. */
    private var cameraSet = false

    init {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        mapView = MapView(context)
        // A rejected key leaves this null, which is exactly the failure the factory turns
        // into "use the other backend".
        aMap = mapView.map ?: throw IllegalStateException("AMap returned no map (key rejected?)")
        aMap.setOnMapClickListener { latLng ->
            onTap?.invoke(toWgs84(latLng))
        }
        aMap.setOnMapLongClickListener { latLng ->
            // Same rule as the other backend: a long press on a waypoint is a drag.
            val tapped = toWgs84(latLng)
            if (!isOnWaypoint(tapped)) onLongPress?.invoke(tapped)
        }
        // A tap on a waypoint belongs to that marker, and AMap's default answer is an info
        // window with nothing in it. Consuming the tap says it was handled without drawing
        // anything; the other backend opens the waypoint's own label there.
        aMap.setOnMarkerClickListener { true }
        aMap.setOnMarkerDragListener(
            object : AMap.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) = Unit

                override fun onMarkerDrag(marker: Marker) = Unit

                override fun onMarkerDragEnd(marker: Marker) {
                    val index = waypointMarkers.indexOfFirst { it === marker }
                    if (index < 0) return
                    val landed = marker.position ?: return
                    onWaypointDragEnd?.invoke(index, toWgs84(landed))
                }
            },
        )
    }

    override fun showRoute(geometry: List<GeoPoint>, waypoints: List<GeoPoint>) {
        this.geometry = geometry
        this.waypoints = waypoints
        aMap.clear()
        waypointMarkers = ArrayList()
        liveMarker = null

        val positions = geometry.map { toGcj02(it) }
        if (positions.size >= 2) {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(positions)
                    .width(8f)
                    .color(ROUTE_COLOR),
            )
        }
        for (waypoint in waypoints) {
            val marker = aMap.addMarker(
                MarkerOptions()
                    .position(toGcj02(waypoint))
                    .draggable(true)
                    .icon(BitmapDescriptorFactory.defaultMarker()),
            )
            if (marker != null) waypointMarkers.add(marker)
        }
    }

    override fun showLive(point: GeoPoint?) {
        if (point == null) {
            liveMarker?.remove()
            liveMarker = null
            return
        }
        val position = toGcj02(point)
        val existing = liveMarker
        if (existing != null) {
            existing.position = position
            return
        }
        liveMarker = aMap.addMarker(
            MarkerOptions()
                .position(position)
                .anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.defaultMarker(LIVE_HUE))
                .zIndex(LIVE_Z_INDEX),
        )
    }

    override fun centerOn(point: GeoPoint) {
        // newLatLng, not newLatLngZoom: once the map has a camera, centring must not fight
        // the zoom the user chose. The FIRST centre is the initial camera, though, and
        // AMap's default is a country-wide view - so that one carries a street zoom.
        val update = if (cameraSet) {
            CameraUpdateFactory.newLatLng(toGcj02(point))
        } else {
            cameraSet = true
            CameraUpdateFactory.newLatLngZoom(toGcj02(point), DEFAULT_ZOOM)
        }
        aMap.moveCamera(update)
    }

    override fun fitRoute() {
        val positions = (if (geometry.size >= 2) geometry else waypoints).map { toGcj02(it) }
        if (positions.size < 2) return
        val minLat = positions.minOf { it.latitude }
        val maxLat = positions.maxOf { it.latitude }
        val minLon = positions.minOf { it.longitude }
        val maxLon = positions.maxOf { it.longitude }
        // A zero-area box is rejected by the fit update, and a route of two identical
        // points is a state the editor can produce.
        if (maxLat - minLat < MIN_FIT_SPAN_DEG && maxLon - minLon < MIN_FIT_SPAN_DEG) {
            centerOn(GeoPoint(maxLat, maxLon))
            return
        }
        val bounds = LatLngBounds.Builder().apply {
            for (position in positions) include(position)
        }.build()
        try {
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PIXELS))
            cameraSet = true
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
    }

    override fun onResume() {
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
    }

    override fun onDestroy() {
        mapView.onDestroy()
    }

    /**
     * Close enough to a waypoint to mean "drag that one", measured on screen: how close a
     * finger has to be depends on the zoom, so the projection decides, not a metre radius.
     */
    private fun isOnWaypoint(point: GeoPoint): Boolean {
        // Before the first layout every coordinate projects to the same pixel, which would
        // read as "all waypoints are under the finger" and swallow the tap.
        if (mapView.width == 0 || mapView.height == 0) return false
        val projection = aMap.projection ?: return false
        val tap: Point = projection.toScreenLocation(toGcj02(point)) ?: return false
        return waypoints.any { waypoint ->
            val screen: Point = projection.toScreenLocation(toGcj02(waypoint)) ?: return@any false
            hypot((screen.x - tap.x).toDouble(), (screen.y - tap.y).toDouble()) <= WAYPOINT_GRAB_PIXELS
        }
    }

    private fun toGcj02(point: GeoPoint): LatLng {
        val converted = CoordinateTransform.wgs84ToGcj02(point)
        return LatLng(converted.lat, converted.lon)
    }

    private fun toWgs84(position: LatLng): GeoPoint =
        CoordinateTransform.gcj02ToWgs84(GeoPoint(position.latitude, position.longitude))

    private companion object {
        const val DEFAULT_ZOOM = 15f

        /** Breathing room around a fitted route, in pixels. */
        const val FIT_PADDING_PIXELS = 64

        /** Below this span (about a metre) a route box has nothing to zoom to. */
        const val MIN_FIT_SPAN_DEG = 1e-5

        /** Red, in AMap's hue convention for the default pin. */
        const val LIVE_HUE = 0f

        const val LIVE_Z_INDEX = 1f

        /** Finger radius, in screen pixels, that counts as touching a waypoint. */
        const val WAYPOINT_GRAB_PIXELS = 28.0

        val ROUTE_COLOR = 0xFF1E88E5.toInt()
    }
}
