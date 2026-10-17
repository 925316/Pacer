// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Deterministic virtual radio environment. Given a simulated coordinate and a
// per-session seed it produces a stable set of WiFi access points, cell towers
// and BLE beacons. Access points are placed on a fixed local grid and become
// visible when the current fix is inside their radio range, so a place always
// yields the same set and moving only swaps the APs that cross the range edge.
// No database or platform service is consulted.
package org.pacer.hook.hooks.env

object FakeRadioEnvironment {

    /** Grid pitch used to place candidate access points. */
    private const val AP_GRID_METERS = 150.0

    /** How far a candidate access point can be received from the current fix. */
    private const val AP_VISIBLE_METERS = 140.0

    /** Neighbouring grid cells scanned around the current fix. */
    private const val AP_NEIGHBOUR_RADIUS = 2

    private const val MAX_SCAN_RESULTS = 24

    /** Cell towers are placed on a much coarser grid: they serve a whole area. */
    private const val CELL_GRID_METERS = 4000.0

    /** BLE beacons are short range, so they use a denser grid and small radius. */
    private const val BEACON_GRID_METERS = 120.0

    private const val BEACON_VISIBLE_METERS = 110.0

    private const val MAX_BEACONS = 12

    private const val BEACON_SALT = 0x424C45L

    private val CAPABILITIES = listOf(
        "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
        "[WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][ESS]",
        "[WPA2-PSK-CCMP][ESS][WPS]",
        "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]",
        "[RSN-PSK-CCMP][ESS]",
        "[ESS]",
        "[WPA2-SAE-CCMP][RSN-SAE-CCMP][ESS]",
        "[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS]",
    )

    private val SSID_PREFIXES = listOf(
        "TP-LINK_", "HUAWEI-", "Xiaomi_", "Redmi_", "CMCC-", "ChinaNet-",
        "MERCURY_", "H3C_", "FAST_", "Tenda_", "HONOR_", "ZTE_",
    )

    private val PUBLIC_SSIDS = listOf(
        "CMCC", "CMCC-WEB", "ChinaNet", "iTV", "Starbucks", "KFC-Free-WiFi",
        "McDonalds-WiFi", "Public-WiFi", "D-Link_DIRECT", "Guest",
    )

    private val BEACON_NAMES = listOf("Tile", "AirTag", "SmartTag", "iBeacon", "Eddystone", "Beacon")

    private val WIFI_24_CHANNELS = intArrayOf(1, 6, 11, 1, 6, 11, 3, 9, 13)

    private val WIFI_5_CHANNELS = intArrayOf(36, 40, 44, 48, 64, 100, 116, 132, 149, 153, 157, 161)

    private val WIFI_5_WIDTHS = intArrayOf(20, 40, 80, 80)

    private val WIFI_24_LINK_SPEEDS = intArrayOf(39, 65, 72, 144)

    private val WIFI_5_LINK_SPEEDS = intArrayOf(200, 390, 433, 780, 866)

    data class WifiAp(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val baseLevel: Int,
        val frequency: Int,
        val capabilities: String,
        val wifiStandard: Int,
        val centerFreq0: Int,
        val centerFreq1: Int,
        val channelWidth: Int,
        val linkSpeedMbps: Int,
        val networkId: Int,
    )

    data class Cell(
        val type: String,
        val mcc: Int,
        val mnc: Int,
        val areaCode: Int,
        val cellId: Long,
        val pci: Int,
        val level: Int,
        val registered: Boolean,
    )

    data class Beacon(
        val address: String,
        val name: String,
        val rssi: Int,
    )

    data class Environment(
        val aps: List<WifiAp>,
        val cells: List<Cell>,
        val beacons: List<Beacon>,
    )

    private data class Operator(val mcc: Int, val mnc: Int)

    private data class Region(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double,
        val operators: List<Operator>,
    )

