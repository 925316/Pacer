// NMEA-0183 spoofing. Some map SDKs parse the raw GPS sentence stream instead
// of Location objects, so a moving coordinate with no matching NMEA is a tell.
// When a simulation is active we intercept addNmeaListener, suppress the real
// sentence stream and feed synthetic GGA/RMC built from the current fix.
// Pacer's own implementation (sentence formats are public NMEA-0183).
package org.pacer.hook.hooks

import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.NmeaFormatter
import java.util.concurrent.CopyOnWriteArrayList

object NmeaHooker {

    private class Sink(val listener: Any, val handler: Handler?)

    private val sinks = CopyOnWriteArrayList<Sink>()

    @Volatile private var timerStarted = false

    fun install(classLoader: ClassLoader) {
        val lm = HookUtils.findClassIfExists("android.location.LocationManager", classLoader) ?: return

        HookUtils.hookAllMethods(lm, "addNmeaListener") { chain ->
            if (PacerRuntimeConfig.snapshot().active) {
                var listener: Any? = null
                var handler: Handler? = null
                for (arg in chain.args) {
                    if (arg is Handler) handler = arg
                    else if (arg != null && hasOnNmea(arg.javaClass)) listener = arg
                }
                if (listener != null) {
                    sinks.add(Sink(listener, handler))
                    startTimerIfNeeded()
                    // Consume: do not register the real listener, suppressing
                    // the genuine sentence stream while simulating.
                    return@hookAllMethods null
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }

        HookUtils.hookAllMethods(lm, "removeNmeaListener") { chain ->
            val l = chain.args.firstOrNull()
            if (l != null) sinks.removeAll { it.listener === l }
            chain.proceed(chain.args.toTypedArray())
        }
        PacerLog.i("nmea hooks installed")
    }

    private fun startTimerIfNeeded() {
        synchronized(this) {
            if (timerStarted) return
            timerStarted = true
        }
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    emit()
                } catch (_: Throwable) {
                } finally {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(tick)
    }

    private fun emit() {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return
        val sample = RouteEngine.sampleAt()
        // The NMEA sentences follow the same derived position as everything else; falling
        // back to the configured start would leave a straight-line run reporting a point
        // that never moves.
        val fix = CurrentFix.of(cfg)
        val lat = sample?.point?.lat ?: fix.lat
        val lon = sample?.point?.lon ?: fix.lon
        if (lat == 0.0 && lon == 0.0) return
        val speedKn = (sample?.speedMps ?: cfg.speedMps) * 1.94384
        val bearing = (sample?.bearingDeg ?: cfg.bearingDeg).toDouble()
        val sats = sample?.satellites ?: 9
        val altitude = sample?.altitudeMeters ?: cfg.altitudeMeters
        val gga = NmeaFormatter.gga(lat, lon, sats, altitude)
        val rmc = NmeaFormatter.rmc(lat, lon, speedKn, bearing)
        for (sink in sinks.toList()) {
            val h = sink.handler ?: Handler(Looper.getMainLooper())
            h.post {
                try {
                    invokeNmea(sink.listener, gga)
                    invokeNmea(sink.listener, rmc)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun invokeNmea(listener: Any, sentence: String) {
        // OnNmeaMessageListener.onNmeaMessage(String, long), API 24+.
        try {
            HookUtils.callMethod(listener, "onNmeaMessage", sentence, System.currentTimeMillis())
        } catch (_: Throwable) {
            try {
                HookUtils.callMethod(listener, "onNmeaReceived", System.currentTimeMillis(), sentence)
            } catch (_: Throwable) {
            }
        }
    }

    /** True when the class (or a superclass) exposes an NMEA callback. */
    private fun hasOnNmea(clazz: Class<*>): Boolean {
        var c: Class<*>? = clazz
        while (c != null) {
            if (c.declaredMethods.any { it.name == "onNmeaMessage" || it.name == "onNmeaReceived" }) return true
            c = c.superclass
        }
        return false
    }
}
