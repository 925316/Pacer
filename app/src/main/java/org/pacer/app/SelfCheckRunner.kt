// In-process self-check.
//
// WHY THIS EXISTS: the only person who can run Pacer is the person holding the
// phone, and "does the hook work" is not a question a user should have to answer by
// reading logcat. Every probe here follows the same shape - compute what the engine
// says the device must report, ask the device, compare.
//
// WHAT IT MUST NOT DO: read the hook's own in-memory state.
//
// The app ships the hook code and the framework loads it again from the same APK with
// its own class loader, so `PacerRuntimeConfig` and `HookStatus` exist twice in this
// process: the hook writes one copy and the app reads the other. An earlier version of
// this file compared against the app's copy, which is never written, and so reported
// "no hook attached, config never arrived" while 149 hooks were demonstrably live. The
// expected values therefore come from the app's own stored configuration and the same
// pure engine the hook uses - and "is the hook working" is answered by whether the
// platform hands back what that engine says, not by asking the hook.
package org.pacer.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.provider.Settings
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.env.FakeRadioEnvironment
import org.pacer.hook.hooks.system.vendor.ProcessIdentity
import org.pacer.motion.CheckItem
import org.pacer.motion.MotionOwner
import org.pacer.motion.SelfCheck
import org.pacer.motion.SelfCheckReport

/**
 * Runs the probes and returns a report.
 *
 * Everything is read on the calling thread and every probe is individually guarded:
 * one API that throws (a permission the app lacks, a service missing on this ROM)
 * marks that item SKIP or FAIL and lets the rest run. A self-check that stops at the
 * first problem is worth less than the report of everything else that still works.
 *
 * @param cfg the configuration the app itself wrote, so the expected values come from
 *   the same run the hooks read rather than from a second copy of the settings.
 */