    // A deliberately tiny bounding-box table, not a country database: it only
    // keeps the reported MCC/MNC from being absurdly far from the fake fix.
    // Order matters: more specific boxes are listed first.
    private val REGIONS = listOf(
        Region(50.0, 61.0, -8.0, 2.0, listOf(Operator(234, 10), Operator(234, 15), Operator(234, 30))),
        Region(35.0, 72.0, -11.0, 40.0, listOf(Operator(262, 1), Operator(208, 1), Operator(222, 10), Operator(214, 1))),
        Region(18.0, 54.0, 73.0, 135.0, listOf(Operator(460, 0), Operator(460, 1), Operator(460, 11))),
        Region(24.0, 50.0, -125.0, -66.0, listOf(Operator(310, 260), Operator(311, 480), Operator(310, 410))),
        Region(42.0, 84.0, -141.0, -52.0, listOf(Operator(302, 220), Operator(302, 610), Operator(302, 720))),
        Region(30.0, 46.0, 129.0, 146.0, listOf(Operator(440, 10), Operator(440, 20), Operator(441, 40))),
        Region(33.0, 39.0, 124.0, 132.0, listOf(Operator(450, 5), Operator(450, 8), Operator(450, 11))),
        Region(6.0, 36.0, 68.0, 90.0, listOf(Operator(404, 10), Operator(405, 20), Operator(404, 45))),
        Region(-44.0, -10.0, 113.0, 154.0, listOf(Operator(505, 1), Operator(505, 2), Operator(505, 3))),
        Region(-34.0, 6.0, -74.0, -34.0, listOf(Operator(724, 5), Operator(724, 6), Operator(724, 3))),
        Region(41.0, 78.0, 19.0, 180.0, listOf(Operator(250, 1), Operator(250, 20), Operator(250, 99))),
    )

    private val DEFAULT_OPERATORS = listOf(Operator(460, 0), Operator(460, 1), Operator(460, 11))

    fun environment(
        latitude: Double,
        longitude: Double,
        sessionSeed: Long,
        nowMs: Long,
    ): Environment = Environment(
        aps = scanResults(latitude, longitude, sessionSeed, nowMs),
        cells = cells(latitude, longitude, sessionSeed),
        beacons = beacons(latitude, longitude, sessionSeed, nowMs),
    )

    /** Visible access points, strongest first. */
    fun scanResults(
        latitude: Double,
        longitude: Double,
        sessionSeed: Long,
        nowMs: Long,
    ): List<WifiAp> {
        val latSpan = EnvGeo.metersToLatDegrees(AP_GRID_METERS)
        val lonSpan = EnvGeo.metersToLonDegrees(latitude, AP_GRID_METERS)
        val cellY = EnvGeo.gridIndex(latitude, latSpan)
        val cellX = EnvGeo.gridIndex(longitude, lonSpan)

        val out = ArrayList<WifiAp>()
        for (dy in -AP_NEIGHBOUR_RADIUS..AP_NEIGHBOUR_RADIUS) {
            for (dx in -AP_NEIGHBOUR_RADIUS..AP_NEIGHBOUR_RADIUS) {
                val cx = cellX + dx
                val cy = cellY + dy
                val seed = EnvGeo.cellSeed(cx, cy, sessionSeed)
                val rng = EnvGeo.Rng(seed)
                // Sparse deployment: most cells host at most two visible APs.
                val count = when (rng.nextInt(100)) {
                    in 0..24 -> 0
                    in 25..74 -> 1
                    else -> 2
                }
                repeat(count) { index ->
                    val apSeed = seed xor (index * 0x9E3779B97F4A7C15uL.toLong())
                    val apRng = EnvGeo.Rng(apSeed)
                    val centreLat = (cy + 0.5) * latSpan
                    val centreLon = (cx + 0.5) * lonSpan
                    val north = (apRng.nextDouble() - 0.5) * AP_GRID_METERS
                    val east = (apRng.nextDouble() - 0.5) * AP_GRID_METERS
                    val position = EnvGeo.offset(centreLat, centreLon, north, east)
                    val distance = EnvGeo.metersBetween(latitude, longitude, position[0], position[1])
                    if (distance <= AP_VISIBLE_METERS) {
                        out.add(buildAp(apSeed, distance, apRng, nowMs))
                    }
                }
            }
        }
        out.sortWith(compareByDescending<WifiAp> { it.level })
        return if (out.size > MAX_SCAN_RESULTS) ArrayList(out.subList(0, MAX_SCAN_RESULTS)) else out
    }

