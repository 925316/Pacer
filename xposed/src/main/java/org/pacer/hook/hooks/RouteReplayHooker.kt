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
    private class Sink(
        val sink: Any,
        val handler: Handler?,
        /**
         * The executor the caller asked deliveries to arrive on, when it supplied one.
         *
         * `registerLocationListener` takes an Executor instead of a Handler, and a caller
         * that chose one has stated where its callback must run. Ignoring it and answering
         * on the main thread is a thread-affinity violation the caller cannot detect
         * except as a race in its own code.
         */
        val executor: java.util.concurrent.Executor?,
        val provider: String,
    )

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
            if (captured != null || pendingIntent != null) {
                if (captured != null) {
                    // Captured unconditionally, NOT only while a run is active. A target
                    // app registers its listener once, usually long before the user taps
                    // start, so a capture guarded by `active` records nothing and the
                    // timer then has no sink to push to - which looks exactly like
                    // spoofing that does not work. The active check belongs in the
                    // delivery path (fakeIfActive), where it is evaluated per tick.
                    //
                    // No synchronous first fix and no onProviderEnabled() here. The real
                    // manager delivers nothing inside the registration call, and it only
                    // reports a provider-state change when the state changes. A caller
                    // that registers and immediately removes again must receive nothing -
                    // otherwise one deliberately minimal pair of calls proves the caller
                    // is not talking to the platform.
                    // Re-registering the same listener must not produce two sinks, or the
                    // app receives each fix twice.
                    sinks.removeAll { it.sink === captured }
                    sinks.add(Sink(captured, handler, null, provider))
                }
                if (pendingIntent != null) {
                    piSinks.add(PiSink(pendingIntent, provider))
                }
                startTimerIfNeeded()
                // Skip the real registration for this caller. Without this the platform
                // also delivers real fixes, and the app sees two contradictory streams:
                // the timer's simulated one and the device's own.
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

        // registerLocationListener is the registration API an app on Android 11+ is most
        // likely to use, and it is a DIFFERENT method from requestLocationUpdates. Missing
        // it was the widest hole in this layer: an app that registered here reached the
        // real provider, and no amount of correct work in requestLocationUpdates could
        // affect it. The listener, the executor and the provider are read from the same
        // argument list, so the delivery below matches what the caller asked for.
        HookUtils.hookAllMethods(lm, "registerLocationListener") { chain ->
            val provider = chain.args.firstOrNull { it is String } as? String
                ?: LocationManager.GPS_PROVIDER
            val listener = chain.args.firstOrNull { it != null && isLocationSink(it) }
            if (listener == null) {
                chain.proceed(chain.args.toTypedArray())
            } else {
                val executor = chain.args.firstOrNull { it is java.util.concurrent.Executor }
                    as? java.util.concurrent.Executor
                sinks.removeAll { it.sink === listener }
                sinks.add(Sink(listener, null, executor, provider))
                startTimerIfNeeded()
                // Same reason as requestLocationUpdates: the real provider must not also
                // answer, or the app receives our track and the device's at once.
                null
            }
        }

        HookUtils.hookAllMethods(lm, "unregisterLocationListener") { chain ->
            for (arg in chain.args) {
                if (arg != null) sinks.removeAll { it.sink === arg }
            }
            chain.proceed(chain.args.toTypedArray())
        }

        // The PendingIntent form of the same registration. An app that wants fixes while
        // its own process is asleep uses this, and it was uncovered: the intent reached
        // the real provider unfiltered.
        HookUtils.hookAllMethods(lm, "registerLocationPendingIntent") { chain ->
            val provider = chain.args.firstOrNull { it is String } as? String
                ?: LocationManager.GPS_PROVIDER
            val intent = chain.args.firstOrNull { it is android.app.PendingIntent }
                as? android.app.PendingIntent
            if (intent == null) {
                chain.proceed(chain.args.toTypedArray())
            } else {
                piSinks.removeAll { it.intent === intent }
                piSinks.add(PiSink(intent, provider))
                startTimerIfNeeded()
                null
            }
        }

        HookUtils.hookAllMethods(lm, "unregisterLocationPendingIntent") { chain ->
            for (arg in chain.args) {
                if (arg is android.app.PendingIntent) piSinks.removeAll { it.intent === arg }
            }
            chain.proceed(chain.args.toTypedArray())
        }

        // getLastKnownLocation is a cache read, and an app that asks for a cached fix
        // before starting a stream would otherwise get either the device's own position or
        // null. Answering it with the simulated fix makes the first frame consistent with
        // everything that follows, instead of showing the real place and jumping a second
        // later.
        HookUtils.hookAllMethods(lm, "getLastKnownLocation") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            val provider = chain.args.firstOrNull { it is String } as? String
            if (cfg.active && provider != null) {
                LocationInjector.buildLocation(
                    provider,
                    cfg,
                    SystemClock.elapsedRealtimeNanos(),
                )
            } else {
                chain.proceed(chain.args.toTypedArray())
            }
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
                val deliver = Runnable {
                    try {
                        // The registration may have been removed between the snapshot and
                        // this post; delivering then would be a callback after
                        // unregisterLocationListener.
                        if (sinks.contains(sink)) invokeAccept(sink.sink, Location(loc))
                    } catch (_: Throwable) {
                    }
                }
                // The caller's executor wins, then its handler, then the main thread: the
                // delivery has to land where the caller asked, in that order.
                when {
                    sink.executor != null -> sink.executor.execute(deliver)
                    else -> (sink.handler ?: Handler(Looper.getMainLooper())).post(deliver)
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
