package org.pacer.hook.hooks

import org.pacer.motion.CoordinateTransform
import org.pacer.motion.GeoPoint
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.ConcurrentHashMap

object MapSdkHooker {

    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    private class Coords(val lat: Double, val lon: Double)

    /** Active target coords in WGS84, or null when idle. */
    private fun activeWgs84(): GeoPoint? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return GeoPoint(cfg.latitude, cfg.longitude)
    }

    fun install(classLoader: ClassLoader) {
        hookAmap(classLoader)
        hookBaidu(classLoader)
        PacerLog.i("map sdk hooks installed")
    }

    // ---- AMap (GCJ-02) ----
    private fun gcjCoords(): Coords? = activeWgs84()?.let {
        val g = CoordinateTransform.wgs84ToGcj02(it)
        Coords(g.lat, g.lon)
    }

    private fun hookAmap(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("com.amap.api.location.AMapLocation", classLoader) ?: return

        HookUtils.hookAllMethods(locClass, "getLatitude") { chain ->
            val c = gcjCoords()
            if (c != null) c.lat else chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(locClass, "getLongitude") { chain ->
            val c = gcjCoords()
            if (c != null) c.lon else chain.proceed(chain.args.toTypedArray())
        }

        // Fill freshly constructed instances so internal field readers (not
        // going through getters) also see plausible coords.
        HookUtils.hookAllConstructors(locClass) { chain ->
            val r = chain.proceed(chain.args.toTypedArray())
            val obj = chain.thisObject
            val c = gcjCoords()
            if (obj != null && c != null) {
                HookUtils.setFieldQuietly(obj, "mLatitude", c.lat)
                HookUtils.setFieldQuietly(obj, "mLongitude", c.lon)
            }
            r
        }

        // Listener callbacks: patch the AMapLocation argument in place.
        val clientClass = HookUtils.findClassIfExists("com.amap.api.location.AMapLocationClient", classLoader)
        if (clientClass != null) {
            HookUtils.hookAllMethods(clientClass, "setLocationListener") { chain ->
                val l = chain.args.firstOrNull()
                if (l != null) hookListenerClass(l.javaClass, "onLocationChanged") { args ->
                    val c = gcjCoords()
                    val arg = args.firstOrNull()
                    if (c != null && arg != null) {
                        invokeQuietly(arg, "setLatitude", c.lat)
                        invokeQuietly(arg, "setLongitude", c.lon)
                        HookUtils.setFieldQuietly(arg, "mLatitude", c.lat)
                        HookUtils.setFieldQuietly(arg, "mLongitude", c.lon)
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    // ---- Baidu (BD-09) ----
    private fun bdCoords(): Coords? = activeWgs84()?.let {
        val b = CoordinateTransform.wgs84ToBd09(it)
        Coords(b.lat, b.lon)
    }

    private fun hookBaidu(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("com.baidu.location.BDLocation", classLoader) ?: return

        HookUtils.hookAllMethods(locClass, "getLatitude") { chain ->
            val c = bdCoords()
            if (c != null) c.lat else chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(locClass, "getLongitude") { chain ->
            val c = bdCoords()
            if (c != null) c.lon else chain.proceed(chain.args.toTypedArray())
        }

        val clientClass = HookUtils.findClassIfExists("com.baidu.location.LocationClient", classLoader)
        if (clientClass != null) {
            HookUtils.hookAllMethods(clientClass, "registerLocationListener") { chain ->
                val l = chain.args.firstOrNull()
                if (l != null) hookListenerClass(l.javaClass, "onReceiveLocation") { args ->
                    val c = bdCoords()
                    val arg = args.firstOrNull()
                    if (c != null && arg != null) {
                        invokeQuietly(arg, "setLatitude", c.lat)
                        invokeQuietly(arg, "setLongitude", c.lon)
                        HookUtils.setFieldQuietly(arg, "latitude", c.lat)
                        HookUtils.setFieldQuietly(arg, "longitude", c.lon)
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    /** Hook a concrete listener class's single callback method once. */
    private fun hookListenerClass(clazz: Class<*>, method: String, patch: (List<Any?>) -> Unit) {
        if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
            HookUtils.hookAllMethods(clazz, method) { chain ->
                if (PacerRuntimeConfig.snapshot().active) patch(chain.args)
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    private fun invokeQuietly(obj: Any, name: String, vararg args: Any?) {
        try {
            HookUtils.callMethod(obj, name, *args)
        } catch (_: Throwable) {
        }
    }
}
