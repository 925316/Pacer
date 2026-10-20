package org.pacer.app

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionOwner
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteClock
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
        /** True while the route run is frozen. */
        val routePaused: Boolean,
        /** Route time reached at the last pause, in milliseconds. */
        val routeOffsetMs: Long,
        /** Wall clock of the last resume; 0 for a run that was never resumed. */
        val routeResumedAtMs: Long,
    ) {
        /**
         * Which input owns speed and heading, derived from what is stored.
         *
         * One place answers this so the two screens and the overlay cannot disagree
         * about whether a hand control is meaningful. It is also based on `active`:
         * `onRoute` alone describes a configured route, not a running one, and a
         * stopped run keeps its `route_json` on purpose.
         */
        val owner: MotionOwner get() = MotionOwner.of(active, onRoute, routePaused, plan)

        /**
         * Seconds into the route run, with any pause taken into account.
         *
         * The single clock both this app and the module use. Everything derived from a
         * route - position, speed, steps, lap - is looked up at this time, so a paused
         * run is frozen rather than tracked with a second stored position.
         */
        fun routeSeconds(nowMs: Long = System.currentTimeMillis()): Double = RouteClock.secondsAt(
            offsetSec = routeOffsetMs / 1000.0,
            resumedAtMs = if (routeResumedAtMs > 0L) routeResumedAtMs else startedAtMs,
            nowMs = nowMs,
            paused = routePaused,
        )
    }

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
            routePaused = p.getBoolean("route_paused", false),
            routeOffsetMs = p.getLong("route_offset_ms", 0L),
            routeResumedAtMs = p.getLong("route_resumed_ts", 0L),
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
            // A new run starts unpaused, and no earlier pause applies to it. Writing
            // these explicitly means a restart cannot inherit a frozen clock by
            // accident, which would look like a run that refuses to move.
            .putBoolean("route_paused", false)
            .putLong("route_offset_ms", 0L)
            .putLong("route_resumed_ts", 0L)
            .apply()
        Log.i(TAG, "simulation started lat=$lat lon=$lon speed=$speed route=${routeJson.isNotEmpty()}")
        return true
    }

    fun stop() {
        val p = prefs ?: return
        p.edit()
            .putBoolean("active", false)
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(0.0))
            .putBoolean("route_paused", false)
            .apply()
        Log.i(TAG, "simulation stopped")
    }

    /**
     * Freezes a route run where it stands, or lets it go again.
     *
     * Pausing does not store a position. A route run is a pure function of the route
     * second, so the only thing a pause has to do is stop that second from advancing:
     * [routeOffsetMs] is the route time reached, and the clock restarts from it on
     * resume. Position, speed, steps and the lap therefore all stay consistent with
     * each other, because they still come from one sample of one run - a separate
     * "paused position" would be a second source of truth for the same fact.
     *
     * `start_ts` is deliberately left alone. It is the seed of the humanized track and
     * the base the step counter is anchored to; changing it would rebuild the run with
     * different noise and move the subject.
     *
     * @return false when no route run is running, in which case nothing was written.
     */
    fun setRoutePaused(paused: Boolean): Boolean {
        val p = prefs ?: return false
        val state = stored() ?: return false
        if (paused) {
            if (!state.owner.canPauseRoute) return false
            val now = System.currentTimeMillis()
            // Ask the stored run for its own clock rather than re-deriving it here: a
            // run that was never resumed has no resume timestamp, and reading 0 as one
            // would put the pause offset at the epoch and fling the subject to the end
            // of the route. Stored.routeSeconds already falls back to the run's start.
            val offsetMs = (state.routeSeconds(now) * 1000.0).toLong()
            p.edit()
                .putBoolean("route_paused", true)
                .putLong("route_offset_ms", offsetMs)
                .apply()
            Log.i(TAG, "route paused at ${offsetMs}ms into the run")
        } else {
            if (!state.owner.canResumeRoute) return false
            p.edit()
                .putBoolean("route_paused", false)
                .putLong("route_resumed_ts", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "route resumed from ${state.routeOffsetMs}ms into the run")
        }
        return true
    }

    /**
     * Live update from the floating joystick.
     *
     * Refused unless the hand owns the run ([MotionOwner.acceptsHandSteering]). Both
     * an automatic route and an interval plan derive distance from the clock, so a
     * speed written here would not steer anything: the subject would move along its
     * own track by the difference, and in loop mode the lap length fixed at the first
     * build would no longer match. The rule lives here rather than only in the overlay
     * so another caller cannot reintroduce it.
     *
     * A hand-steered straight line still has to move its anchor first: without that,
     * the whole line is recomputed from where the run began under the new heading and
     * the subject jumps.
     */
    fun updateMotion(speed: Double, bearing: Float) {
        val p = prefs ?: return
        if (!owner().acceptsHandSteering) {
            Log.i(TAG, "motion update ignored: ${owner()} owns the run")
            return
        }
        val edit = p.edit()
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(speed))
            .putFloat("bearing", bearing)
        anchorHere(p, edit, System.currentTimeMillis())
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

    /**
     * True when the subject can be moved by a long press.
     *
     * This is not the same question as hand steering: a plan run cannot be steered
     * (its speed belongs to the plan) but it can still be moved, because moving only
     * re-anchors the clock and the plan continues from the new point. A route run
     * cannot be moved at all: its position comes from its geometry.
     */
    fun canTeleport(): Boolean {
        val owner = owner()
        return owner == MotionOwner.PLAN || owner == MotionOwner.HAND
    }

    /**
     * Which input owns speed and heading right now.
     *
     * [MotionOwner.NONE] when nothing is stored, so a caller that only wants to know
     * whether hand steering applies does not have to distinguish "no run" from
     * "a run that owns itself".
     */
    fun owner(): MotionOwner = stored()?.owner ?: MotionOwner.NONE

    /** True when hand steering cannot change the run that is active. */
    fun isAutomaticRun(): Boolean = owner().isAutomatic

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
