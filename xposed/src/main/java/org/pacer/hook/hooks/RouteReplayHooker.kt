package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.CopyOnWriteArrayList

object RouteReplayHooker {

    private data class Sink(val listener: LocationListener?, val handler: Handler?, val provider: String)

    private data class PiSink(val intent: android.app.PendingIntent, val provider: String)

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val piSinks = CopyOnWriteArrayList<PiSink>()

    @Volatile private var timerStarted = false
    private val timerLock = Any()

    fun install(classLoader: ClassLoader) {
        val lm = HookUtils.findClassIfExists("android.location.LocationManager", classLoader) ?: return

        HookUtils.hookAllMethods(lm, "requestLocationUpdates") { chain ->
            val provider = chain.args.firstOrNull { it is String } as? String ?: LocationManager.GPS_PROVIDER
            var listener: LocationListener? = null
            var handler: Handler? = null
            var pendingIntent: android.app.PendingIntent? = null
            for (arg in chain.args) {
                if (arg is LocationListener) listener = arg
                if (arg is Handler) handler = arg
                if (arg is android.app.PendingIntent) pendingIntent = arg
            }
            if (PacerRuntimeConfig.snapshot().active && (listener != null || pendingIntent != null)) {
                if (listener != null) {
                    // No synchronous first fix and no onProviderEnabled() here. The real
                    // manager delivers nothing inside the registration call, and it only
                    // reports a provider-state change when the state changes. A caller
                    // that registers and immediately removes again must receive nothing -
                    // otherwise one deliberately minimal pair of calls proves the caller
                    // is not talking to the platform.
                    sinks.add(Sink(listener, handler, provider))
                }
                if (pendingIntent != null) {
                    piSinks.add(PiSink(pendingIntent, provider))
                }
                startTimerIfNeeded()
                return@hookAllMethods null
            }
            chain.proceed(chain.args.toTypedArray())
        }

        HookUtils.hookAllMethods(lm, "removeUpdates") { chain ->
            val l = chain.args.firstOrNull()
            if (l is LocationListener) sinks.removeAll { it.listener === l }
            if (l is android.app.PendingIntent) piSinks.removeAll { it.intent === l }
            chain.proceed(chain.args.toTypedArray())
        }
        PacerLog.i("route replay hooks installed")
    }

    private fun startTimerIfNeeded() {
        synchronized(timerLock) {
            if (timerStarted) return
            timerStarted = true
        }
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    pushTick()
                } catch (_: Throwable) {
                } finally {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(tick)
    }

    private fun pushTick() {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return
        // No position bookkeeping here: the current position is derived from the clock
        // by CurrentFix, which is the same value the injected location and every
        // environment layer read, so a tick only has to deliver what that says now.
        // Advancing a stored position here is what used to fight the config channel's
        // re-read and pull a straight-line run back to its start every few seconds.
        val loc = fakeIfActive(LocationManager.GPS_PROVIDER) ?: return
        for (sink in sinks.toList()) {
            try {
                (sink.handler ?: Handler(Looper.getMainLooper())).post {
                    try {
                        // The registration may have been removed between the snapshot and
                        // this post; delivering then would be a callback after removeUpdates.
                        if (!sinks.contains(sink)) return@post
                        sink.listener?.onLocationChanged(Location(loc))
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        for (pi in piSinks.toList()) {
            try {
                val ctx = appContext() ?: continue
                val intent = android.content.Intent()
                intent.putExtra(LocationManager.KEY_LOCATION_CHANGED, Location(loc))
                intent.putExtra(LocationManager.KEY_PROVIDER_ENABLED, true)
                pi.intent.send(ctx, 0, intent)
            } catch (_: Throwable) {
            }
        }
    }

    /** Best-effort application context without a constructor-provided Context. */
    private fun appContext(): android.content.Context? =
        try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (_: Throwable) {
            null
        }

    private fun fakeIfActive(provider: String): Location? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return LocationInjector.buildLocation(provider, cfg, SystemClock.elapsedRealtimeNanos())
    }
}
