// In-process self-check.
//
// WHY THIS EXISTS: the only person who can run Pacer is the person holding the
// phone, and "does the hook work" is not a question a user should have to answer by
// reading logcat. Every probe here follows the same shape - compute what the engine
// says the device must report, ask the device, compare - so the verdict is derived
// from the same engine that would be wrong, not from a second implementation that
// could agree with the bug.
//
// It runs INSIDE the Pacer app's own process, which is in the module's scope. That is
// what makes it possible at all: the hooks are already installed here, so a plain
// LocationManager / WifiManager / SensorManager call returns the injected answer. No
// root, no cross-process status file, no adb.
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
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.env.FakeRadioEnvironment
import org.pacer.hook.hooks.system.vendor.ProcessIdentity
import org.pacer.hook.utils.HookStatus
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
 *   the same run the hooks are reading rather than from a second copy of the settings.
 */
class SelfCheckRunner(
    private val context: Context,
    private val cfg: PacerConfigClient.Stored?,
) {

    fun run(): SelfCheckReport {
        // The probes need these to read anything at all; asking here rather than at
        // launch keeps a permission wall away from a page nobody is using yet.
        requestProbePermissions(context)
        val items = ArrayList<CheckItem>()
        // Install report first: when nothing is injected, every later probe is a
        // measurement of the real device and its "FAIL" says nothing about Pacer.
        items += installReport()

        if (cfg == null || !cfg.active) {
            items += SelfCheck.skip(
                "simulation",
                "no run is active; start one, then run the check again so the injected " +
                    "values differ from the device's own",
            )
            return SelfCheckReport.of(processLabel(), items, notes())
        }

        // Before anything is measured: if the config never arrived, every later
        // expectation is the module's defaults and the whole sheet is meaningless.
        items += configChannelProbe()
        items += locationProbes()
        items += radioProbes()
        // A Settings read needs no permission, so it runs even when the probes above are
        // skipped: it is the one anti-detection answer the app can always ask for.
        items += mockLocationSettingProbe()
        // Sensors last and only when a step run is on: the probe waits several seconds
        // for the hook's own heartbeat, which would otherwise delay every check.
        if (cfg.stepSimulation) {
            items += sensorProbes()
        } else {
            items += SelfCheck.skip("step counter", "step simulation is switched off")
        }

        return SelfCheckReport.of(processLabel(), items, notes())
    }

    // ---------------------------------------------------------------- installs

    /**
     * What attached in this process.
     *
     * `HookStatus` is the module's own record, so this item is the one that can tell
     * "the probe failed because the value is wrong" from "the probe failed because
     * nothing was installed here" - the two look identical from a LocationManager.
     */
    private fun installReport(): CheckItem {
        val total = HookStatus.countHooked()
        val summary = HookStatus.summary()
        return if (total > 0) {
            SelfCheck.pass("hooks installed", summary)
        } else {
            CheckItem(
                "hooks installed",
                CheckItem.Verdict.FAIL,
                "no hook attached in this process. Add Pacer to its own LSPosed scope and " +
                    "restart it. ($summary)",
            )
        }
    }

    /**
     * Whether the module copied a config into this process at all.
     *
     * This is the probe that makes the rest trustworthy. The expected values below are
     * computed from `PacerRuntimeConfig`, which only the hook side populates: without
     * that channel the holder keeps its defaults and the "expected" fix would be the
     * origin, not the run. Comparing against the app's own stored settings instead would
     * hide a dead config channel behind a green sheet, so the channel itself is a check.
     */
    private fun configChannelProbe(): CheckItem {
        val state = cfg ?: return CheckItem(
            "config channel",
            CheckItem.Verdict.FAIL,
            "the app has no stored configuration to compare with",
        )
        val snap = PacerRuntimeConfig.snapshot()
        if (!snap.active) {
            return CheckItem(
                "config channel",
                CheckItem.Verdict.FAIL,
                "the stored run says active=true but this process never received it; the " +
                    "hooks are idle here",
            )
        }
        val seconds = kotlin.math.abs(snap.startTimestamp - state.startedAtMs) / 1000.0
        return if (seconds <= CONFIG_START_TOLERANCE_SECONDS) {
            SelfCheck.pass(
                "config channel",
                "received start=${snap.startTimestamp}, active=true",
            )
        } else {
            CheckItem(
                "config channel",
                CheckItem.Verdict.FAIL,
                "this process holds a different run than the app (start ${snap.startTimestamp} " +
                    "vs ${state.startedAtMs}); the config did not reach it",
            )
        }
    }

    // ---------------------------------------------------------------- location

    private fun locationProbes(): List<CheckItem> {
        val out = ArrayList<CheckItem>()
        val expected = runCatching { expectedFix() }.getOrNull()
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
        } else {
            out += position("getLastKnownLocation", expected, last.latitude, last.longitude)
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
        }

        // The permission state the anti-detection layer answers with is checked in
        // run(), because it needs no permission and therefore always runs.
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
        return try {
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

    /** The fix the engine says the injected location must carry at this instant. */
    private fun expectedFix(): Expected {
        val snap = PacerRuntimeConfig.snapshot()
        val point = CurrentFix.of(snap)
        val route = org.pacer.hook.hooks.RouteEngine.sampleAt()
        val state = cfg
        return Expected(
            lat = route?.point?.lat ?: point.lat,
            lon = route?.point?.lon ?: point.lon,
            speedMps = route?.speedMps ?: CurrentFix.linearSpeedAt(snap),
            accuracyMeters = route?.accuracyMeters ?: state?.accuracy ?: snap.accuracyMeters,
            altitudeMeters = (state?.altitude ?: snap.altitudeMeters) +
                (route?.altitudeMeters ?: 0.0),
        )
    }

    private fun expectedWifi(): List<FakeRadioEnvironment.WifiAp> {
        val snap = PacerRuntimeConfig.snapshot()
        val fix = CurrentFix.of(snap)
        // The same function and the same session seed the WiFi hook uses, so this
        // compares the whole chain - config, fix, model, fabricator - rather than a
        // second model that could agree with the bug.
        return FakeRadioEnvironment.scanResults(
            latitude = fix.lat,
            longitude = fix.lon,
            sessionSeed = EnvGeo.sessionSeed(snap.startTimestamp),
            nowMs = System.currentTimeMillis(),
        ).filter { it.ssid.isNotEmpty() }
    }

    private fun expectedCells(): List<FakeRadioEnvironment.Cell> {
        val snap = PacerRuntimeConfig.snapshot()
        val fix = CurrentFix.of(snap)
        return FakeRadioEnvironment.cells(
            latitude = fix.lat,
            longitude = fix.lon,
            sessionSeed = EnvGeo.sessionSeed(snap.startTimestamp),
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

        /**
         * A start timestamp that differs by more than this did not come from the same
         * run: the app writes it once, and the hook copies it verbatim.
         */
        const val CONFIG_START_TOLERANCE_SECONDS = 1.0

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
