// system_server side location correction. The app-side hooks only cover a
// process that reads its own LocationManager; this layer rewrites the Location
// objects the framework itself produces and delivers, so a modernized app that
// never touches the app-side API still sees the simulated track. The internal
// class layout differs across releases and OEMs, so every target is probed by
// name and a ROM without a matching class simply hooks nothing.
package org.pacer.hook.hooks.system

import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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

    // --- registration and one-shot APIs --------------------------------------

    /**
     * The delivery method on the caller's own callback object.
     *
     * Framework-internal transport classes are renamed between releases and differ on OEM
     * ROMs; the callback object is supplied by the caller and is stable for the whole
     * registration.
     */
    private val callbackMethods = arrayOf(
        "onLocation",
        "onLocationChanged",
        "acceptLocationChange",
        "deliverOnLocationChanged",
    )

    /** Registration entry points, on both the service and the per-provider manager. */
    private val registrationMethods = arrayOf(
        "registerLocationListener",
        "registerLocationRequest",
        "registerLocationPendingIntent",
        "requestLocationUpdates",
        "registerLocationUpdates",
    )

    /** Classes whose callback method is already intercepted; one hook per class. */
    private val hookedCallbackClasses: MutableSet<Class<*>> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())

    /** Intercepts the caller's own callback, once per class. */
    private fun hookCallbackSink(callback: Any?): Int {
        if (callback == null) return 0
        val clazz = callback.javaClass
        if (!hookedCallbackClasses.add(clazz)) return 0
        var n = 0
        for (method in callbackMethods) {
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

    /** The argument that carries the per-request callback, or null. */
    private fun callbackArg(args: List<Any?>): Any? = args.firstOrNull { arg ->
        if (arg == null) return@firstOrNull false
        val name = arg.javaClass.name
        name.contains("ILocationCallback") ||
            name.contains("ILocationListener") ||
            name.contains("LocationResult") ||
            HookUtils.hasTypeByName(arg.javaClass, "java.util.function.Consumer") ||
            HookUtils.hasTypeByName(arg.javaClass, "android.location.LocationListener")
    }

    /**
     * Covers the one-shot APIs.
     *
     * A provider report never reaches the funnel for these: `getCurrentLocation` is how a
     * modern map app asks for its first fix.
     */
    private fun hookOneShot(clazz: Class<*>): Int {
        var n = 0
        for (method in arrayOf("getCurrentLocation", "getLastLocation", "getLastLocationUnsafe")) {
            n += HookUtils.hookAllMethods(clazz, method) { chain ->
                // Synchronous overloads return the fix; asynchronous ones answer through
                // the callback hooked below.
                var result: Any? = null
                try {
                    result = chain.proceed(chain.args.toTypedArray())
                    if (result != null && isType(result, "android.location.Location")) {
                        rewriteLocation(result)
                    }
                } catch (t: Throwable) {
                    PacerLog.w("system-location", t)
                }
                try {
                    if (PacerRuntimeConfig.snapshot().active) {
                        hookCallbackSink(callbackArg(chain.args))
                    }
                } catch (t: Throwable) {
                    PacerLog.w("system-location", t)
                }
                result
            }
        }
        return n
    }

    /**
     * Covers the registration APIs: the caller's listener is captured and its delivery
     * method intercepted.
     */
    private fun hookRegistrations(clazz: Class<*>): Int {
        var n = 0
        for (method in registrationMethods) {
            n += HookUtils.hookAllMethods(clazz, method) { chain ->
                try {
                    if (PacerRuntimeConfig.snapshot().active) {
                        val listener = chain.args.firstOrNull { arg ->
                            if (arg == null) return@firstOrNull false
                            HookUtils.hasTypeByName(arg.javaClass, "android.location.LocationListener") ||
                                arg.javaClass.name.contains("ILocationListener") ||
                                arg.javaClass.name.contains("ILocationCallback") ||
                                HookUtils.hasTypeByName(arg.javaClass, "java.util.function.Consumer")
                        }
                        hookCallbackSink(listener)
                    }
                } catch (t: Throwable) {
                    PacerLog.w("system-location", t)
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
        return n
    }

    /**
     * Reports every provider as enabled while a simulation runs.
     *
     * An app that checks `isProviderEnabled("gps")` first and sees false never registers,
     * so no downstream hook can reach it.
     */
    private fun hookProviderEnabled(clazz: Class<*>): Int {
        var n = 0
        for (method in arrayOf("isProviderEnabled", "isProviderEnabledForUser")) {
            n += HookUtils.hookAllMethods(clazz, method) { chain ->
                if (PacerRuntimeConfig.snapshot().active) true
                else chain.proceed(chain.args.toTypedArray())
            }
        }
        return n
    }


    // --- LocationProviderManager --------------------------------------------

    private fun hookProviderManager(clazz: Class<*>): Int {
        var n = 0
        // The single funnel every provider report passes through, including the
        // cached last location handed to a fresh registration.
        n += HookUtils.hookAllMethods(clazz, "onReportLocation") { chain ->
            try {
                if (PacerRuntimeConfig.snapshot().active) {
                    // Logged once, not per fix: "the report never reaches this method"
                    // and "it arrives but is rewritten incorrectly" look identical from
                    // the outside, and a single first-hit line settles which it is
                    // without filling the log at one line per second.
                    if (reportHits.getAndIncrement() == 0) {
                        PacerLog.i("system-location: onReportLocation reached (first hit)")
                    }
                    rewriteArgs(chain.args)
                }
            } catch (t: Throwable) {
                PacerLog.w("system-location", t)
            }
            chain.proceed(chain.args.toTypedArray())
        }
        n += hookOneShot(clazz)
        n += hookRegistrations(clazz)
        n += hookProviderEnabled(clazz)
        return n
    }

    /** Counts deliveries into the provider funnel; see the first-hit log above. */
    private val reportHits = java.util.concurrent.atomic.AtomicInteger(0)

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
        n += hookOneShot(clazz)
        n += hookRegistrations(clazz)
        n += hookProviderEnabled(clazz)
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
        // Read the value back: a setter that ran but did not stick (a final field, a
        // setter missing on this release) would leave the real fix in place while the
        // hook reports success. One line per rewrite is cheap next to a fix per second,
        // and without it "the hook ran" and "the hook worked" are indistinguishable.
        val after = try {
            HookUtils.callMethod(location, "getLatitude") as? Double
        } catch (_: Throwable) {
            null
        }
        if (after == null || kotlin.math.abs(after - fake.latitude) > 1e-6) {
            PacerLog.i(
                "system-location: rewrite did not stick - wanted ${fake.latitude}, " +
                    "read back $after (provider=$provider, class=${location.javaClass.name})",
            )
        }
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
        copyExtras(location, fake)
    }

    /**
     * Copies the satellite counts onto the rewritten fix.
     *
     * The coordinates alone are not enough for the SDK that reads these fixes:
     * its own mock check treats a MISSING or non-positive extras["satellites"]
     * as a spoofed location and throws the whole report away (error 15, then
     * every value zeroed), so a framework fix that carries no such key - a
     * network or fused fix usually does not - would be discarded however
     * correct its coordinates are. The app-side injector publishes those keys
     * for the fixes it builds; the system-side rewrite builds through the same
     * injector, so it must hand them on too.
     *
     * Merged, not replaced: the real fix may carry keys this module knows
     * nothing about, and discarding them is its own kind of lie.
     */
    private fun copyExtras(location: Any, fake: android.location.Location) {
        try {
            val source = fake.extras ?: return
            val merged = try {
                HookUtils.callMethod(location, "getExtras") as? Bundle
            } catch (_: Throwable) {
                null
            } ?: Bundle()
            merged.putAll(source)
            HookUtils.callMethod(location, "setExtras", merged)
        } catch (t: Throwable) {
            PacerLog.w("system-location", t)
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
