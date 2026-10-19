package org.pacer.app

import android.content.Context
import org.pacer.motion.GeoPoint
import org.pacer.motion.RouteDraft

/**
 * Keeps the in-progress route across launches, and which basemap is being drawn.
 *
 * The draft is the handful of waypoints the user tapped on the map; it is the only thing
 * that describes a route, and [RouteDraft.toJson] is the wire format the module also reads.
 * There is no second layer any more: road routing is gone with the routing service, so the
 * waypoints ARE the geometry, and what the map draws is exactly what will be simulated.
 */
class RouteStore(context: Context) {

    private val prefs = context.getSharedPreferences("pacer_routes", Context.MODE_PRIVATE)

    fun load(): RouteDraft =
        RouteDraft.parse(prefs.getString(KEY_DRAFT, "") ?: "")

    fun save(draft: RouteDraft) {
        prefs.edit().putString(KEY_DRAFT, draft.toJson()).apply()
    }

    /** The basemap last chosen, or null when the user has never chosen one. */
    fun mapStyle(): MapStyle? =
        MapStyle.byName(prefs.getString(KEY_MAP, "") ?: "")

    fun saveMapStyle(style: MapStyle) {
        prefs.edit().putString(KEY_MAP, style.name).apply()
    }

    /** The layer last chosen, or null when the user has never chosen one. */
    fun mapLayer(): MapLayer? =
        prefs.getString(KEY_LAYER, "")?.let { name ->
            MapLayer.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

    fun saveMapLayer(layer: MapLayer) {
        prefs.edit().putString(KEY_LAYER, layer.name).apply()
    }

    /**
     * The geometry a run follows: the draft's own points.
     *
     * A method rather than handing the list out from the page, so both screens keep asking
     * one object what a route is. Copied, because the map keeps what it is given and a live
     * view over the editable waypoints would change underneath it.
     */
    fun geometry(draft: RouteDraft): List<GeoPoint> = draft.points.toList()

    private companion object {
        const val KEY_DRAFT = "draft"
        const val KEY_MAP = "map_style"
        const val KEY_LAYER = "map_layer"
    }
}
