package org.pacer.app

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.pacer.motion.GeoPoint
import org.pacer.motion.RealismToggles
import org.pacer.motion.SpeedPlan
import org.pacer.motion.StraightLine

/**
 * Writes the simulation config into the Xposed framework's shared preference
 * group ("pacer"). Hooked processes read the same group via
 * org.pacer.hook.config.ConfigSync. Keys must stay in sync with ConfigSync.
 */
object PacerConfigClient {

    private const val TAG = "Pacer"
    private const val GROUP = "pacer"

    @Volatile private var service: XposedService? = null
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * What a screen shows when [start] returned false.
     *
     * One string rather than one per screen: the same failure has the same cause, and two
     * copies of the advice would eventually disagree about it.
     *
     * Deliberately NOT "the module is not installed": the channel binds asynchronously, so
     * a press during the first moments of a cold start fails the same way a missing module
     * does. The wording has to be true for both.
     */
    const val NOT_READY_MESSAGE =
        "The simulation was not started: the module's config channel is not bound yet. " +
            "Try again in a moment; if it keeps happening, check that Pacer is enabled and " +
            "that this app (or the target app) is in its scope."

    private val pendingReady = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Guards against a second listener: the bind callback is process wide. */
    @Volatile private var listening = false

    fun init() {
        if (listening) return
        listening = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                try {
                    prefs = s.getRemotePreferences(GROUP)
                    Log.i(TAG, "xposed service bound, api=${s.apiVersion}")
                } catch (t: Throwable) {
                    Log.e(TAG, "getRemotePreferences failed", t)
                }
                flushReady()
            }

