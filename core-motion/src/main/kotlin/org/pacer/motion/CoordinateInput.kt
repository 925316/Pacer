package org.pacer.motion

/**
 * Turns whatever the user pasted into a coordinate: a bare decimal pair, a
 * `geo:` URI, or a share link from a map app.
 *
 * Pure Kotlin so it is fully unit-testable and so the hook, the app and the
 * tests agree on what a coordinate looks like. Only forms whose latitude and
 * longitude order is unambiguous are accepted: a `lat=`/`lon=` pair is decided
 * by the parameter names, and the positional forms are the ones that put
 * latitude first (Google's `@lat,lon`, `?q=lat,lon`, `!3d<lat>!4d<lon>`, and
 * `geo:lat,lon`). Deliberately NOT guessed: parameters such as AMap's
 * `location=`, which is longitude-first and would silently swap the axes.
 */
object CoordinateInput {

    /** "39.9042,116.4074" or "39.9042 116.4074" (also ; separator). */
    private val DECIMAL_PAIR =
        Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /** Google Maps drop-pin: .../@39.9042,116.4074,15z */
    private val AT_PAIR = Regex("""@(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")

    /** Google Maps place data blob: !3d39.9042!4d116.4074 (lat then lon). */
    private val BANG_PAIR = Regex("""!3d(-?\d{1,3}(?:\.\d+)?)!4d(-?\d{1,3}(?:\.\d+)?)""")

    /** Share link query: ?q=39.9042,116.4074 (also query/ll/center/daddr). */
    private val QUERY_PAIR =
        Regex("""[?&](?:q|query|ll|center|daddr|destination)=(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")

    /** geo:39.9042,116.4074?z=15 */
    private val GEO_URI = Regex("""geo:(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")

    /** Named parameters; order-independent because the names disambiguate. */
    private val LAT_THEN_LON =
        Regex("""[?&]lat=(-?\d{1,3}(?:\.\d+)?)[^0-9-]+lon=(-?\d{1,3}(?:\.\d+)?)""")
    private val LON_THEN_LAT =
        Regex("""[?&]lon=(-?\d{1,3}(?:\.\d+)?)[^0-9-]+lat=(-?\d{1,3}(?:\.\d+)?)""")

    /** Parses [text] into a validated point, or null when nothing matches. */
    fun parse(text: String): GeoPoint? {
        val t = percentDecode(text).trim()
        if (t.isEmpty()) return null

        DECIMAL_PAIR.matchEntire(t)?.let { return point(it, latFirst = true) }
        BANG_PAIR.find(t)?.let { return point(it, latFirst = true) }
        AT_PAIR.find(t)?.let { return point(it, latFirst = true) }
        // Query before geo: on purpose, so "geo:0,0?q=39.9,116.4" prefers the
        // real point in q over the placeholder origin.
        QUERY_PAIR.find(t)?.let { return point(it, latFirst = true) }
        GEO_URI.find(t)?.let { return point(it, latFirst = true) }
        LAT_THEN_LON.find(t)?.let { return point(it, latFirst = true) }
        LON_THEN_LAT.find(t)?.let { return point(it, latFirst = false) }
        return null
    }

    /** [parse] that reports whether it succeeded, for form validation. */
    fun isValid(text: String): Boolean = parse(text) != null

    /**
     * Builds a point from a two-group match and validates the ranges, so an
     * out-of-range pair (999,999) is rejected rather than accepted silently.
     */
    private fun point(match: MatchResult, latFirst: Boolean): GeoPoint? {
        val first = match.groupValues[1].toDoubleOrNull() ?: return null
        val second = match.groupValues[2].toDoubleOrNull() ?: return null
        val lat = if (latFirst) first else second
        val lon = if (latFirst) second else first
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }

    /** Minimal decoding for the two escapes that appear in shared map links. */
    private fun percentDecode(text: String): String =
        text.replace("%2C", ",").replace("%2c", ",").replace("%20", " ")
}
