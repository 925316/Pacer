// Cross-process config channel: the module app writes a preference group into
// the Xposed framework service; every hooked process reads it and pushes it
// into PacerRuntimeConfig. Read-only from the hook side (LSPosed enforces
// this). Keys are Pacer's own naming.
package org.pacer.hook.config

import android.content.SharedPreferences
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.CoordinatePolicy
import org.pacer.motion.CoordinateSystem
import org.pacer.motion.RealismToggles
import org.pacer.motion.SpeedPlan

object ConfigSync {

    const val GROUP = "pacer"

    /** Interval of the safety-net re-read described in [startFallbackPoll]. */
    private const val FALLBACK_POLL_MS = 5000L

    fun install(module: io.github.libxposed.api.XposedInterface) {
        try {
            val prefs = module.getRemotePreferences(GROUP)
            apply(prefs)
            prefs.registerOnSharedPreferenceChangeListener { p, _ -> apply(p) }
            startFallbackPoll(prefs)
            PacerLog.i("config sync installed (active=${PacerRuntimeConfig.active})")
        } catch (t: Throwable) {
            // Framework without remote-preference support: hooks stay idle-safe.
            PacerLog.w("config-sync", t)
        }
    }

    /**
     * Safety net for the change listener.
     *
     * The remote preference object is provided by the framework, and two things
     * about it cannot be verified without a device: whether it really delivers
     * cross-process change events, and whether it keeps a strong reference to a
     * listener registered as a lambda. Either failing would leave it looking as
     * if the module works while a config pushed from the module app never takes
     * effect in an already-running target process. A slow unconditional re-read
     * costs one preference read every few seconds and removes the whole class of
     * silent failure. It is deliberately slow: the value only changes when the
     * user taps start or stop.
     */
    private fun startFallbackPoll(prefs: SharedPreferences) {
        try {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed(
                object : Runnable {
                    override fun run() {
                        try {
                            apply(prefs)
                        } catch (_: Throwable) {
                        }
                        handler.postDelayed(this, FALLBACK_POLL_MS)
                    }
                },
                FALLBACK_POLL_MS,
            )
        } catch (_: Throwable) {
        }
    }

    internal fun apply(prefs: SharedPreferences) {
        PacerRuntimeConfig.active = prefs.getBoolean("active", false)
        PacerRuntimeConfig.speedMps = doubleOf(prefs, "speed_mps", 0.0)
        PacerRuntimeConfig.startTimestamp = prefs.getLong("start_ts", 0L)
        PacerRuntimeConfig.latitude = doubleOf(prefs, "lat", 0.0)
        PacerRuntimeConfig.longitude = doubleOf(prefs, "lon", 0.0)
        PacerRuntimeConfig.accuracyMeters = prefs.getFloat("accuracy", 5f)
        PacerRuntimeConfig.altitudeMeters = doubleOf(prefs, "altitude", 0.0)
        PacerRuntimeConfig.bearingDeg = prefs.getFloat("bearing", 0f)
        PacerRuntimeConfig.enableStepSimulation = prefs.getBoolean("step_enabled", true)
        // Defaults to ON, matching the app's default: the
        // environment layers must agree with each other (Wi-Fi and cells are always
        // simulated while a run is active), so BLE being the odd one out would be the
        // anomaly. A key that is absent means an app build older than the switch.
        PacerRuntimeConfig.mockBluetooth = prefs.getBoolean("mock_bt", true)
        // Every realism switch defaults to ON, matching the engine's own defaults: a run
        // started by an older app build that never wrote these keys must still look like
        // a person rather than a perfect line.
        PacerRuntimeConfig.realism = RealismToggles(
            bearingHold = prefs.getBoolean("bearing_hold", true),
            altitudeDrift = prefs.getBoolean("altitude_drift", true),
            accuracyWarmup = prefs.getBoolean("accuracy_warmup", true),
            satelliteJitter = prefs.getBoolean("satellite_jitter", true),
            signalDropout = prefs.getBoolean("signal_dropout", true),
        )
        PacerRuntimeConfig.routeJson = prefs.getString("route_json", "") ?: ""
        // Parsed here, once per config change, because the engine asks for the plan on
        // every sample. An unreadable plan becomes "no plan" rather than a run with
        // invented intervals.
        PacerRuntimeConfig.plan = SpeedPlan.parse(prefs.getString("plan", "") ?: "")
        PacerRuntimeConfig.loopRoute = prefs.getBoolean("loop_route", false)
        PacerRuntimeConfig.routePaused = prefs.getBoolean("route_paused", false)
        PacerRuntimeConfig.routeOffsetMs = prefs.getLong("route_offset_ms", 0L)
        PacerRuntimeConfig.routeResumedAtMs = prefs.getLong("route_resumed_ts", 0L)
        // Parsed once per config change rather than per fix: the table is a string, and a
        // fix is produced tens of times a second. The datum itself is resolved at the
        // call site, because the default depends on which API is being hooked.
        val overrides = CoordinatePolicy.parse(prefs.getString("coord_overrides", "") ?: "")
        PacerRuntimeConfig.coordinateOverrides = overrides
        PacerRuntimeConfig.coordinateSystem = CoordinateSystem.AUTO
    }

    private fun doubleOf(p: SharedPreferences, key: String, def: Double): Double =
        // SharedPreferences stores doubles as Float; accept both forms.
        try {
            java.lang.Double.longBitsToDouble(p.getLong(key, java.lang.Double.doubleToRawLongBits(def)))
        } catch (_: Throwable) {
            try {
                p.getFloat(key, def.toFloat()).toDouble()
            } catch (_: Throwable) {
                def
            }
        }
}