    /**
     * The access point the device claims to be associated with. Picked by the
     * un-jittered level so the connected SSID does not flap between calls.
     */
    fun connectedAp(
        latitude: Double,
        longitude: Double,
        sessionSeed: Long,
        nowMs: Long,
    ): WifiAp? = scanResults(latitude, longitude, sessionSeed, nowMs)
        .filter { it.ssid.isNotEmpty() }
        .maxWithOrNull(compareBy<WifiAp>({ it.baseLevel }, { it.bssid }))
        ?.copy(networkId = 0)

    /**
     * Stable key for the coarse area that decides the tower set. Callers use it
     * to cache a fabricated payload, because the tower set only changes when the
     * fix crosses a CELL_GRID_METERS square.
     */
    fun cellAreaKey(latitude: Double, longitude: Double): Long =
        areaKey(latitude, longitude, CELL_GRID_METERS)

    /** The same, for the shorter ranged beacon set. */
    fun beaconAreaKey(latitude: Double, longitude: Double): Long =
        areaKey(latitude, longitude, BEACON_GRID_METERS)

    private fun areaKey(latitude: Double, longitude: Double, gridMeters: Double): Long {
        val latSpan = EnvGeo.metersToLatDegrees(gridMeters)
        val lonSpan = EnvGeo.metersToLonDegrees(latitude, gridMeters)
        val cellX = EnvGeo.gridIndex(longitude, lonSpan)
        val cellY = EnvGeo.gridIndex(latitude, latSpan)
        return (cellX shl 32) xor (cellY and 0xFFFFFFFFL)
    }

    /** Serving cell plus neighbours for the coarse area that contains the fix. */
    fun cells(latitude: Double, longitude: Double, sessionSeed: Long): List<Cell> {
        val latSpan = EnvGeo.metersToLatDegrees(CELL_GRID_METERS)
        val lonSpan = EnvGeo.metersToLonDegrees(latitude, CELL_GRID_METERS)
        val cellX = EnvGeo.gridIndex(longitude, lonSpan)
        val cellY = EnvGeo.gridIndex(latitude, latSpan)
        val rng = EnvGeo.Rng(EnvGeo.cellSeed(cellX, cellY, sessionSeed))
        val operators = operatorsFor(latitude, longitude)
        val count = 3 + rng.nextInt(3)

        val out = ArrayList<Cell>(count)
        for (i in 0 until count) {
            val op = operators[i % operators.size]
            val type = when (i) {
                0 -> "LTE"
                1 -> if (rng.nextInt(4) == 0) "NR" else "LTE"
                2 -> if (rng.nextInt(3) == 0) "NR" else "LTE"
                else -> if (rng.nextBoolean()) "GSM" else "WCDMA"
            }
            val areaCode = 1000 + rng.nextInt(60000)
            val cellId = 1L + (rng.nextLong() ushr 8) % 268435455L
            out.add(
                Cell(
                    type = type,
                    mcc = op.mcc,
                    mnc = op.mnc,
                    areaCode = areaCode,
                    cellId = cellId,
                    pci = rng.nextInt(504),
                    level = -70 - rng.nextInt(28),
                    registered = i == 0,
                ),
            )
        }
        return out
    }

