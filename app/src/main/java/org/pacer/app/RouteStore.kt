package org.pacer.app

import android.content.Context
import org.pacer.motion.GeoPoint
import org.pacer.motion.RouteDraft
import org.pacer.motion.RouteJson

/**
 * Keeps the in-progress route across launches, so a half-built route is not lost when
 * the screen is closed, together with the road path that was planned through it.
 *
 * TWO THINGS ARE STORED ON PURPOSE. The draft is what the user edits: the handful of
 * waypoints they tapped. The road path is the geometry a simulation actually follows,
 * which is the same waypoints connected by streets - hundreds of points they never want
 * to see, edit or drag. Keeping them apart means editing stays a matter of moving a few
 * handles, while the simulated track still follows the roads.
 *
 * The path is stored WITH THE DRAFT IT WAS COMPUTED FROM. A path that no longer matches
 * the waypoints is a route through streets the user has since moved, and silently running
 * it would be worse than running the plain polyline they can see.
 */
class RouteStore(context: Context) {

    private val prefs = context.getSharedPreferences("pacer_routes", Context.MODE_PRIVATE)

    fun load(): RouteDraft =
        RouteDraft.parse(prefs.getString(KEY_DRAFT, "") ?: "")

    fun save(draft: RouteDraft) {
        val json = draft.toJson()
        val edit = prefs.edit().putString(KEY_DRAFT, json)
        // A road path planned through the OLD waypoints would silently stay on disk
        // for ever, describing streets the user has since moved. The key already makes
        // it unusable; dropping it keeps the file from carrying a megabyte of obsolete
        // geometry.
        if (prefs.getString(KEY_ROAD_FOR, "") != json) {
            edit.remove(KEY_ROAD).remove(KEY_ROAD_FOR)
        }
        edit.apply()
    }

    /** The road path planned through the current draft, or null when there is none. */
    fun roadPath(draft: RouteDraft): List<GeoPoint>? {
        if (prefs.getString(KEY_ROAD_FOR, "") != draft.toJson()) return null
        val text = prefs.getString(KEY_ROAD, "") ?: return null
        val points = RouteJson.decodeOrNull(text) ?: return null
        return if (points.size >= 2) points else null
    }

    /** Records the path planned through [draft], replacing any earlier one. */
    fun saveRoadPath(draft: RouteDraft, path: List<GeoPoint>) {
        prefs.edit()
            .putString(KEY_ROAD, RouteJson.encode(path))
            .putString(KEY_ROAD_FOR, draft.toJson())
            .apply()
    }

    /**
     * The geometry to simulate: the road path when it still matches the draft, the draft's
     * own polyline otherwise. One answer for both screens, so the map and the run cannot
     * describe different routes.
     */
    fun geometry(draft: RouteDraft): List<GeoPoint> = geometryOf(draft, roadPath(draft))

    companion object {
        /**
         * The same rule as [geometry], for a caller that has just decoded the road path and
         * should not pay to decode it again: the map redraws on every tap, and a planned
         * path is a long JSON array.
         *
         * Copied, not handed out as the draft's own list: the map keeps the geometry it is
         * given, and a live view over the editable waypoints would change under it.
         */
        fun geometryOf(draft: RouteDraft, road: List<GeoPoint>?): List<GeoPoint> =
            road ?: draft.points.toList()

        private const val KEY_DRAFT = "draft"
        private const val KEY_ROAD = "road"
        private const val KEY_ROAD_FOR = "road_for"
    }
}
