package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.CopyOnWriteArrayList

object RouteReplayHooker {

    /**
     * One registration.
     *
     * [sink] is whatever object the caller handed to requestLocationUpdates. It is not
     * typed as LocationListener because a Consumer overload takes the same slot, and the
     * delivery has to match: calling onLocationChanged on a Consumer (or accept on a
     * listener) silently does nothing and looks exactly like spoofing that is not working.
     */
    private class Sink(val sink: Any, val handler: Handler?, val provider: String)

    private data class PiSink(val intent: android.app.PendingIntent, val provider: String)

    private val sinks = CopyOnWriteArrayList<Sink>()
    private val piSinks = CopyOnWriteArrayList<PiSink>()

    @Volatile private var timerStarted = false
    private val timerLock = Any()

    /** The SDK listener types that must not be treated as a platform location source. */
    private val sdkListenerTypes = listOf(
        "com.amap.api.location.AMapLocationListener",
        "com.baidu.location.BDLocationListener",
        "com.baidu.location.BDAbstractLocationListener",
        "com.tencent.map.geolocation.TencentLocationListener",
    )

    fun install(classLoader: ClassLoader) {
        val lm = HookUtils.findClassIfExists("android.location.LocationManager", classLoader) ?: return

        HookUtils.hookAllMethods(lm, "requestLocationUpdates") { chain ->
            val provider = chain.args.firstOrNull { it is String } as? String
                ?: LocationManager.GPS_PROVIDER
            var captured: Any? = null
            var handler: Handler? = null
            var pendingIntent: android.app.PendingIntent? = null
            for (arg in chain.args) {
                if (arg is Handler) handler = arg
                if (arg is android.app.PendingIntent) pendingIntent = arg
                if (arg != null && isLocationSink(arg)) captured = arg
            }
            if (PacerRuntimeConfig.snapshot().active && (captured != null || pendingIntent != null)) {
                if (captured != null) {
                    // No synchronous first fix and no onProviderEnabled() here. The real
                    // manager delivers nothing inside the registration call, and it only
                    // reports a provider-state change when the state changes. A caller
                    // that registers and immediately removes again must receive nothing -
                    // otherwise one deliberately minimal pair of calls proves the caller
                    // is not talking to the platform.
                    sinks.add(Sink(captured, handler, provider))
                }
                if (pendingIntent != null) {
                    piSinks.add(PiSink(pendingIntent, provider))
                }
                startTimerIfNeeded()
                // The platform must not also register this: a second, real delivery path
                // would race the timer and hand the app two contradictory fixes.
                return@hookAllMethods null
            }
            chain.proceed(chain.args.toTypedArray())
        }

        HookUtils.hookAllMethods(lm, "removeUpdates") { chain ->
            for (arg in chain.args) {
                if (arg == null) continue
                sinks.removeAll { it.sink === arg }
                if (arg is android.app.PendingIntent) piSinks.removeAll { it.intent === arg }
            }
            chain.proceed(chain.args.toTypedArray())
        }

        // getCurrentLocation is a one-shot: the platform will answer with a cached or
        // freshly computed fix, or never answer at all. Answering it directly is both
        // more reliable and more consistent with what the timer pushes.
        HookUtils.hookAllMethods(lm, "getCurrentLocation") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            val consumer = chain.args.firstOrNull { it != null && isConsumer(it) }
            if (cfg.active && consumer != null) {
                val provider = chain.args.firstOrNull { it is String } as? String
                    ?: LocationManager.GPS_PROVIDER
                val loc = LocationInjector.buildLocation(
                    provider,
                    cfg,
                    SystemClock.elapsedRealtimeNanos(),
                )
                val executor = chain.args.firstOrNull { it is java.util.concurrent.Executor }
                    as? java.util.concurrent.Executor
                val deliver = Runnable { invokeAccept(consumer, loc) }
                // Delivered on the caller's executor when it supplied one, because a
                // caller that asked for its own executor is entitled to have the answer
                // arrive there; otherwise the registration's thread is the honest one.
                if (executor != null) executor.execute(deliver) else deliver.run()
                return@hookAllMethods null
            }
            chain.proceed(chain.args.toTypedArray())
        }

        PacerLog.i("route replay hooks installed")
    }

    /**
     * True when [arg] is a plain platform location sink, not an SDK's own listener.
     *
     * Matching by TYPE NAME rather than `is` because the interfaces crossed into this
     * process through a different class loader than the one the argument class was
     * resolved against, and a cross-loader `is` check fails silently.
     */
    private fun isLocationSink(arg: Any): Boolean {
        val name = arg.javaClass.name
        for (excluded in sdkListenerTypes) {
            if (HookUtils.hasTypeByName(arg.javaClass, excluded)) return false
        }
        if (name == "java.util.function.Consumer" || name == "androidx.core.util.Consumer") {
            return true
        }
        return HookUtils.hasTypeByName(arg.javaClass, "android.location.LocationListener") ||
            HookUtils.hasTypeByName(arg.javaClass, "java.util.function.Consumer") ||
            HookUtils.hasTypeByName(arg.javaClass, "androidx.core.util.Consumer")
    }

    private fun isConsumer(arg: Any): Boolean =
        HookUtils.hasTypeByName(arg.javaClass, "java.util.function.Consumer") ||
            HookUtils.hasTypeByName(arg.javaClass, "androidx.core.util.Consumer")

    /** LocationListener.onLocationChanged or Consumer.accept, whichever this is. */
    private fun invokeAccept(target: Any, loc: Location) {
        if (HookUtils.hasTypeByName(target.javaClass, "android.location.LocationListener")) {
            (target as? LocationListener)?.onLocationChanged(loc)
            return
        }
        try {
            HookUtils.callMethod(target, "accept", loc)
        } catch (_: Throwable) {
        }
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
                        invokeAccept(sink.sink, Location(loc))
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
