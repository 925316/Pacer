// system_server side location correction. The app-side hooks only cover a
// process that reads its own LocationManager; this layer rewrites the Location
// objects the framework itself produces and delivers, so a modernized app that
// never touches the app-side API still sees the simulated track. The internal
// class layout differs across releases and OEMs, so every target is probed by
// name and a ROM without a matching class simply hooks nothing.
package org.pacer.hook.hooks.system

import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.LocationInjector
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object SystemLocationServiceHooker {

    // The per-registration delivery objects. Whatever the ROM names them, the
    // class that turns a provider result into a client callback is the point
    // where a freshly created registration receives its cached last location.
    // These are inner classes of the provider manager, so they are not a
    // SystemComponent: there is no alternative service to resolve, only different
    // spellings of the same inner class.
    private val deliveryCandidates = listOf(
        "com.android.server.location.provider.LocationProviderManager\$Registration",
        "com.android.server.location.provider.LocationProviderManager\$LocationRegistration",
        "com.android.server.location.provider.LocationProviderManager\$LocationListenerRegistration",
        "com.android.server.location.provider.LocationProviderManager\$LocationListenerTransport",
        "com.android.server.location.provider.LocationProviderManager\$LocationPendingIntentTransport",
        "com.android.server.location.provider.LocationProviderManager\$GetCurrentLocationTransport",
    )

    private val deliveryMethodNames = arrayOf(
        "acceptLocationChange",
        "deliverOnLocationChanged",
        "onLocationChanged",
    )

    fun install(classLoader: ClassLoader) {
        var hooked = 0
        SystemClassLocator.locate(SystemComponent.LOCATION_PROVIDER_MANAGER, classLoader)
            ?.let { hooked += hookProviderManager(it) }
        SystemClassLocator.locate(SystemComponent.LOCATION_MANAGER_SERVICE, classLoader)
            ?.let { hooked += hookService(it) }
        for (name in deliveryCandidates) {
            val clazz = HookUtils.findClassIfExists(name, classLoader) ?: continue
            hooked += hookDeliveryClass(clazz)
        }
        PacerLog.i("system location hooks installed (targets=$hooked)")
    }

    // --- LocationProviderManager --------------------------------------------

    private fun hookProviderManager(clazz: Class<*>): Int {
        var n = 0
        // The single funnel every provider report passes through, including the
        // cached last location handed to a fresh registration.
        n += HookUtils.hookAllMethods(clazz, "onReportLocation") { chain ->
            try {
                if (PacerRuntimeConfig.snapshot().active) rewriteArgs(chain.args)
            } catch (t: Throwable) {
                PacerLog.w("system-location", t)
            }
            chain.proceed(chain.args.toTypedArray())
        }
        n += hookGetter(clazz, "getLastLocation")
        n += hookGetter(clazz, "getLastLocationUnsafe")
        return n
    }

    // --- LocationManagerService ---------------------------------------------

    private fun hookService(clazz: Class<*>): Int {
        var n = 0
        // Older AOSP pushes reports through this entry instead of the provider
        // manager; both shapes carry the Location in their arguments.
        n += HookUtils.hookAllMethods(clazz, "reportLocation") { chain ->
            try {
                if (PacerRuntimeConfig.snapshot().active) rewriteArgs(chain.args)
            } catch (t: Throwable) {
                PacerLog.w("system-location", t)
            }
            chain.proceed(chain.args.toTypedArray())
        }
        n += hookGetter(clazz, "getLastLocation")
        // callerIsMock decides whether a reported location is flagged as mock.
        // Reporting "not mock" keeps the flag off the cached fix; only a real
        // boolean method is intercepted.
        n += HookUtils.hookAllMethods(clazz, "callerIsMock") { chain ->
            val isBoolean = (chain.executable as? java.lang.reflect.Method)?.returnType === java.lang.Boolean.TYPE
            if (PacerRuntimeConfig.snapshot().active && isBoolean) {
                return@hookAllMethods false
            }
            chain.proceed(chain.args.toTypedArray())
        }
        return n
    }

    // --- per-registration delivery path -------------------------------------

    private fun hookDeliveryClass(clazz: Class<*>): Int {
        var n = 0
        for (method in deliveryMethodNames) {
            n += HookUtils.hookAllMethods(clazz, method) { chain ->
                try {
                    if (PacerRuntimeConfig.snapshot().active) rewriteArgs(chain.args)
                } catch (t: Throwable) {
                    PacerLog.w("system-location", t)
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
        return n
    }

    // --- shared rewriting ----------------------------------------------------

    /** Rewrites the result of a synchronous getter in place when it is a fix. */
    private fun hookGetter(clazz: Class<*>, method: String): Int =
        HookUtils.hookAllMethods(clazz, method) { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            try {
                if (PacerRuntimeConfig.snapshot().active && result != null) {
                    rewriteArgs(listOf(result))
                }
            } catch (t: Throwable) {
                PacerLog.w("system-location", t)
            }
            result
        }

    /** Walks an argument list and rewrites every Location / LocationResult found. */
    private fun rewriteArgs(args: List<Any?>) {
        for (arg in args) {
            if (arg == null) continue
            when {
                isType(arg, "android.location.Location") -> rewriteLocation(arg)
                isType(arg, "android.location.LocationResult") -> rewriteLocationResult(arg)
                arg is List<*> -> arg.forEach { item ->
                    if (item != null && isType(item, "android.location.Location")) rewriteLocation(item)
                }
            }
        }
    }

    private fun rewriteLocationResult(result: Any) {
        val list = try {
            HookUtils.callMethod(result, "asList") as? List<*>
        } catch (_: Throwable) {
            null
        }
        list?.forEach { item ->
            if (item != null && isType(item, "android.location.Location")) rewriteLocation(item)
        }
    }

    /**
     * Overwrites an existing framework Location in place. Field-level rewriting
     * (instead of building a replacement) keeps object identity, so a cached
     * fix that is re-delivered to several clients stays corrected, and it works
     * for the lists nested inside a LocationResult.
     */
    private fun rewriteLocation(location: Any) {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return
        if (cfg.latitude == 0.0 && cfg.longitude == 0.0) return
        val provider = try {
            HookUtils.callMethod(location, "getProvider") as? String
        } catch (_: Throwable) {
            null
        }
        val fake = try {
            LocationInjector.buildLocation(provider ?: LocationManager.GPS_PROVIDER, cfg)
        } catch (t: Throwable) {
            PacerLog.w("system-location", t)
            return
        }
        setDouble(location, "setLatitude", fake.latitude)
        setDouble(location, "setLongitude", fake.longitude)
        setFloat(location, "setAccuracy", fake.accuracy)
        setDouble(location, "setAltitude", fake.altitude)
        setFloat(location, "setBearing", fake.bearing)
        setFloat(location, "setSpeed", fake.speed)
        try {
            HookUtils.callMethod(location, "setTime", System.currentTimeMillis())
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 17) {
            try {
                HookUtils.callMethod(location, "setElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            } catch (_: Throwable) {
            }
        }
        // Clear every mock marker the framework may have set on this instance.
        HookUtils.setFieldQuietly(location, "mIsFromMockProvider", false)
        HookUtils.setFieldQuietly(location, "mMock", false)
        HookUtils.setFieldQuietly(location, "mIsMock", false)
        try {
            HookUtils.callMethod(location, "setMock", false)
        } catch (_: Throwable) {
        }
        try {
            HookUtils.callMethod(location, "setIsFromMockProvider", false)
        } catch (_: Throwable) {
        }
    }

    private fun setDouble(target: Any, method: String, value: Double) {
        try {
            HookUtils.callMethod(target, method, value)
        } catch (_: Throwable) {
        }
    }

    private fun setFloat(target: Any, method: String, value: Float) {
        try {
            HookUtils.callMethod(target, method, value)
        } catch (_: Throwable) {
        }
    }

    private fun isType(value: Any, name: String): Boolean {
        var c: Class<*>? = value.javaClass
        while (c != null) {
            if (c.name == name) return true
            c = c.superclass
        }
        return false
    }
}
