// Tencent location SDK (com.tencent.map.geolocation).
//
// Unlike AMap and Baidu, this SDK hands the app an *interface*
// (TencentLocation) whose implementation class is obfuscated, and in SDK 8.x
// the implementation body is not even in classes.jar (it ships encrypted in
// assets). Hooking "the TencentLocation implementation class" by name is
// therefore not viable, and an interface method has no body to hook either.
// The stable entry points are the manager and the listener interface, so:
//   1. capture the listener passed to requestLocationUpdates,
//   2. hook that concrete listener class's onLocationChanged callbacks,
//   3. replace the TencentLocation argument with a dynamic proxy that answers
//      the spoofed values and delegates everything else to the real object.
// Tencent uses GCJ-02 by default (TencentLocationManager
// COORDINATE_TYPE_GCJ02 == 1).
package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.CoordinatePolicy
import org.pacer.motion.CoordinateSystem
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

object TencentLocationHooker {

    private const val LISTENER_FQN = "com.tencent.map.geolocation.TencentLocationListener"
    private const val LOCATION_FQN = "com.tencent.map.geolocation.TencentLocation"

    /** Concrete listener classes already hooked, so each is hooked once. */
    private val hookedListeners = ConcurrentHashMap<Class<*>, Boolean>()

    fun install(classLoader: ClassLoader) {
        val manager =
            HookUtils.findClassIfExists("com.tencent.map.geolocation.TencentLocationManager", classLoader)
                ?: return

        HookUtils.hookAllMethods(manager, "requestLocationUpdates") { chain ->
            if (PacerRuntimeConfig.snapshot().active) {
                for (arg in chain.args) {
                    if (arg != null && implementsByName(arg.javaClass, LISTENER_FQN)) {
                        hookListener(arg.javaClass)
                    }
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }

        // getLastKnownLocation() hands out a cached fix without any callback.
        HookUtils.hookAllMethods(manager, "getLastKnownLocation") { chain ->
            val real = chain.proceed(chain.args.toTypedArray())
            proxyFor(real) ?: real
        }

        PacerLog.i("tencent location hooks installed")
    }

    private fun hookListener(clazz: Class<*>) {
        if (hookedListeners.putIfAbsent(clazz, true) != null) return
        // onLocationChanged(TencentLocation, int, String) on the concrete class.
        HookUtils.hookAllMethods(clazz, "onLocationChanged") { chain ->
            val args = chain.args.toTypedArray()
            val index = args.indexOfFirst { it != null && implementsByName(it.javaClass, LOCATION_FQN) }
            if (index >= 0) {
                val replacement = proxyFor(args[index])
                if (replacement != null) args[index] = replacement
            }
            chain.proceed(args)
        }
    }

    /**
     * Builds a TencentLocation proxy carrying the simulated fix, or null when
     * idle / the interface cannot be resolved (then the caller keeps the real
     * object so no code path is left without a result).
     */
    private fun proxyFor(real: Any?): Any? {
        if (real == null) return null
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || (cfg.latitude == 0.0 && cfg.longitude == 0.0)) return null
        val iface = interfaceByName(real.javaClass, LOCATION_FQN) ?: return null

        return try {
            Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                InvocationHandler { _, method, rawArgs ->
                    spoofed(method) ?: invokeReal(real, method, rawArgs)
                },
            )
        } catch (t: Throwable) {
            PacerLog.w("tencent-proxy", t)
            null
        }
    }

    /**
     * Value for a spoofed accessor, adapted to the method's real return type so
     * the proxy never hands back a wrongly typed object. Returns null for every
     * method we do not spoof, which makes the caller delegate to the real fix.
     *
     * The position is resolved PER CALL rather than captured when the proxy is built.
     * The proxy outlives the call that created it, so a captured value would leave a
     * long-lived client reporting wherever the subject stood when it registered.
     */
    private fun spoofed(method: Method): Any? {
        val cfg = PacerRuntimeConfig.snapshot()
        // The last fix actually reported, when there is one: the SDK's own accuracy and
        // bearing accessors have to describe that fix, not the raw configuration, or a
        // warm-up ramp would exist in one API and not in the other.
        val reported = LocationInjector.lastReported()
        val name = method.name
        // Tencent's SDK is GCJ-02 by default; a per-app override still wins, and the
        // reported coordinate type has to follow it or the SDK would describe the datum
        // it did not receive.
        val datum = cfg.datumFor(CoordinateSystem.GCJ02)
        val here = CurrentFix.of(cfg)
        val number: Double = when (name) {
            "getLatitude" -> CoordinatePolicy.convert(here, datum).lat
            "getLongitude" -> CoordinatePolicy.convert(here, datum).lon
            "getSpeed" -> reported?.speedMps?.toDouble() ?: CurrentFix.speedAt(cfg)
            "getBearing" -> reported?.bearingDeg?.toDouble() ?: cfg.bearingDeg.toDouble()
            "getAccuracy" -> reported?.accuracyMeters?.toDouble() ?: cfg.accuracyMeters.toDouble()
            "getTime" -> System.currentTimeMillis().toDouble()
            // 0 == not mock; the SDK exposes this as an int, not a boolean.
            "isMockGps", "getMockGps" -> 0.0
            // 1 == COORDINATE_TYPE_GCJ02, 0 == WGS84. Report what was actually delivered.
            "getCoordinateType" ->
                if (datum == CoordinateSystem.WGS84) 0.0 else 1.0
            else -> return null
        }
        val rt = method.returnType
        return when {
            rt == java.lang.Double.TYPE -> number
            rt == java.lang.Float.TYPE -> number.toFloat()
            rt == java.lang.Long.TYPE -> number.toLong()
            rt == java.lang.Integer.TYPE -> number.toInt()
            else -> null
        }
    }

    private fun invokeReal(real: Any, method: Method, args: Array<Any?>?): Any? =
        try {
            val safe: Array<Any?> = args ?: emptyArray()
            method.invoke(real, *safe)
        } catch (t: Throwable) {
            // Mirrors the real object's own failure instead of inventing a value.
            if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) {
                throw t.targetException
            }
            null
        }

    /** True when [clazz] (or a superclass/interface) is named [fqn]. */
    private fun implementsByName(clazz: Class<*>, fqn: String): Boolean =
        interfaceByName(clazz, fqn) != null

    private fun interfaceByName(clazz: Class<*>, fqn: String): Class<*>? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (i in c.interfaces) {
                if (i.name == fqn) return i
                findIn(i, fqn)?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    private fun findIn(iface: Class<*>, fqn: String): Class<*>? {
        if (iface.name == fqn) return iface
        for (i in iface.interfaces) {
            findIn(i, fqn)?.let { return it }
        }
        return null
    }
}