            override fun onServiceDied(s: XposedService) {
                if (service === s) {
                    service = null
                    prefs = null
                }
            }
        })
    }

    /**
     * Runs [action] once the config channel is usable, immediately when it already
     * is. The service binds asynchronously, so a screen that wants to show what is
     * configured cannot simply read it in onCreate.
     */
    fun onReady(action: () -> Unit) {
        if (prefs != null) {
            action()
            return
        }
        pendingReady.add(action)
    }

    private fun flushReady() {
        if (prefs == null) return
        for (action in pendingReady) {
            try {
                action()
            } catch (t: Throwable) {
                Log.w(TAG, "deferred config action failed", t)
            }
        }
        pendingReady.clear()
    }

    /** What the UI needs to show the configuration that is actually stored. */
    data class Stored(
        val active: Boolean,
        /** Wall-clock millis the run started; the clock every derived position uses. */
        val startedAtMs: Long,
        val onRoute: Boolean,
        val routeJson: String,
        val lat: Double,
        val lon: Double,
        val speed: Double,
        val bearing: Float,
        val accuracy: Float,
        val altitude: Double,
        val stepSimulation: Boolean,
        val mockBluetooth: Boolean,
        val realism: RealismToggles,
        /** The interval plan as the user typed it; empty means one steady speed. */
        val plan: String,
        /** Whether a route run starts over when it reaches the end. */
        val loopRoute: Boolean,
    )

    /** The stored configuration, or null while the channel is not ready or unused. */
    fun stored(): Stored? {
        val p = prefs ?: return null
        // No key at all means nothing was ever pushed; reporting fallbacks then would
        // make the UI claim settings the hook side has never seen.
        if (!p.contains("active")) return null
        return Stored(
            active = p.getBoolean("active", false),
            startedAtMs = p.getLong("start_ts", 0L),
            onRoute = !p.getString("route_json", "").isNullOrEmpty(),
            routeJson = p.getString("route_json", "") ?: "",
            lat = readDouble(p, "lat", 0.0),
            lon = readDouble(p, "lon", 0.0),
            speed = readDouble(p, "speed_mps", 0.0),
            bearing = p.getFloat("bearing", 0f),
            accuracy = p.getFloat("accuracy", 5f),
            altitude = readDouble(p, "altitude", 0.0),
            stepSimulation = p.getBoolean("step_enabled", true),
            mockBluetooth = p.getBoolean("mock_bt", true),
            realism = RealismToggles(
                bearingHold = p.getBoolean("bearing_hold", true),
                altitudeDrift = p.getBoolean("altitude_drift", true),
                accuracyWarmup = p.getBoolean("accuracy_warmup", true),
                satelliteJitter = p.getBoolean("satellite_jitter", true),
                signalDropout = p.getBoolean("signal_dropout", true),
            ),
            plan = p.getString("plan", "") ?: "",
            loopRoute = p.getBoolean("loop_route", false),
        )
    }

    /**
     * Start a simulation at [lat]/[lon] (WGS84) at [speed] m/s along [bearing].
     *
     * @return false when the config channel is not bound, in which case NOTHING was
     * stored. The caller must not report a run that the module will never see.
     */
    fun start(
        lat: Double,
        lon: Double,
        speed: Double,
        bearing: Float,
        accuracy: Float = 5f,
        altitudeMeters: Double = 0.0,
        routeJson: String = "",
        plan: String = "",
        loopRoute: Boolean = false,
        mockBluetooth: Boolean = true,
        stepSimulation: Boolean = true,
        realism: RealismToggles = RealismToggles(),
    ): Boolean {
        val p = prefs ?: run {
            Log.w(TAG, "config channel not ready yet")
            return false
        }
        p.edit()
            .putBoolean("active", true)
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(speed))
            .putLong("start_ts", System.currentTimeMillis())
            .putLong("lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(lon))
            .putFloat("accuracy", accuracy)
            .putLong("altitude", java.lang.Double.doubleToRawLongBits(altitudeMeters))
            .putFloat("bearing", bearing)
            .putBoolean("step_enabled", stepSimulation)
            .putBoolean("mock_bt", mockBluetooth)
            .putBoolean("bearing_hold", realism.bearingHold)
            .putBoolean("altitude_drift", realism.altitudeDrift)
            .putBoolean("accuracy_warmup", realism.accuracyWarmup)
            .putBoolean("satellite_jitter", realism.satelliteJitter)
            .putBoolean("signal_dropout", realism.signalDropout)
            .putString("plan", plan)
            .putBoolean("loop_route", loopRoute)
            .putString("route_json", routeJson)
            .apply()
        Log.i(TAG, "simulation started lat=$lat lon=$lon speed=$speed route=${routeJson.isNotEmpty()}")
        return true
    }

    fun stop() {
        val p = prefs ?: return
        p.edit()
            .putBoolean("active", false)
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(0.0))
            .apply()
        Log.i(TAG, "simulation stopped")
    }

    /**
     * Live update from the floating joystick.
     *
     * The position is derived from the start point and the clock, so changing the
     * motion has to move the anchor first: without that, the whole straight line is
     * recomputed from where the run began under the new heading and the subject jumps.
     * Route mode is left alone - it derives its own sample from the route, and
     * re-anchoring would restart the route from its first waypoint.
     */
    fun updateMotion(speed: Double, bearing: Float) {
        val p = prefs ?: return
        val edit = p.edit()
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(speed))
            .putFloat("bearing", bearing)
        if (p.getString("route_json", "").isNullOrEmpty()) {
            anchorHere(p, edit, System.currentTimeMillis())
        }
        edit.apply()
    }

    /**
     * Moves the simulated subject to [lat]/[lon] now, keeping its speed and heading.
     * An active route ignores this: its position comes from the route, and starting
     * that over is a different action, so the caller asks first (see [canTeleport]).
     */
    fun teleport(lat: Double, lon: Double) {
        val p = prefs ?: return
        if (!canTeleport()) return
        p.edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(lon))
            .putLong("start_ts", System.currentTimeMillis())
            .apply()
        Log.i(TAG, "simulation moved to $lat, $lon")
    }

    /** True when a running straight-line simulation is there to be moved. */
    fun canTeleport(): Boolean {
        val state = stored() ?: return false
        return state.active && !state.onRoute
    }

    /**
     * Writes the current derived position as the new start point, so a motion change
     * continues from where the subject is rather than from where the run began.
     */
    private fun anchorHere(p: SharedPreferences, edit: SharedPreferences.Editor, nowMs: Long) {
        val start = GeoPoint(readDouble(p, "lat", 0.0), readDouble(p, "lon", 0.0))
        val elapsed = ((nowMs - p.getLong("start_ts", nowMs)).coerceAtLeast(0L)) / 1000.0
        // An interval plan supplies the distance directly: speed x time would describe a
        // different track from the one the hook side derives from the same plan.
        val meters = SpeedPlan.parse(p.getString("plan", "") ?: "")?.metersAt(elapsed)
            ?: (readDouble(p, "speed_mps", 0.0) * elapsed)
        val here = StraightLine.byDistance(
            start = start,
            bearingDeg = p.getFloat("bearing", 0f).toDouble(),
            meters = meters,
        )
        edit.putLong("lat", java.lang.Double.doubleToRawLongBits(here.lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(here.lon))
            .putLong("start_ts", nowMs)
    }

    private fun readDouble(p: SharedPreferences, key: String, fallback: Double): Double =
        if (p.contains(key)) java.lang.Double.longBitsToDouble(p.getLong(key, 0L)) else fallback
}
