// In-process self-check.
//
// WHY THIS EXISTS: the only person who can run Pacer is the person holding the
// phone, and "what does the device actually report" should not require reading
// logcat. Every probe follows the same shape - compute what the engine says the
// device must report, ask the device, compare.
//
// WHAT IT DELIBERATELY CANNOT DO: decide whether a target app is being spoofed.
//
// Two earlier versions of this file were wrong in opposite directions, and both are
// worth remembering. The first asked the hook "are you installed?" and always heard
// no, because the app ships the hook code and the framework loads it again with its
// own class loader - two copies of every hook object, and the app can only ever read
// its own. The second removed that probe and started measuring the real device
// instead, reporting "getLastKnownLocation returned null" and "wifi SSIDs missing" as
// failures while the system-server layer was rewriting fixes correctly.
//
// So this app is kept OUT of the module's scope (`scope.list` does not list it). It
// is an ordinary client, and the report is an honest statement of what an ordinary
// client sees - which is the same path a target app takes through the system server.
// The scope note printed on every report says so, because the report travels without
// the screen that explained it.
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
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.env.FakeRadioEnvironment
import org.pacer.hook.hooks.system.vendor.ProcessIdentity
import org.pacer.motion.CheckItem
import org.pacer.motion.CoordinatePolicy
import org.pacer.motion.CoordinateSystem
import org.pacer.motion.CoordinateTransform
import org.pacer.motion.GeoMath
import org.pacer.motion.MotionOwner
import org.pacer.motion.SelfCheck
import org.pacer.motion.SelfCheckReport
import java.util.Locale

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
        //
        // The request is asynchronous, so a check that started in the same breath read
        // "not granted" for every stream and reported a wall of SKIPs - which looks exactly
        // like a device that cannot support the feature. Return that as its own answer and
        // let the caller re-run once the dialog is done.
        if (requestProbePermissions(context)) {
            return SelfCheckReport.of(
                processLabel(),
                listOf(
                    SelfCheck.skip(
                        "permissions",
                        "Pacer does not have its permissions yet. Grant them, then run the " +
                            "check again.",
                    ),
                ),
                notes(),
                scopeNote = SCOPE_NOTE,
            )
        }
        val items = ArrayList<CheckItem>()

        if (cfg == null || !cfg.active) {
            items += SelfCheck.skip(
                "simulation",
                "no run is active; start one, then run the check again so the injected " +
                    "values differ from the device's own",
            )
            return SelfCheckReport.of(
                processLabel(),
                items,
                notes(),
                scopeNote = SCOPE_NOTE,
            )
        }

        val expected = runCatching { expectedFix() }.getOrNull()
        items += coordinateProbes()
        items += locationProbes(expected)
        items += radioProbes()
        // Sensors last and only when a step run is on: the probe waits several seconds
        // for the hook's own heartbeat, which would otherwise delay every check.
        if (cfg.stepSimulation) {
            items += sensorProbes()
        } else {
            items += SelfCheck.skip("step counter", "step simulation is switched off")
        }

        return SelfCheckReport.of(processLabel(), items, notes(), scopeNote = SCOPE_NOTE)
    }

    // ---------------------------------------------------------------- coordinates

    /**
     * What datum each hooked SDK would be given, and by how much that differs from WGS-84.
     *
     * This is pure arithmetic on the stored configuration, but it is the one part of the
     * pipeline a user cannot verify by opening a map: a 500 m datum error and a working
     * spoof look identical on a phone, and the map that would reveal it is exactly the
     * thing being spoofed. Printing the numbers is how the app states its own answer
     * instead of asking the user to trust it.
     *
     * The offset is reported together with the distance so a wrong transform cannot hide
     * behind a plausible-looking coordinate: a shift of 0 m means the conversion did
     * nothing, and a shift far outside 100-700 m means it did the wrong thing.
     */
    private fun coordinateProbes(): List<CheckItem> {
        val state = cfg ?: return emptyList()
        val wgs = runCatching { subject.point(state) }.getOrNull()
            ?: return listOf(
                SelfCheck.skip("coordinate system", "the engine has no current fix to convert"),
            )
        val overrides = CoordinatePolicy.parse(state.coordinateOverrides)
        val override = overrides.firstOrNull { it.appliesTo(context.packageName) }?.system
        val overridden = if (override == null) "" else " (settings override for this package: $override)"

        val out = ArrayList<CheckItem>()
        val gcj = CoordinateTransform.wgs84ToGcj02(wgs)
        val bd = CoordinateTransform.wgs84ToBd09(wgs)

        // AMap and Tencent take one datum and have no request to read, so this is the whole
        // rule for them; the only thing that can change it is a package override.
        out += SelfCheck.pass(
            "AMap / Tencent datum",
            String.format(
                Locale.US,
                "%s: %.5f, %.5f, %.0f m from the WGS-84 fix%s",
                (override ?: CoordinateSystem.GCJ02).name, gcj.lat, gcj.lon,
                GeoMath.haversine(wgs, gcj), overridden,
            ),
        )

        // Baidu names the datum it wants per client, and the hook follows that rather than
        // forcing BD-09. Both branches are printed because which one runs depends on the
        // target app, which this report cannot see.
        out += SelfCheck.pass(
            "Baidu datum",
            String.format(
                Locale.US,
                "follows the client coorType: gcj02 -> %.5f, %.5f, bd09ll -> %.5f, %.5f; " +
                    "bd09mc is delivered as BD-09 rather than projected to Mercator",
                gcj.lat, gcj.lon, bd.lat, bd.lon,
            ),
        )

        // The second rotation is small next to the GCJ-02 offset, and conflating the two is
        // how "Baidu is another 500 m off" gets believed.
        out += SelfCheck.pass(
            "BD-09 extra rotation",
            String.format(Locale.US, "%.0f m beyond GCJ-02", GeoMath.haversine(gcj, bd)),
        )

        // A fix outside the offset's coverage legitimately converts to itself, so this is a
        // statement about which branch ran, not a pass/fail on the size of the shift.
        out += SelfCheck.pass(
            "offset coverage",
            if (GeoMath.haversine(wgs, gcj) < 1.0) {
                "this fix is outside the boundary the GCJ-02 scheme covers, so every " +
                    "conversion returns it unchanged; that matches what the providers do"
            } else {
                "this fix is inside the covered area, so GCJ-02 and BD-09 both shift it"
            },
        )
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
            // Not a failure: this app is normally outside the module's scope, so nothing
            // injected a fix and the platform has no cache to answer from. Calling that a
            // FAIL was the mistake that made an earlier report read as "the module is
            // broken" while the system-server layer was rewriting fixes correctly.
            out += SelfCheck.skip(
                "getLastKnownLocation",
                "the platform had no cached fix to return. This app is outside the module's " +
                    "scope by design, so this says nothing about whether a target app is " +
                    "being spoofed",
            )
            return out
        }

        // Reported as a measurement, not a verdict, for the same reason: in a process the
        // module does not hook the honest answer is "here is what this process sees and
        // how far it is from the run", and the reader decides what that means.
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
        out += SelfCheck.text("provider", LocationManager.GPS_PROVIDER, last.provider ?: "")
        return out
    }

    private fun position(name: String, expected: Expected, lat: Double, lon: Double): CheckItem {
        val meters = EnvGeo.metersBetween(expected.lat, expected.lon, lat, lon)
        return if (meters <= SelfCheck.POSITION_TOLERANCE_METERS) {
            SelfCheck.pass(name, String.format(java.util.Locale.US, "%.5f,%.5f (%.2f m off)", lat, lon, meters))
        } else {
            // A distance this process's fix is away from the run. Whether that means "the
            // module is not active in this app" or "the module is broken" cannot be told
            // from here, so the item says which numbers disagreed and leaves the verdict
            // to the scope note at the top of the report.
            CheckItem(
                name,
                CheckItem.Verdict.FAIL,
                String.format(
                    java.util.Locale.US,
                    "expected %.5f,%.5f but got %.5f,%.5f - %.0f m off; this process's fix " +
                        "does not match the run",
                    expected.lat, expected.lon, lat, lon, meters,
                ),
            )
        }
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
        val sample = subject.sample(state) ?: return null
        // Not state.speed: a route carries its own speed curve, and a straight run under an
        // interval plan carries the plan's current step. Comparing the injected speed with
        // the configured one failed every correct route run.
        val speed = if (state.routePaused) 0.0 else sample.speedMps
        return Expected(
            lat = sample.point.lat,
            lon = sample.point.lon,
            speedMps = speed,
            accuracyMeters = sample.accuracyMeters,
            // The module adds the configured ground altitude to the engine's drift.
            altitudeMeters = state.altitude + sample.altitudeMeters,
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
     * benefit is.
     *
     * @return true when a request was posted, so the caller knows this run saw no
     *   permissions yet and its probes cannot say anything.
     */
    private fun requestProbePermissions(activity: android.app.Activity): Boolean {
        val wanted = AppPermissions.missing(activity)
        if (wanted.isEmpty()) return false
        activity.runOnUiThread {
            activity.requestPermissions(wanted.toTypedArray(), PROBE_PERMISSION_REQUEST)
        }
        return true
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

    companion object {
        /** The request code MainActivity answers, so the check can re-run by itself. */
        const val PROBE_PERMISSION_REQUEST = 1001

        /** The injected fix carries jitter and rounding; a metre is generous. */
        private const val SPEED_TOLERANCE_MPS = 0.8
        private const val ACCURACY_TOLERANCE_METERS = 6.0
        private const val ALTITUDE_TOLERANCE_METERS = 5.0

        /**
         * Printed at the top of every report.
         *
         * The report is pasted into a chat without the panel around it, so the limit has
         * to travel with it. "Position OK" here means this app's own LocationManager
         * returned the simulated fix; whether the target app sees the same depends on the
         * system-server layer, which is checked by opening that app and looking.
         */
        private const val SCOPE_NOTE =
            "this process only. A PASS proves this app's own API calls return the simulated " +
                "values; it does not prove any other app does. To check a target app, open it " +
                "and look at its map."

        /** Long enough for the hook's quiet window plus one heartbeat. */
        private const val SENSOR_WAIT_SECONDS = 8L
    }
}
