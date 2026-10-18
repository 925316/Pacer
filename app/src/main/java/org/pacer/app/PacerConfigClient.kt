package org.pacer.app

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.pacer.motion.RealismToggles

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

    fun init() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                try {
                    prefs = s.getRemotePreferences(GROUP)
                    Log.i(TAG, "xposed service bound, api=${s.apiVersion}")
                } catch (t: Throwable) {
                    Log.e(TAG, "getRemotePreferences failed", t)
                }
            }

            override fun onServiceDied(s: XposedService) {
                if (service === s) {
                    service = null
                    prefs = null
                }
            }
        })
    }

    val isReady: Boolean get() = prefs != null

    /** Start a simulation at [lat]/[lon] (WGS84) at [speed] m/s along [bearing]. */
    fun start(
        lat: Double,
        lon: Double,
        speed: Double,
        bearing: Float,
        accuracy: Float = 5f,
        altitudeMeters: Double = 0.0,
        routeJson: String = "",
        mockBluetooth: Boolean = true,
        stepSimulation: Boolean = true,
        realism: RealismToggles = RealismToggles(),
    ) {
        val p = prefs ?: run {
            Log.w(TAG, "config channel not ready yet")
            return
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
            .putString("route_json", routeJson)
            .apply()
        Log.i(TAG, "simulation started lat=$lat lon=$lon speed=$speed route=${routeJson.isNotEmpty()}")
    }

    fun stop() {
        val p = prefs ?: return
        p.edit()
            .putBoolean("active", false)
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(0.0))
            .apply()
        Log.i(TAG, "simulation stopped")
    }

    /** Live update from the floating joystick: speed and heading only. */
    fun updateMotion(speed: Double, bearing: Float) {
        val p = prefs ?: return
        p.edit()
            .putLong("speed_mps", java.lang.Double.doubleToRawLongBits(speed))
            .putFloat("bearing", bearing)
            .apply()
    }
}
