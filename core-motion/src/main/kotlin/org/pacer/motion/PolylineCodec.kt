package org.pacer.motion

/**
 * The encoded polyline format used by routing services and by every map SDK that
 * talks to one.
 *
 * WHY IT IS IN THE ENGINE: a planned route arrives as one compressed string, and a
 * wrong decode produces a route that is silently somewhere else - the failure looks
 * like "the map is offset", not like a parsing bug, and no compiler or network log
 * shows it. The arithmetic is small and pure, so it belongs where the tests run.
 *
 * The format, for a reader who has not met it: every value is a delta from the
 * previous one, zig-zag encoded into an unsigned integer, split into 5-bit chunks and
 * offset by 63 so the result is printable ASCII. Coordinates are produced at five
 * decimal places, i.e. about a metre, which is why a planned route is not the place to
 * expect sub-metre geometry.
 */
object PolylineCodec {

    /**
     * Decodes an encoded polyline into points. A malformed string yields an empty
     * list rather than an exception: a route the user asked for either arrives whole
     * or not at all, and half a polyline is a line drawn across the countryside.
     */
    fun decode(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val first = readDelta(encoded, index) ?: return emptyList()
            index = first.next
            val second = readDelta(encoded, index) ?: return emptyList()
            index = second.next
            lat += first.value
            lon += second.value
            // Accumulating deltas can leave the valid range even when every individual
            // delta does; such a string does not describe a place on Earth.
            if (lat !in LAT_RANGE || lon !in LON_RANGE) return emptyList()
            out.add(GeoPoint(lat / 1e5, lon / 1e5))
        }
        return out
    }

    /** Encodes [points] in the same format; used by tests and by any route we hand on. */
    fun encode(points: List<GeoPoint>): String {
        val sb = StringBuilder(points.size * 6)
        var lastLat = 0L
        var lastLon = 0L
        for (point in points) {
            val lat = Math.round(point.lat * 1e5)
            val lon = Math.round(point.lon * 1e5)
            appendDelta(sb, lat - lastLat)
            appendDelta(sb, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    private class Delta(val value: Int, val next: Int)

    /**
     * Reads one zig-zag encoded delta starting at [start].
     *
     * A value longer than 32 bits, or a string that ends mid-value, is treated as
     * malformed. The bound also stops a crafted input from looping over an enormous
     * shift, which would otherwise be a way to freeze the app with one string.
     */
    private fun readDelta(s: String, start: Int): Delta? {
        var index = start
        var shift = 0
        var result = 0L
        while (true) {
            if (index >= s.length || shift > 30) return null
            val chunk = s[index].code - 63
            if (chunk < 0 || chunk > 63) return null
            index++
            result = result or ((chunk and 0x1F).toLong() shl shift)
            shift += 5
            if (chunk < 0x20) break
        }
        // The format carries a signed 32-bit delta, so anything wider is not a delta:
        // accepting it would wrap the coordinate to a different place on the map.
        if (result > 0xFFFFFFFFL) return null
        val narrow = result.toInt()
        // Zig-zag: the low bit is the sign, the rest is the magnitude.
        val value = if ((narrow and 1) != 0) (narrow shr 1).inv() else narrow shr 1
        return Delta(value, index)
    }

    private fun appendDelta(sb: StringBuilder, delta: Long) {
        var value = delta shl 1
        if (delta < 0) value = value.inv()
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1F).toInt()) + 63).toChar())
            value = value ushr 5
        }
        sb.append((value + 63).toChar())
    }

    /** Latitude and longitude in units of 1e-5 degrees, the precision the format carries. */
    private val LAT_RANGE = -9_000_000L..9_000_000L
    private val LON_RANGE = -18_000_000L..18_000_000L
}
