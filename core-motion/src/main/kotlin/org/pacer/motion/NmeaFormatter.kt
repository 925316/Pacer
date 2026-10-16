// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NMEA-0183 sentence construction (GGA / RMC) for the simulated fix stream.
 * Pure and unit-testable; the Android hook just supplies coordinates and
 * forwards the strings to registered listeners.
 */
object NmeaFormatter {

    /** Builds a $GPGGA sentence. */
    fun gga(lat: Double, lon: Double, satellites: Int, altitudeMeters: Double, time: Date = Date()): String {
        val body = "GPGGA,${timeOf(time)},${formatLat(lat)},${latHemisphere(lat)}," +
            "${formatLon(lon)},${lonHemisphere(lon)},1,$satellites,0.9," +
            "${"%.1f".format(Locale.US, altitudeMeters)},M,0.0,M,,"
        return wrap(body)
    }

    /** Builds a $GPRMC sentence. */
    fun rmc(lat: Double, lon: Double, speedKnots: Double, bearingDeg: Double, date: Date = Date()): String {
        val body = "GPRMC,${timeOf(date)},A,${formatLat(lat)},${latHemisphere(lat)}," +
            "${formatLon(lon)},${lonHemisphere(lon)}," +
            "${"%.1f".format(Locale.US, speedKnots)},${"%.1f".format(Locale.US, bearingDeg)}," +
            "${dateOf(date)},,,A"
        return wrap(body)
    }

    /** Appends the NMEA XOR checksum. */
    fun wrap(body: String): String {
        var checksum = 0
        for (c in body) checksum = checksum xor c.code
        return "\$$body*${"%02X".format(checksum)}"
    }

    /** Validates a sentence's checksum; used by tests and debug tools. */
    fun checksumValid(sentence: String): Boolean {
        val star = sentence.lastIndexOf('*')
        if (star < 0 || !sentence.startsWith("$")) return false
        val body = sentence.substring(1, star)
        val expected = wrap(body).substringAfter('*')
        return expected.equals(sentence.substring(star + 1).trim(), ignoreCase = true)
    }

    /** ddmm.mmmm */
    fun formatLat(lat: Double): String {
        val abs = kotlin.math.abs(lat)
        val deg = abs.toInt()
        return "%02d%07.4f".format(Locale.US, deg, (abs - deg) * 60.0)
    }

    /** dddmm.mmmm */
    fun formatLon(lon: Double): String {
        val abs = kotlin.math.abs(lon)
        val deg = abs.toInt()
        return "%03d%07.4f".format(Locale.US, deg, (abs - deg) * 60.0)
    }

    fun latHemisphere(lat: Double) = if (lat >= 0) "N" else "S"
    fun lonHemisphere(lon: Double) = if (lon >= 0) "E" else "W"

    fun timeOf(date: Date): String = SimpleDateFormat("HHmmss.SSS", Locale.US).format(date)
    fun dateOf(date: Date): String = SimpleDateFormat("ddMMyy", Locale.US).format(date)
}
