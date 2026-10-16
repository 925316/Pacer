package org.pacer.app

import org.json.JSONArray
import org.pacer.motion.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One geocoding hit: a human readable label plus its WGS84 coordinate. */
data class Place(val displayName: String, val point: GeoPoint)

/**
 * Free text place lookup via the OpenStreetMap Nominatim endpoint, which needs
 * no API key and fits the project's no-backend rule. Nominatim's usage policy
 * asks for an identifying User-Agent and light traffic; both are respected here
 * (one request per button press, no polling).
 */
object PlaceSearch {

    private const val ENDPOINT = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "Pacer/1.0 (Android research tool; https://github.com/925316/Pacer)"

    /**
     * Blocking network call: never invoke on the main thread. Returns an empty
     * list on any network or parse failure so the caller needs no error path.
     */
    fun search(query: String, limit: Int = 5): List<Place> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$ENDPOINT?format=json&limit=$limit&q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) return emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Parses the Nominatim JSON array; malformed input yields an empty list. */
    fun parse(body: String): List<Place> {
        val array = try {
            JSONArray(body)
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = ArrayList<Place>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val lat = obj.optString("lat").toDoubleOrNull() ?: continue
            val lon = obj.optString("lon").toDoubleOrNull() ?: continue
            val name = obj.optString("display_name").ifBlank { "$lat, $lon" }
            out.add(Place(name, GeoPoint(lat, lon)))
        }
        return out
    }
}
