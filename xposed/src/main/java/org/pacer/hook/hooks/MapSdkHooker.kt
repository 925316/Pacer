package org.pacer.hook.hooks

import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.CoordinatePolicy
import org.pacer.motion.CoordinateSystem
import org.pacer.motion.CoordinateTransform
import org.pacer.motion.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object MapSdkHooker {

    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    /**
     * Address-like getters. While a simulation is active they answer with an empty
     * string: the real SDK object's address describes the device's actual position,
     * which would contradict the injected coordinates more loudly than no address.
     */
    private val amapTextGetters = listOf(
        "getAddress", "getCountry", "getProvince", "getCity", "getDistrict",
        "getStreet", "getStreetNum", "getPoiName", "getAoiName", "getBuildingId",
        "getFloor", "getDescription",
    )

    private val baiduTextGetters = listOf(
        "getAddrStr", "getProvince", "getCity", "getDistrict", "getStreet",
        "getStreetNumber", "getLocationDescribe",
    )

    private const val BAIDU_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    private class Coords(val lat: Double, val lon: Double)

    /**
     * The non-coordinate fields a location object carries.
     *
     * Read from the same sources the android.location injection uses: the route
     * sample when a route is running, otherwise the configured speed/plan. An SDK
     * object that says "you are here" while still carrying the real device's speed,
     * altitude and address is the same disagreement in a different place.
     */
    private class Fields(
        val speedMps: Double,
        val accuracyMeters: Float,
        val altitudeMeters: Double,
        val bearingDeg: Float,
        val timeMs: Long,
    )

    private fun currentFields(): Fields? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return null
        val now = System.currentTimeMillis()
        val sample = RouteEngine.sampleAt(now)
        return if (sample != null) {
            Fields(sample.speedMps, sample.accuracyMeters, sample.altitudeMeters, sample.bearingDeg, now)
        } else {
            Fields(
                speedMps = CurrentFix.linearSpeedAt(cfg, now),
                accuracyMeters = cfg.accuracyMeters,
                altitudeMeters = cfg.altitudeMeters,
                bearingDeg = cfg.bearingDeg,
                timeMs = now,
            )
        }
    }

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
        // GCJ-02 is this SDK's default; an explicit per-app override wins over it.
        val system = PacerRuntimeConfig.snapshot().datumFor(CoordinateSystem.GCJ02)
        val g = CoordinatePolicy.convert(it, system)
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

        hookAmapFields(locClass)

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

    /** AMap's non-coordinate fields; its getTime is milliseconds. */
    private fun hookAmapFields(locClass: Class<*>) {
        hookNumeric(locClass, "getSpeed") { it.speedMps }
        hookNumeric(locClass, "getAccuracy") { it.accuracyMeters.toDouble() }
        hookNumeric(locClass, "getAltitude") { it.altitudeMeters }
        hookNumeric(locClass, "getBearing") { it.bearingDeg.toDouble() }
        hookNumeric(locClass, "getTime") { it.timeMs.toDouble() }
        blankStrings(locClass, amapTextGetters)
    }

    private fun hookQualityReport(classLoader: ClassLoader) {        val reportClass =
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
        // The SDK reads its own requested datum per object, which is more specific than
        // the package-level override: a client that explicitly asked for WGS-84 must get
        // WGS-84 even if the package override says BD-09. Only the fall-through case (no
        // recognisable coorType) consults the override, with BD-09 as the SDK default.
        val kind = coorType?.trim()?.lowercase(Locale.US) ?: ""
        val p = when (kind) {
            "gcj02", "gcj02ll", "gcj" -> CoordinateTransform.wgs84ToGcj02(wgs)
            "wgs84", "wgs" -> wgs
            "bd09mc" -> {
                PacerLog.i("baidu bd09mc requested; injecting BD-09 (Mercator conversion unsupported)")
                CoordinatePolicy.convert(wgs, CoordinateSystem.BD09)
            }
            else -> CoordinatePolicy.convert(
                wgs,
                PacerRuntimeConfig.snapshot().datumFor(CoordinateSystem.BD09),
            )
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

        hookBaiduFields(locClass)

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

    /** Baidu's non-coordinate fields; its getTime is a formatted local string. */
    private fun hookBaiduFields(locClass: Class<*>) {
        hookNumeric(locClass, "getSpeed") { it.speedMps }
        hookNumeric(locClass, "getRadius") { it.accuracyMeters.toDouble() }
        hookNumeric(locClass, "getAltitude") { it.altitudeMeters }
        hookNumeric(locClass, "getDirection") { it.bearingDeg.toDouble() }
        HookUtils.hookAllMethods(locClass, "getTime") { chain ->
            val fields = currentFields()
            if (fields == null) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            SimpleDateFormat(BAIDU_TIME_PATTERN, Locale.US).format(Date(fields.timeMs))
        }
        blankStrings(locClass, baiduTextGetters)
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

    /** Hooks a numeric getter and answers with the simulated field while a run is active. */
    private fun hookNumeric(clazz: Class<*>, name: String, value: (Fields) -> Double) {
        HookUtils.hookAllMethods(clazz, name) { chain ->
            val fields = currentFields()
            if (fields == null) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            typedNumber(chain, value(fields)) ?: chain.proceed(chain.args.toTypedArray())
        }
    }

    /** Hooks string getters and withholds them while a run is active. */
    private fun blankStrings(clazz: Class<*>, names: List<String>) {
        for (name in names) {
            HookUtils.hookAllMethods(clazz, name) { chain ->
                if (PacerRuntimeConfig.snapshot().active) {
                    ""
                } else {
                    chain.proceed(chain.args.toTypedArray())
                }
            }
        }
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