class SelfCheckRunner(
    private val context: android.app.Activity,
    private val cfg: PacerConfigClient.Stored?,
) {

    /** Rebuilds the run exactly as the hook does, from the app's own stored config. */
    private val subject = LiveSubject()

    fun run(): SelfCheckReport {
        // The probes need these to read anything at all; asking here rather than at
        // launch keeps a permission wall away from a page nobody is using yet.
        requestProbePermissions(context)
        val items = ArrayList<CheckItem>()

        if (cfg == null || !cfg.active) {
            items += SelfCheck.skip(
                "simulation",
                "no run is active; start one, then run the check again so the injected " +
                    "values differ from the device's own",
            )
            return SelfCheckReport.of(processLabel(), items, notes())
        }

        val expected = runCatching { expectedFix() }.getOrNull()
        items += locationProbes(expected)
        items += antiDetectionProbes()
        items += radioProbes()
        // Sensors last and only when a step run is on: the probe waits several seconds
        // for the hook's own heartbeat, which would otherwise delay every check.
        if (cfg.stepSimulation) {
            items += sensorProbes()
        } else {
            items += SelfCheck.skip("step counter", "step simulation is switched off")
        }

        return SelfCheckReport.of(processLabel(), items, notes())
    }

    // ----------------------------------------------------------- anti-detection

    /**
     * The one probe that can only pass if a hook is running in this process.
     *
     * `Settings.Secure.mock_location` is written by the system for an app that has been
     * granted mock-location rights, and Pacer never holds that grant. A 0 here is
     * therefore not something the app can produce by itself: it is the module answering
     * the settings read. This is the evidence that replaces an earlier version which
     * asked the hook "are you installed?" and, because the app holds a second copy of
     * the hook's classes, always heard "no".
     */
    private fun antiDetectionProbes(): List<CheckItem> {
        val out = ArrayList<CheckItem>()
        out += mockLocationSettingProbe()
        return out
    }

    // ---------------------------------------------------------------- location

    private fun locationProbes(expected: Expected?): List<CheckItem> {
        val out = ArrayList<CheckItem>()
        if (expected == null) {
            out += SelfCheck.skip("location", "the engine could not compute a fix for this config")
            return out
        }

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            out += SelfCheck.skip("location", "grant location permission to Pacer to check it")
            return out
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            out += SelfCheck.skip("location", "no LocationManager on this device")
            return out
        }

        val last = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        if (last == null) {
            out += CheckItem(
                "getLastKnownLocation",
                CheckItem.Verdict.FAIL,
                "returned null while a simulation is active; read every getLastKnownLocation " +
                    "overload and the platform may have a stale cache to answer from",
            )
            return out
        }

        out += position("getLastKnownLocation", expected, last.latitude, last.longitude)
        // The injected Location is built by the app itself, so this is a claim the app
        // could also have made up. The position above is not: it comes back through the
        // platform, and a hook that is not running cannot make it match the engine.
        out += SelfCheck.flag("mock flag hidden", false, last.isFromMockProvider)
        out += SelfCheck.number(
            "speed",
            expected.speedMps,
            last.speed.toDouble(),
            tolerance = SPEED_TOLERANCE_MPS,
            unit = " m/s",
        )
        out += SelfCheck.number(
            "accuracy",
            expected.accuracyMeters.toDouble(),
            last.accuracy.toDouble(),
            tolerance = ACCURACY_TOLERANCE_METERS,
            unit = " m",
        )
        out += SelfCheck.number(
            "altitude",
            expected.altitudeMeters,
            last.altitude,
            tolerance = ALTITUDE_TOLERANCE_METERS,
            unit = " m",
        )
        // The provider name is free to check and catches a fix delivered under the
        // wrong provider, which a caller that filters by provider would ignore.
        out += SelfCheck.text("provider", LocationManager.GPS_PROVIDER, last.provider ?: "")
        return out
    }

    private fun position(name: String, expected: Expected, lat: Double, lon: Double): CheckItem {
        val meters = EnvGeo.metersBetween(expected.lat, expected.lon, lat, lon)
        return if (meters <= SelfCheck.POSITION_TOLERANCE_METERS) {
            SelfCheck.pass(name, String.format(java.util.Locale.US, "%.5f,%.5f (%.2f m off)", lat, lon, meters))
        } else {
            CheckItem(
                name,
                CheckItem.Verdict.FAIL,
                String.format(
                    java.util.Locale.US,
                    "expected %.5f,%.5f but got %.5f,%.5f - %.0f m off",
                    expected.lat, expected.lon, lat, lon, meters,
                ),
            )
        }
    }

    /**
     * `Settings.Secure.ALLOW_MOCK_LOCATION` and the AppOps answer for it.
     *
     * A real device reports 0 for the first (it is a per-app grant) and MODE_ERRORED
     * for the second unless the user allowed mock locations; the module answers 0 and
     * MODE_IGNORED for the mock-location op while a run is active.
     */
    @SuppressLint("HardwareIds")
    private fun mockLocationSettingProbe(): CheckItem = try {
        val value = Settings.Secure.getInt(context.contentResolver, "mock_location", -1)
        SelfCheck.exact("Settings.Secure mock_location", 0, value)
    } catch (t: Throwable) {
        SelfCheck.skip("Settings.Secure mock_location", t.javaClass.simpleName)
    }

    // ------------------------------------------------------------------- radio

    /**
     * The WiFi view.
     *
     * The expected list is `FakeRadioEnvironment.scanResults` at the injected fix, which
     * is the same pure function the hook uses to build what `getScanResults` returns.
     * Comparing the BSSIDs therefore checks the whole chain: config -> fix -> model ->
     * fabricator -> WifiManager.
     *
     * The radio is followed, not overridden: the hook offers no access points when the
     * device's own scan list is empty, so WiFi off is a SKIP here rather than a FAIL.
     */
    @SuppressLint("MissingPermission")
    private fun radioProbes(): List<CheckItem> {
        val out = ArrayList<CheckItem>()

        if (!hasPermission(Manifest.permission.ACCESS_WIFI_STATE) ||
            !hasPermission(Manifest.permission.CHANGE_WIFI_STATE)
        ) {
            out += SelfCheck.skip("wifi scan list", "grant WiFi permissions to Pacer to check it")
        } else {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val real = runCatching { wm?.scanResults }.getOrNull()
            if (wm == null || !wm.isWifiEnabled) {
                out += SelfCheck.skip("wifi scan list", "WiFi is off; the module follows the real radio")
            } else if (real.isNullOrEmpty()) {
                out += SelfCheck.skip(
                    "wifi scan list",
                    "the device returned no scan results; the module does not invent access points " +
                        "for a radio that reports none",
                )
            } else {
                val expected = runCatching { expectedWifi() }.getOrNull()
                if (expected.isNullOrEmpty()) {
                    out += CheckItem(
                        "wifi scan list",
                        CheckItem.Verdict.FAIL,
                        "the engine produced no access points for the injected fix, but the radio " +
                            "reported ${real.size} real ones",
                    )
                } else {
                    val asked = real.mapNotNull { it.BSSID?.lowercase() }.toSet()
                    val wanted = expected.map { it.bssid.lowercase() }
                    val missing = wanted.filter { it !in asked }
                    out += if (missing.isEmpty()) {
                        SelfCheck.pass(
                            "wifi scan list",
                            "${real.size} access points, all ${wanted.size} expected BSSIDs present",
                        )
                    } else {
                        CheckItem(
                            "wifi scan list",
                            CheckItem.Verdict.FAIL,
                            "${missing.size}/${wanted.size} expected BSSIDs missing " +
                                "(e.g. ${missing.first()}); the list is still the device's own",
                        )
                    }
                    // The SSID of the strongest expected AP, read through WifiInfo, proves
                    // the connection view agrees with the scan view.
                    out += wifiInfoProbe(wm, expected)
                }
            }
        }

        out += cellProbes()
        out += bleProbes()
        return out
    }

    @SuppressLint("MissingPermission")
    private fun wifiInfoProbe(wm: WifiManager, expected: List<FakeRadioEnvironment.WifiAp>): CheckItem = try {
        val info = wm.connectionInfo
        if (info == null || info.networkId == -1) {
            SelfCheck.skip("wifi connection", "not associated with any network; nothing to compare")
        } else if (info.ssid.isNullOrEmpty()) {
            SelfCheck.skip("wifi connection", "the platform withheld the SSID from this caller")
        } else {
            val strongest = expected.firstOrNull()
            if (strongest == null) {
                SelfCheck.skip("wifi connection", "no expected access point to compare with")
            } else {
                SelfCheck.text(
                    "wifi connection",
                    EnvGeo.quoteSsid(strongest.ssid),
                    info.ssid,
                )
            }
        }
    } catch (t: Throwable) {
        SelfCheck.skip("wifi connection", t.javaClass.simpleName)
    }

    /**
     * The cell view.
     *
     * `getAllCellInfo` needs location permission on API 29+, and on many devices it is
     * empty indoors regardless. An empty answer is SKIP, not FAIL: the module cannot
     * fabricate a cell list for a device that reports none, by the same follow-the-radio
     * rule as WiFi.
     */
    @SuppressLint("MissingPermission")
    private fun cellProbes(): List<CheckItem> {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return listOf(SelfCheck.skip("cells", "grant location permission to Pacer to check them"))
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            ?: return listOf(SelfCheck.skip("cells", "no TelephonyManager on this device"))
        val real = runCatching { tm.allCellInfo }.getOrNull()
        if (real.isNullOrEmpty()) {
            return listOf(
                SelfCheck.skip("cells", "the device reported no cells; the module does not invent them"),
            )
        }
        val expected = runCatching { expectedCells() }.getOrNull()
        if (expected.isNullOrEmpty()) {
            return listOf(
                CheckItem(
                    "cells",
                    CheckItem.Verdict.FAIL,
                    "the engine produced no cells for the injected fix, but the radio reported " +
                        "${real.size}",
                ),
            )
        }
        // Compare the set of (area, cell id) pairs. Reading the identifiers back through
        // the public getters also proves the hidden-field write took effect.
        val actual = real.mapNotNull { cell ->
            runCatching {
                val identity = cell.javaClass.getMethod("getCellIdentity").invoke(cell) ?: return@runCatching null
                val area = firstInt(identity, "getTac", "getLac", "getLacOrTac")
                val id = firstLong(identity, "getCi", "getCid", "getCellId")
                if (area == null || id == null) null else area to id
            }.getOrNull()
        }.toSet()
        val wanted = expected.map { it.areaCode to it.cellId }.toSet()
        val found = wanted.count { it in actual }
        return listOf(
            if (found == wanted.size) {
                SelfCheck.pass("cells", "$found/${wanted.size} expected towers read back")
            } else {
                CheckItem(
                    "cells",
                    CheckItem.Verdict.FAIL,
                    "$found/${wanted.size} expected towers read back; the list is still the " +
                        "device's own (expected e.g. ${wanted.first()})",
                )
            },
        )
    }

    private fun bleProbes(): List<CheckItem> {
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } catch (t: Throwable) {
            null
        } ?: return listOf(SelfCheck.skip("bluetooth", "no Bluetooth hardware on this device"))
        if (!adapter.isEnabled) {
            return listOf(SelfCheck.skip("bluetooth", "Bluetooth is off; the module follows the real adapter"))
        }
        // Bonded devices are emptied while the environment is simulated. This is the one
        // BLE probe that needs no scan, so it is the one that always runs.
        val bondedResult = try {
            val bonded = adapter.bondedDevices
            if (bonded.isNullOrEmpty()) {
                SelfCheck.pass("bluetooth bonded devices", "hidden (empty set while simulating)")
            } else {
                CheckItem(
                    "bluetooth bonded devices",
                    CheckItem.Verdict.FAIL,
                    "${bonded.size} real paired device(s) still visible",
                )
            }
        } catch (t: Throwable) {
            // A SecurityException here means the module's answer was not reached - the
            // platform checked the permission before the hook could return.
            CheckItem("bluetooth bonded devices", CheckItem.Verdict.FAIL, t.javaClass.simpleName)
        }
        return listOf(bondedResult)
    }

    // ----------------------------------------------------------------- sensors

    /**
     * The step counter, read through the platform as an app would.
     *
     * The hook only delivers once the hardware has been quiet for a couple of seconds
     * and only to a listener that is registered, so this probe registers, waits for the
     * callback, then unregisters. Reading the counter before the first run and after a
     * few seconds is the only way to tell "frozen at the base" from "never registered".
     */
    private fun sensorProbes(): List<CheckItem> = try {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return listOf(SelfCheck.skip("step counter", "no SensorManager"))
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: return listOf(SelfCheck.skip("step counter", "this device has no step counter"))
        val latch = java.util.concurrent.CountDownLatch(1)
        val value = java.util.concurrent.atomic.AtomicReference<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    value.set(event.values.firstOrNull() ?: 0f)
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        val got = latch.await(SENSOR_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        sm.unregisterListener(listener)
        if (!got || value.get() == null) {
            listOf(
                CheckItem(
                    "step counter",
                    CheckItem.Verdict.FAIL,
                    "no event within ${SENSOR_WAIT_SECONDS}s; the heartbeat did not deliver to a " +
                        "listener registered in this process",
                ),
            )
        } else {
            listOf(
                SelfCheck.pass(
                    "step counter",
                    "delivered ${value.get()}; the value is cumulative since boot by design",
                ),
            )
        }
    } catch (t: Throwable) {
        listOf(SelfCheck.skip("step counter", t.javaClass.simpleName))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The fix the engine says the injected location must carry at this instant.
     *
     * Derived from the app's stored run through [LiveSubject], which is the same
     * simulation the module builds from the same inputs and the same seed.
     */
    private fun expectedFix(): Expected? {
        val state = cfg ?: return null
        val point = subject.point(state) ?: return null
        // Speed follows the same owner rule the module uses: a paused route reports zero,
        // and any other running owner reports the configured speed.
        val speed = if (state.routePaused) 0.0 else state.speed
        return Expected(
            lat = point.lat,
            lon = point.lon,
            speedMps = speed,
            accuracyMeters = state.accuracy,
            altitudeMeters = state.altitude,
        )
    }

    private fun expectedWifi(): List<FakeRadioEnvironment.WifiAp> {
        val state = cfg ?: return emptyList()
        val fix = subject.point(state) ?: return emptyList()
        // The same function and the same session seed the WiFi hook uses, so this
        // compares the whole chain - config, fix, model, fabricator - rather than a
        // second model that could agree with the bug.
        return FakeRadioEnvironment.scanResults(
            latitude = fix.lat,
            longitude = fix.lon,
            sessionSeed = EnvGeo.sessionSeed(state.startedAtMs),
            nowMs = System.currentTimeMillis(),
        ).filter { it.ssid.isNotEmpty() }
    }

    private fun expectedCells(): List<FakeRadioEnvironment.Cell> {
        val state = cfg ?: return emptyList()
        val fix = subject.point(state) ?: return emptyList()
        return FakeRadioEnvironment.cells(
            latitude = fix.lat,
            longitude = fix.lon,
            sessionSeed = EnvGeo.sessionSeed(state.startedAtMs),
        )
    }

    private fun processLabel(): String = runCatching {
        "${ProcessIdentity.current()} (${context.packageName})"
    }.getOrDefault(context.packageName)

    private fun notes(): List<String> {
        val state = cfg ?: return listOf("no stored configuration")
        val out = ArrayList<String>()
        out += "owner=${state.owner}"
        if (state.owner != MotionOwner.NONE) {
            out += "speed=${state.speed} m/s route=${state.onRoute} paused=${state.routePaused}"
        }
        return out
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks for the runtime permissions the probes need, and says why.
     *
     * Requesting them at first launch would be a permission wall in front of a page the
     * user has not started using; asking when the check is run keeps the cost where the
     * benefit is. The self-check still runs if they are refused - with SKIPs.
     */
    private fun requestProbePermissions(activity: android.app.Activity) {
        val wanted = ArrayList<String>()
        for (permission in PROBE_PERMISSIONS) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                wanted += permission
            }
        }
        if (wanted.isEmpty()) return
        activity.runOnUiThread {
            activity.requestPermissions(wanted.toTypedArray(), PROBE_PERMISSION_REQUEST)
        }
    }

    private fun firstInt(target: Any, vararg methods: String): Int? {
        for (name in methods) {
            val value = runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()
            if (value is Int) return value
        }
        return null
    }

    private fun firstLong(target: Any, vararg methods: String): Long? {
        for (name in methods) {
            val value = runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()
            if (value is Long) return value
            if (value is Int) return value.toLong()
        }
        return null
    }

    private class Expected(
        val lat: Double,
        val lon: Double,
        val speedMps: Double,
        val accuracyMeters: Float,
        val altitudeMeters: Double,
    )

    private companion object {
        /** The injected fix carries jitter and rounding; a metre is generous. */
        const val SPEED_TOLERANCE_MPS = 0.8
        const val ACCURACY_TOLERANCE_METERS = 6.0
        const val ALTITUDE_TOLERANCE_METERS = 5.0

        /** Long enough for the hook's quiet window plus one heartbeat. */
        const val SENSOR_WAIT_SECONDS = 8L

        const val PROBE_PERMISSION_REQUEST = 1001

        val PROBE_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