    /** Nearby BLE beacons, strongest first. */
    fun beacons(
        latitude: Double,
        longitude: Double,
        sessionSeed: Long,
        nowMs: Long,
    ): List<Beacon> {
        val latSpan = EnvGeo.metersToLatDegrees(BEACON_GRID_METERS)
        val lonSpan = EnvGeo.metersToLonDegrees(latitude, BEACON_GRID_METERS)
        val cellY = EnvGeo.gridIndex(latitude, latSpan)
        val cellX = EnvGeo.gridIndex(longitude, lonSpan)

        val out = ArrayList<Beacon>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val seed = EnvGeo.cellSeed(cellX + dx, cellY + dy, sessionSeed xor BEACON_SALT)
                val rng = EnvGeo.Rng(seed)
                if (rng.nextInt(100) < 70) continue
                val centreLat = ((cellY + dy) + 0.5) * latSpan
                val centreLon = ((cellX + dx) + 0.5) * lonSpan
                val north = (rng.nextDouble() - 0.5) * BEACON_GRID_METERS
                val east = (rng.nextDouble() - 0.5) * BEACON_GRID_METERS
                val position = EnvGeo.offset(centreLat, centreLon, north, east)
                val distance = EnvGeo.metersBetween(latitude, longitude, position[0], position[1])
                if (distance > BEACON_VISIBLE_METERS) continue
                val rssi = (EnvGeo.dbmFromDistance(distance, BEACON_VISIBLE_METERS) + timeJitter(seed, nowMs))
                    .coerceIn(-99, -40)
                out.add(Beacon(EnvGeo.macForSeed(seed), rng.pick(BEACON_NAMES), rssi))
            }
        }
        out.sortWith(compareByDescending<Beacon> { it.rssi })
        return if (out.size > MAX_BEACONS) ArrayList(out.subList(0, MAX_BEACONS)) else out
    }

    private fun buildAp(seed: Long, distance: Double, rng: EnvGeo.Rng, nowMs: Long): WifiAp {
        val band24 = rng.nextInt(100) < 55
        val channel = if (band24) {
            WIFI_24_CHANNELS[rng.nextInt(WIFI_24_CHANNELS.size)]
        } else {
            WIFI_5_CHANNELS[rng.nextInt(WIFI_5_CHANNELS.size)]
        }
        val frequency = if (band24) 2412 + 5 * (channel - 1) else 5000 + 5 * channel
        val channelWidth = if (band24) {
            if (rng.nextInt(4) == 0) 40 else 20
        } else {
            WIFI_5_WIDTHS[rng.nextInt(WIFI_5_WIDTHS.size)]
        }
        val wifiStandard = if (band24) {
            if (rng.nextInt(3) == 0) 6 else 4
        } else {
            if (rng.nextInt(2) == 0) 6 else 5
        }
        val baseLevel = EnvGeo.dbmFromDistance(distance, AP_VISIBLE_METERS) - (if (band24) 0 else 5)
        val level = (baseLevel + timeJitter(seed, nowMs)).coerceIn(-95, -28)
        val linkSpeed = if (band24) {
            WIFI_24_LINK_SPEEDS[rng.nextInt(WIFI_24_LINK_SPEEDS.size)]
        } else {
            WIFI_5_LINK_SPEEDS[rng.nextInt(WIFI_5_LINK_SPEEDS.size)]
        }
        return WifiAp(
            ssid = ssidFor(rng),
            bssid = EnvGeo.macForSeed(seed),
            level = level,
            baseLevel = baseLevel,
            frequency = frequency,
            capabilities = rng.pick(CAPABILITIES),
            wifiStandard = wifiStandard,
            centerFreq0 = if (channelWidth == 80) frequency + 30 else frequency,
            centerFreq1 = 0,
            channelWidth = channelWidth,
            linkSpeedMbps = linkSpeed,
            networkId = -1,
        )
    }

    private fun ssidFor(rng: EnvGeo.Rng): String {
        val roll = rng.nextInt(100)
        return when {
            roll < 8 -> "" // hidden SSID
            roll < 30 -> rng.pick(PUBLIC_SSIDS)
            else -> rng.pick(SSID_PREFIXES) + EnvGeo.hex4(rng.nextInt(0x10000))
        }
    }

    /**
     * Small +/-2 dBm wiggle that changes every 1.5 s so consecutive scans are
     * not byte identical, while the base level stays tied to the coordinate.
     */
    private fun timeJitter(seed: Long, nowMs: Long): Int {
        val bucket = nowMs / 1500L
        val mixed = EnvGeo.mix64(seed xor (bucket * 0xC2B2AE3D27D4EB4FuL.toLong()))
        return ((mixed ushr 33).toInt() % 5) - 2
    }

    private fun operatorsFor(latitude: Double, longitude: Double): List<Operator> {
        for (region in REGIONS) {
            if (latitude in region.minLat..region.maxLat && longitude in region.minLon..region.maxLon) {
                return region.operators
            }
        }
        return DEFAULT_OPERATORS
    }
}
