package org.pacer.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import org.pacer.motion.GeoPoint

/**
 * The map the operation page draws a route on.
 *
 * Two backends exist: OpenStreetMap tiles through osmdroid (no key, no proprietary
 * dependency, always available) and the AMap SDK when this build carries a key. The page -
 * the draft, the controls, the live poll - is written once against this interface, so the
 * two cannot drift apart in what they mean by "the route".
 *
 * COORDINATES ARE WGS-84 HERE. The engine, the module and every stored route are WGS-84,
 * and a backend whose tiles use another datum converts at its own boundary: that offset
 * belongs to the tiles, not to the app's model of a route. A tap hands back WGS-84 for the
 * same reason.
 */
interface RouteMap {

    /** Which backend this is, for the page to say out loud. */
    val name: String

    /** The view to add to the layout. */
    val view: View

    /** Draws [points] as the route, replacing whatever was drawn before. */
    fun showRoute(points: List<GeoPoint>)

    /** Moves the marker for the simulated subject, or removes it when [point] is null. */
    fun showLive(point: GeoPoint?)

    /** A tap that was not on a waypoint. */
    var onTap: ((GeoPoint) -> Unit)?

    /** A waypoint dragged somewhere new: its index, and where it landed in WGS-84. */
    var onWaypointDragEnd: ((Int, GeoPoint) -> Unit)?

    /** A long press that was not on a waypoint. */
    var onLongPress: ((GeoPoint) -> Unit)?

    /** Centres the map on [point] without changing the zoom. */
    fun centerOn(point: GeoPoint)

    /** Activity lifecycle: backends that own native views need it, the others ignore it. */
    fun onCreate(savedInstanceState: Bundle?)

    fun onResume()

    fun onPause()
}

/**
 * Chooses the backend for this build.
 *
 * AMap first when the APK carries a key: it is the map the target audience recognises and
 * the one the route has to be drawn on for the result to look right. Without a key the app
 * uses OpenStreetMap tiles instead, so a build straight out of CI still draws a route.
 *
 * A backend that fails while being created (no SDK classes, a key rejected for this
 * package and signature, no tile store) falls through to the other one, and null means
 * both failed - the page then says so and stays usable for editing.
 */
object RouteMaps {

    /** The manifest entry AMap reads its key from. */
    private const val AMAP_KEY_META = "com.amap.api.v2.apikey"

    private const val TAG = "Pacer"

    /** The AMap key this build carries, or an empty string when it carries none. */
    fun amapKey(context: Context): String = try {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        info.metaData?.getString(AMAP_KEY_META) ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun create(context: Context): RouteMap? {
        if (amapKey(context).isNotBlank()) {
            try {
                return AmapRouteMap(context)
            } catch (t: Throwable) {
                Log.w(TAG, "AMap backend unavailable, falling back to osmdroid", t)
            }
        }
        return try {
            OsmRouteMap(context)
        } catch (t: Throwable) {
            Log.w(TAG, "osmdroid backend unavailable", t)
            null
        }
    }
}
