package org.pacer.hook.hooks

import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.CoordinateTransform
import org.pacer.motion.GeoPoint
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object MapSdkHooker {

    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    private class Coords(val lat: Double, val lon: Double)

    /** Active target coords in WGS84, or null when idle. */
    private fun activeWgs84(): GeoPoint? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        // The CURRENT position, not the configured start: a location SDK that answers
        // with the origin while android.location walks the route is the most visible
        // disagreement there is, and these SDKs are the primary path for many apps.
        return CurrentFix.of(cfg)
    }

    fun install(classLoader: ClassLoader) {
        hookAmap(classLoader)
        hookBaidu(classLoader)
        TencentLocationHooker.install(classLoader)
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
        // Anti-cheat entry: AMapLocation.isMock() backs onto a private field and
        // has a public setMock(boolean), so hide it both ways.
        HookUtils.hookAllMethods(locClass, "isMock") { chain ->
            if (PacerRuntimeConfig.snapshot().active) false
            else chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(locClass, "setMock") { chain ->
            if (PacerRuntimeConfig.snapshot().active) {
                chain.proceed(forceBooleanFalse(chain.args))
            } else {
                chain.proceed(chain.args.toTypedArray())
            }
        }

        // Listener callbacks: patch the AMapLocation argument in place. Its
        // setLatitude/setLongitude are public, so the object the app holds is
        // corrected too.
        val clientClass = HookUtils.findClassIfExists("com.amap.api.location.AMapLocationClient", classLoader)
        if (clientClass != null) {
            HookUtils.hookAllMethods(clientClass, "setLocationListener") { chain ->
                val l = chain.args.firstOrNull()
                if (l != null) {
                    hookListenerClass(l.javaClass, "onLocationChanged") { args ->
                        val arg = args.firstOrNull() ?: return@hookListenerClass
                        val c = gcjCoords() ?: return@hookListenerClass
                        invokeQuietly(arg, "setLatitude", c.lat)
                        invokeQuietly(arg, "setLongitude", c.lon)
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }

        // Quality report: the SDK flags "a high-danger mock app is installed".
        hookQualityReport(classLoader)
    }

    private fun hookQualityReport(classLoader: ClassLoader) {
        val reportClass =
            HookUtils.findClassIfExists("com.amap.api.location.AMapLocationQualityReport", classLoader) ?: return
        HookUtils.hookAllMethods(reportClass, "isInstalledHighDangerMockApp") { chain ->
            if (PacerRuntimeConfig.snapshot().active) false
            else chain.proceed(chain.args.toTypedArray())
        }
    }

    // ---- Baidu (coordinate system resolved at runtime) ----

    /**
     * Resolves which coordinate system the client asked for. LocationClientOption
     * defaults to "gcj02" in current releases; older builds and explicit
     * setCoorType("bd09ll") use BD-09. "bd09mc" (Mercator metres) is not
     * converted and falls back to BD-09 with a log line.
     */
    private fun baiduCoords(coorType: String?): Coords? = activeWgs84()?.let { wgs ->
        val kind = coorType?.trim()?.lowercase(Locale.US) ?: ""
        val p = when (kind) {
            "gcj02", "gcj02ll", "gcj" -> CoordinateTransform.wgs84ToGcj02(wgs)
            "wgs84", "wgs" -> wgs
            "bd09mc" -> {
                PacerLog.i("baidu bd09mc requested; injecting BD-09 (Mercator conversion unsupported)")
                CoordinateTransform.wgs84ToBd09(wgs)
            }
            else -> CoordinateTransform.wgs84ToBd09(wgs)
        }
        Coords(p.lat, p.lon)
    }

    private fun coorTypeOf(obj: Any?): String? =
        try {
            if (obj == null) null else HookUtils.callMethod(obj, "getCoorType") as? String
        } catch (_: Throwable) {
            null
        }

    private fun hookBaidu(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("com.baidu.location.BDLocation", classLoader) ?: return

        HookUtils.hookAllMethods(locClass, "getLatitude") { chain ->
            val c = baiduCoords(coorTypeOf(chain.thisObject))
            if (c != null) c.lat else chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(locClass, "getLongitude") { chain ->
            val c = baiduCoords(coorTypeOf(chain.thisObject))
            if (c != null) c.lon else chain.proceed(chain.args.toTypedArray())
        }

        // Anti-cheat entries present in current releases. getMockGpsProbability
        // and getMockGpsStrategy are ints, getDisToRealLocation is a double, so
        // adapt to the real return type instead of assuming.
        for (name in listOf("getMockGpsProbability", "getMockGpsStrategy")) {
            HookUtils.hookAllMethods(locClass, name) { chain ->
                if (PacerRuntimeConfig.snapshot().active) {
                    typedNumber(chain, 0.0) ?: chain.proceed(chain.args.toTypedArray())
                } else {
                    chain.proceed(chain.args.toTypedArray())
                }
            }
        }
        HookUtils.hookAllMethods(locClass, "getDisToRealLocation") { chain ->
            if (PacerRuntimeConfig.snapshot().active) {
                typedNumber(chain, 0.0) ?: chain.proceed(chain.args.toTypedArray())
            } else {
                chain.proceed(chain.args.toTypedArray())
            }
        }

        val clientClass = HookUtils.findClassIfExists("com.baidu.location.LocationClient", classLoader)
        if (clientClass != null) {
            HookUtils.hookAllMethods(clientClass, "registerLocationListener") { chain ->
                val l = chain.args.firstOrNull()
                if (l != null) {
                    hookListenerClass(l.javaClass, "onReceiveLocation") { args ->
                        val arg = args.firstOrNull() ?: return@hookListenerClass
                        val c = baiduCoords(coorTypeOf(arg)) ?: return@hookListenerClass
                        invokeQuietly(arg, "setLatitude", c.lat)
                        invokeQuietly(arg, "setLongitude", c.lon)
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    // ---- helpers ----

    /** Hook a concrete listener class's callback method once. */
    private fun hookListenerClass(clazz: Class<*>, method: String, patch: (List<Any?>) -> Unit) {
        if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
            HookUtils.hookAllMethods(clazz, method) { chain ->
                if (PacerRuntimeConfig.snapshot().active) patch(chain.args)
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    /** Rewrites the first boolean argument to false, leaving everything else. */
    private fun forceBooleanFalse(args: List<Any?>): Array<Any?> {
        val out = args.toTypedArray()
        val index = out.indexOfFirst { it is Boolean }
        if (index >= 0) out[index] = false
        return out
    }

    /** Boxes [value] into the hooked method's real numeric return type. */
    private fun typedNumber(chain: XposedInterface.Chain, value: Double): Any? =
        when ((chain.executable as? java.lang.reflect.Method)?.returnType) {
            java.lang.Double.TYPE -> value
            java.lang.Float.TYPE -> value.toFloat()
            java.lang.Long.TYPE -> value.toLong()
            java.lang.Integer.TYPE -> value.toInt()
            else -> null
        }

    private fun invokeQuietly(obj: Any, name: String, vararg args: Any?) {
        try {
            HookUtils.callMethod(obj, name, *args)
        } catch (_: Throwable) {
        }
    }
}
