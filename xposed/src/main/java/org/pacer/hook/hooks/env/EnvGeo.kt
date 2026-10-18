// Pure geometry and deterministic pseudo-random helpers for the simulated
// radio environment. Everything here is side-effect free so that a virtual
// WiFi / cell / BLE set can be reproduced from (coordinate + session seed)
// alone, without any external data source.
package org.pacer.hook.hooks.env

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

object EnvGeo {

    /** Mean meters per degree of latitude used by the local approximation. */
    const val METERS_PER_DEGREE_LAT = 111320.0

    /** Radius used by the equirectangular distance approximation. */
    private const val EARTH_RADIUS_METERS = 6371000.0

    private const val HEX = "0123456789abcdef"

    /**
     * SplitMix64. Chosen because it is small, self-contained and well mixed.
     * Unlike java.util.Random it has no 48-bit state reuse, and [nextInt] uses
     * rejection sampling instead of a biased modulo.
     */
    class Rng(seed: Long) {

        private var state: Long = seed

        fun nextLong(): Long {
            state += 0x9E3779B97F4A7C15uL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }

        /** Uniform value in [0.0, 1.0). */
        fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / 9007199254740992.0

        /** Uniform value in [0, bound); throws when bound is not positive. */
        fun nextInt(bound: Int): Int {
            if (bound <= 0) throw IllegalArgumentException("bound must be positive")
            val b = bound.toLong()
            while (true) {
                val r = nextLong() ushr 1
                val m = r % b
                // Reject the short tail so the result stays exactly uniform.
                if (r - m + (b - 1) >= 0L) return m.toInt()
            }
        }

        fun nextInt(from: Int, until: Int): Int =
            if (until <= from) from else from + nextInt(until - from)

        fun nextBoolean(): Boolean = (nextLong() and 1L) == 0L

        fun <T> pick(items: List<T>): T = items[nextInt(items.size)]
    }

    /**
     * Session seed derived from the simulation start time. It is constant for
     * the whole run, so a coordinate visited twice in one session maps to the
     * same virtual environment.
     */
    fun sessionSeed(startTimestampMs: Long): Long =
        if (startTimestampMs > 0L) startTimestampMs else 0x50414345525F5345L

    /** True when the runtime exposes a plausible simulated fix. */
    fun hasFix(latitude: Double, longitude: Double): Boolean =
        latitude != 0.0 && longitude != 0.0

    /** Equirectangular ground distance in meters; accurate enough at city scale. */
    fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
        val x = Math.toRadians(lon2 - lon1) * cos(meanLat)
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * EARTH_RADIUS_METERS
    }

    /** Offsets a WGS84 point by a local north/east vector and returns [lat, lon]. */
    fun offset(
        latitude: Double,
        longitude: Double,
        northMeters: Double,
        eastMeters: Double,
    ): DoubleArray {
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(1e-6)
        val dLat = northMeters / METERS_PER_DEGREE_LAT
        val dLon = eastMeters / (METERS_PER_DEGREE_LAT * cosLat)
        return doubleArrayOf(latitude + dLat, longitude + dLon)
    }

    fun metersToLatDegrees(meters: Double): Double = meters / METERS_PER_DEGREE_LAT

    fun metersToLonDegrees(latitude: Double, meters: Double): Double =
        meters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)).coerceAtLeast(1e-6))

    /** Index of the grid cell that contains [valueDegrees]. */
    fun gridIndex(valueDegrees: Double, spanDegrees: Double): Long =
        floor(valueDegrees / spanDegrees).toLong()

    /** Mixes a grid cell plus a salt into a stable 64-bit seed. */
    fun cellSeed(cellX: Long, cellY: Long, salt: Long): Long =
        mix64(
            (cellX * 0x9E3779B97F4A7C15uL.toLong()) xor
                (cellY * 0xC2B2AE3D27D4EB4FuL.toLong()) xor
                (salt * 0x165667B19E3779F9uL.toLong()),
        )

    fun mix64(value: Long): Long {
        var z = value
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** Pseudo signal level in dBm, falling from -35 at the source to -90 at range. */
    fun dbmFromDistance(meters: Double, maxMeters: Double): Int {
        if (maxMeters <= 0.0) return -90
        val t = (meters / maxMeters).coerceIn(0.0, 1.0)
        return (-35.0 - 55.0 * t).toInt()
    }

    /** WifiInfo.getSSID() and WifiConfiguration.SSID are stored quoted. */
    fun quoteSsid(ssid: String): String = "\"" + ssid + "\""

    fun hex4(value: Int): String {
        val v = value and 0xFFFF
        return buildString(4) {
            append(HEX[(v ushr 12) and 0xF])
            append(HEX[(v ushr 8) and 0xF])
            append(HEX[(v ushr 4) and 0xF])
            append(HEX[v and 0xF])
        }
    }

    /** Formats six bytes as a lower-case, colon separated MAC address. */
    fun mac(bytes: ByteArray): String {
        val sb = StringBuilder(17)
        for (i in bytes.indices) {
            if (i > 0) sb.append(':')
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Deterministic locally administered unicast MAC address for [seed].
     * The multicast bit is cleared and the local bit is set so the address
     * looks like a real interface instead of an arbitrary random value.
     */
    fun macForSeed(seed: Long): String {
        val rng = Rng(mix64(seed))
        val bytes = ByteArray(6)
        for (i in bytes.indices) bytes[i] = rng.nextInt(256).toByte()
        bytes[0] = (((bytes[0].toInt() and 0xFE) or 0x02) and 0xFF).toByte()
        return mac(bytes)
    }

    /**
     * Deterministic address for an access point: a vendor prefix plus three varying
     * bytes.
     *
     * Real access points announce a globally administered address from the OUI block
     * their maker owns. A scan list in which every BSSID has the locally administered
     * bit set is not a neighbourhood, it is a fabricator - one bit test, no radio
     * needed. So this differs from [macForSeed] (personal-area devices, where random
     * addresses are normal) in exactly that bit. The addresses detection code
     * blacklists (all zeroes, all ones, 02:00:00:00:00:00) cannot be produced by
     * either generator.
     */
    fun apMacForSeed(seed: Long): String {
        val rng = Rng(mix64(seed))
        val bytes = ByteArray(6)
        AP_OUIS[rng.nextInt(AP_OUIS.size)].copyInto(bytes, 0)
        for (i in 3..5) bytes[i] = rng.nextInt(256).toByte()
        return mac(bytes)
    }

    /** Vendor prefixes common in consumer routers; any real OUI block would do. */
    private val AP_OUIS: Array<ByteArray> = arrayOf(
        byteArrayOf(0x00, 0x1A, 0x11),
        byteArrayOf(0x00, 0x25, 0x9C.toByte()),
        byteArrayOf(0xB0.toByte(), 0xBE.toByte(), 0x76),
        byteArrayOf(0xF4.toByte(), 0xF5.toByte(), 0xD8.toByte()),
        byteArrayOf(0x00, 0x50, 0xF2.toByte()),
    )
}
