package org.pacer.app

import org.json.JSONObject
import org.pacer.motion.GeoPoint
import org.pacer.motion.PolylineCodec
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns the waypoints the user tapped into a path that follows the roads between them.
 *
 * WHY THIS MATTERS FOR A SIMULATION: the route editor produces a polyline through the
 * points, which cuts across blocks, runs through buildings and ignores one-way streets.
 * A track like that is not a bad drawing, it is evidence - no runner moves in straight
 * segments between arbitrary points. Routing the same waypoints through the actual street
 * graph fixes the geometry without asking the user to tap a hundred times.
 *
 * The service is FOSSGIS's public OSRM instance, which is why there is no key and no
 * account. It is run for OpenStreetMap contributors rather than as a production API, so
 * this makes one request per button press and never retries; the app is a research tool
 * used by one person at a time. If it is unreachable, the editor keeps its straight
 * segments and says so - the routing is an improvement, not a dependency.
 */
object RoadRoute {

    /**
     * The three profiles the public instance serves for a simulated subject.
     *
     * [service] selects which server answers, [mode] is the OSRM profile segment in the
     * URL. The server decides the graph, so the two are kept together rather than left
     * for a caller to pair wrongly.
     */
    enum class Profile(val service: String, val mode: String, val label: String) {
        FOOT("routed-foot", "foot", "Walk"),
        BIKE("routed-bike", "cycling", "Cycle"),
        CAR("routed-car", "driving", "Drive"),
    }

    private const val BASE = "https://routing.openstreetmap.de/"

    private const val USER_AGENT = "Pacer/1.0 (Android research tool; https://github.com/925316/Pacer)"

    /**
     * The road path through [points], or null when the service could not answer.
     *
     * Blocking: never call this on the main thread. A route of two points is still sent
     * to the service, because the straight line between two taps is exactly the part
     * that needs to become a street.
     */
    fun plan(points: List<GeoPoint>, profile: Profile = Profile.FOOT): List<GeoPoint>? {
        if (points.size < 2) return null
        return try {
            val coordinates = points.joinToString(";") { "${it.lon},${it.lat}" }
            val url = URL("$BASE${profile.service}/route/v1/${profile.mode}/$coordinates?overview=full&geometries=polyline")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The encoded geometry of the first route in an OSRM answer.
     *
     * Only the FIRST route is taken: the service may return alternatives, and a run has
     * to be one path. A valid answer with no usable geometry yields null rather than an
     * empty path, so the caller keeps what it had.
     */
    fun parse(body: String): List<GeoPoint>? {
        val geometry = try {
            JSONObject(body)
                .optJSONArray("routes")
                ?.optJSONObject(0)
                ?.optString("geometry")
        } catch (_: Throwable) {
            null
        } ?: return null
        if (geometry.isBlank()) return null
        val points = PolylineCodec.decode(geometry)
        return if (points.size >= 2) points else null
    }
}
