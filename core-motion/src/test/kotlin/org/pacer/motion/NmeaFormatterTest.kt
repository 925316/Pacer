package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class NmeaFormatterTest {

    private val fixedTime: Date = Date(1_700_000_000_000L)

    @Test
    fun `gga has valid checksum and talker id`() {
        val s = NmeaFormatter.gga(39.9042, 116.4074, satellites = 9, altitudeMeters = 10.0, time = fixedTime)
        assertTrue(s.startsWith("\$GPGGA,"))
        assertTrue(NmeaFormatter.checksumValid(s))
    }

    @Test
    fun `rmc has valid checksum`() {
        val s = NmeaFormatter.rmc(39.9042, 116.4074, speedKnots = 5.8, bearingDeg = 45.0, date = fixedTime)
        assertTrue(s.startsWith("\$GPRMC,"))
        assertTrue(NmeaFormatter.checksumValid(s))
    }

    @Test
    fun `corrupted sentence fails checksum`() {
        val s = NmeaFormatter.gga(39.9042, 116.4074, 9, 10.0, fixedTime)
        val broken = s.replaceFirst("GPGGA", "GPGGB")
        assertFalse(NmeaFormatter.checksumValid(broken))
    }

    @Test
    fun `latitude and longitude formatting uses ddmm`() {
        assertEquals("3954.2520", NmeaFormatter.formatLat(39.9042))
        assertEquals("11624.4440", NmeaFormatter.formatLon(116.4074))
    }

    @Test
    fun `hemispheres switch on sign`() {
        assertEquals("S", NmeaFormatter.latHemisphere(-1.0))
        assertEquals("W", NmeaFormatter.lonHemisphere(-1.0))
    }

    @Test
    fun `south west hemisphere sentence is valid`() {
        val s = NmeaFormatter.gga(-33.8688, -151.2093, 7, 5.0, fixedTime)
        assertTrue(NmeaFormatter.checksumValid(s))
        assertTrue(s.contains(",S,"))
        assertTrue(s.contains(",W,"))
    }

    @Test
    fun `time and date formatting are UTC even on a local clock`() {
        val old = TimeZone.getDefault()
        // A zone far from UTC: formatting with the device default would shift both
        // fields, and setting the default to UTC would hide that.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val d = Date(0L)
            assertEquals("000000.000", NmeaFormatter.timeOf(d))
            assertEquals("010170", NmeaFormatter.dateOf(d))
        } finally {
            TimeZone.setDefault(old)
        }
    }
}
