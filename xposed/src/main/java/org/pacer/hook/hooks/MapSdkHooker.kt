// Map-SDK location hooks (AMap / Baidu / Tencent). Those SDKs bypass
// android.location entirely and keep their own Location types, so we intercept
// their getters and listener callbacks and feed each one the coordinate system
// it actually expects.
//
// Coordinate systems are resolved at runtime rather than assumed:
// - AMap expects GCJ-02 (AMapLocation.COORD_TYPE_GCJ02).
// - Tencent expects GCJ-02 by default (TencentLocationManager
//   COORDINATE_TYPE_GCJ02 == 1).
// - Baidu is the trap: LocationClientOption's default coorType is "gcj02" in
//   current releases, not BD-09, so we read BDLocation.getCoorType() per
//   instance. Feeding BD-09 to a client using the default would double-shift
//   the position by a few hundred metres.
//
// The SDKs are obfuscated: AMapLocation stores lat/lon in `q`/`r` and
// BDLocation in `c`/`d`, none of which are stable across releases. We therefore
// only use the public getLatitude/setLatitude accessors and never guess field
// names.
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

    private class Coords(val lat: Double, val lon: Double, val loader: ClassLoader?)

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

    /**
     * SDK listeners that have been registered, so they can be fed on a timer.
     *
     * An SDK that never produces a fix - no GNSS, no network location, which is the
     * normal state of a spoofing target - will never call a listener, so a layer that
     * only rewrites the argument of a callback that does not happen has no effect at
     * all. This set is what the timer drives instead.
     */
    private val locationSinks = java.util.concurrent.CopyOnWriteArrayList<Any>()

    @Volatile private var pumpStarted = false

    /**
     * Builds an AMapLocation carrying the current fix.
     *
     * Constructed reflectively inside the caller's loader because the class belongs to
     * the app, not to the module: resolving it against the module's loader would produce
     * an object the SDK cannot recognise.
     */
    private fun buildAmapLocation(coords: Coords): Any? = try {
        val loader = coords.loader ?: return null
        val clazz = Class.forName("com.amap.api.location.AMapLocation", false, loader)
        val loc = clazz.getConstructor(String::class.java).newInstance("gps")
        invokeQuietly(loc, "setLatitude", coords.lat)
        invokeQuietly(loc, "setLongitude", coords.lon)
        val cfg = PacerRuntimeConfig.snapshot()
        val fix = CurrentFix.of(cfg)
        val fields = currentFields()
        if (fields != null) {
            invokeQuietly(loc, "setAccuracy", fields.accuracyMeters)
            invokeQuietly(loc, "setSpeed", fields.speedMps.toFloat())
            invokeQuietly(loc, "setBearing", fields.bearingDeg)
            invokeQuietly(loc, "setAltitude", fields.altitudeMeters)
        } else {
            invokeQuietly(loc, "setAccuracy", cfg.accuracyMeters)
            invokeQuietly(loc, "setAltitude", cfg.altitudeMeters)
        }
        invokeQuietly(loc, "setTime", System.currentTimeMillis())
        // A GPS fix with no satellites is rejected by AMap's own quality check, so the
        // count has to be present and plausible, not merely absent-and-ignored.
        val sky = LastSky.current()
        invokeQuietly(loc, "setSatellites", sky.inView)
        invokeQuietly(loc, "setGpsAccuracyStatus", 1)
        invokeQuietly(loc, "setLocationType", 1)
        loc
    } catch (_: Throwable) {
        null
    }

    /** One tick per second, matching the platform's fastest ordinary fix rate. */
    private fun startPump() {
        if (pumpStarted) return
        synchronized(this) {
            if (pumpStarted) return
            pumpStarted = true
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    pushSdkTick()
                } catch (_: Throwable) {
                } finally {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(tick)
    }

    private fun pushSdkTick() {
        if (locationSinks.isEmpty()) return
        val coords = gcjCoords() ?: return
        val loc = buildAmapLocation(coords) ?: return
        for (sink in locationSinks.toList()) {
            try {
                invokeQuietly(sink, "onLocationChanged", loc)
            } catch (_: Throwable) {
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        // Which loaders have already been hooked. The framework's loader does NOT see
        // every SDK: AMap's location classes and Baidu's secondary dexes live in the
        // app's own loader, which only exists once Application.attachBaseContext has
        // run. PacerHook re-invokes this from that callback, and without this guard the
        // second call would stack a second interceptor on every AMapLocation getter.
        //
        // Measured cost of not having the retry at all: `map-sdks=0` in AMap's process
        // and no effect in its map, which is exactly the symptom this fixes.
        if (!installedLoaders.add(classLoader)) return
        hookAmap(classLoader)
        hookBaidu(classLoader)
        TencentLocationHooker.install(classLoader)
        PacerLog.i("map sdk hooks installed")
    }

    /** Loaders already hooked; identity comparison on purpose, a loader is a single object. */
    private val installedLoaders =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<ClassLoader, Boolean>())

    // ---- AMap (GCJ-02) ----

    private fun gcjCoords(): Coords? = activeWgs84()?.let {
        // GCJ-02 is this SDK's default; an explicit per-app override wins over it.
        val system = PacerRuntimeConfig.snapshot().datumFor(CoordinateSystem.GCJ02)
        val g = CoordinatePolicy.convert(it, system)
        Coords(g.lat, g.lon, amapLoader)
    }

    /** The loader AMapLocation was found in; see buildAmapLocation. */
    @Volatile private var amapLoader: ClassLoader? = null

    /** The loader BDLocation was found in, for the same reason. */
    @Volatile private var baiduLoader: ClassLoader? = null

    private fun hookAmap(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("com.amap.api.location.AMapLocation", classLoader) ?: return
        amapLoader = classLoader

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
                    // Capture AND patch. Patching the argument covers the case where the
                    // SDK delivers a fix on its own; capturing covers the case where it
                    // never does, which is the normal one on a device with no GNSS fix -
                    // and that is the case where a patch-only layer looks installed and
                    // changes nothing on the map. Capture is guarded by the excluded-type
                    // rule so a Baidu listener registered on an AMap client is not pushed
                    // an AMapLocation it cannot read.
                    locationSinks.addIfAbsent(l)
                    startPump()
                    hookListenerClass(l.javaClass, "onLocationChanged") { args ->
                        val arg = args.firstOrNull() ?: return@hookListenerClass
                        val c = gcjCoords() ?: return@hookListenerClass
                        invokeQuietly(arg, "setLatitude", c.lat)
                        invokeQuietly(arg, "setLongitude", c.lon)
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }

            HookUtils.hookAllMethods(clientClass, "unRegisterLocationListener") { chain ->
                val l = chain.args.firstOrNull()
                if (l != null) locationSinks.removeAll { it === l }
                chain.proceed(chain.args.toTypedArray())
            }

            // getLastKnownLocation must answer with the simulated fix even before the
            // client has ever started; otherwise an app that reads a cached fix first
            // shows the device's own position and only corrects itself a second later.
            HookUtils.hookAllMethods(clientClass, "getLastKnownLocation") { chain ->
                val c = gcjCoords()
                if (c != null) buildAmapLocation(c) else chain.proceed(chain.args.toTypedArray())
            }
        }

        // Quality report: the SDK flags "a high-danger mock app is installed".
        hookQualityReport(classLoader)
        hookMockTolerance(classLoader)
    }

    /**
     * Makes the SDK accept the fix even when its own check calls it mocked.
     *
     * `AMapLocationClientOption.isMockEnable()` is the single switch the SDK consults
     * before discarding a fix it considers spoofed: with it false, the first four such
     * fixes are dropped silently and every later one is returned with error 15 and all
     * fields zeroed. The app sets it false on all four of its main location paths, and
     * in the Hight_Accuracy mode it uses, an error-15 result is not delivered to the app
     * at all - so the user sees "location never arrives" rather than an error.
     *
     * Answering true cannot be observed as a lie: the getter is only read by the SDK's
     * own internal check, which is exactly the branch that must not discard the fix. The
     * app never displays it, and its own logic reads only the resulting AMapLocation.
     *
     * This is the one place where beating the check is the correct answer rather than
     * satisfying it: the checks that CAN be satisfied honestly (mock flag, satellite
     * count, the all-zero triple) are satisfied at the source by LocationInjector, and
     * this covers the case where the SDK's GNSS cross-check still disagrees.
     */
    private fun hookMockTolerance(classLoader: ClassLoader) {
        val optionClass =
            HookUtils.findClassIfExists("com.amap.api.location.AMapLocationClientOption", classLoader)
                ?: return
        HookUtils.hookAllMethods(optionClass, "isMockEnable") { chain ->
            if (PacerRuntimeConfig.snapshot().active) true
            else chain.proceed(chain.args.toTypedArray())
        }
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
        Coords(p.lat, p.lon, baiduLoader)
    }

    private fun coorTypeOf(obj: Any?): String? =
        try {
            if (obj == null) null else HookUtils.callMethod(obj, "getCoorType") as? String
        } catch (_: Throwable) {
            null
        }

    private fun hookBaidu(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("com.baidu.location.BDLocation", classLoader) ?: return
        baiduLoader = classLoader

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
