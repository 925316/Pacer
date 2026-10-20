// Route replay: answers requestLocationUpdates / registerLocationListener /
// getCurrentLocation with fabricated positions.
//
// WHY ACTIVE PUSH: the platform only reports a location when a provider produces one. On
// a device with no usable GNSS fix - indoors, or on an emulator - none is ever produced,
// so rewriting a report means waiting forever. The registration is captured instead and
// a fix is pushed on a timer.
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
     * [sink] is whatever object the caller handed over. It is not typed as
     * LocationListener because the Consumer overloads take the same slot, and calling
     * onLocationChanged on a Consumer silently does nothing.
     */
    private class Sink(
        val sink: Any,
        val handler: Handler?,
        /** Where the caller asked its callback to run; null if it did not say. */
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
                    // Captured unconditionally, not only while a run is active: a target
                    // app registers once, usually before the user taps start.
                    //
                    // Nothing is delivered inside the registration itself. A caller that
                    // registers and immediately removes again must receive nothing, or one
                    // minimal pair of calls proves it is not talking to the platform.
                    //
                    // Re-registering replaces the sink, so a fix is not delivered twice.
                    sinks.removeAll { it.sink === captured }
                    sinks.add(Sink(captured, handler, null, provider))
                }
                if (pendingIntent != null) {
                    piSinks.add(PiSink(pendingIntent, provider))
                }
                startTimerIfNeeded()
                // Skip the real registration, or the app receives our fixes and the
                // device's at once.
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

        // The registration API an app on Android 11+ is most likely to use, and a different
        // method from requestLocationUpdates. The listener, executor and provider are read
        // from the same argument list so delivery matches what the caller asked for.
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
                // Same reason as above: the real provider must not also answer.
                null
            }
        }

        HookUtils.hookAllMethods(lm, "unregisterLocationListener") { chain ->
            for (arg in chain.args) {
                if (arg != null) sinks.removeAll { it.sink === arg }
            }
            chain.proceed(chain.args.toTypedArray())
        }

        // The PendingIntent form, used by an app that wants fixes while its own process
        // is asleep.
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

        // Answered directly so the first frame agrees with the stream that follows,
        // instead of showing the real place and jumping a second later.
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

        // One-shot: answer directly rather than waiting for a fix that may never come.
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
                // The caller's own executor, when it named one.
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
     * Matched by type name because a cross-loader `is` check fails silently.
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
        // No position bookkeeping: the position is derived from the clock by CurrentFix,
        // the same value every other layer reads. Storing it here fought the config
        // channel's re-read and pulled straight-line runs back to their start.
        val loc = fakeIfActive(LocationManager.GPS_PROVIDER) ?: return
        for (sink in sinks.toList()) {
            try {
                val deliver = Runnable {
                    try {
                        // The registration may have been removed since the snapshot.
                        if (sinks.contains(sink)) invokeAccept(sink.sink, Location(loc))
                    } catch (_: Throwable) {
                    }
                }
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
