// system_server side of the WiFi environment. The app-process WifiManager hooks
// only cover an app that talks to WifiManager; a modernized app or a direct
// binder client can call the WifiService / WifiScanningService interfaces and
// read the real neighbourhood. This layer answers those calls from the same
// deterministic virtual environment so both views agree.
//
// THE TRAP: getScanResults does not return a List. It returns a ParceledListSlice,
// and inside the Wi-Fi APEX the class is renamed by jarjar (a real-device
// diagnosis recorded com.android.wifi.x.com.android.modules.utils.ParceledListSlice
// while android.content.pm.ParceledListSlice exists elsewhere). The slice class
// is therefore taken from the hooked method's OWN return type, with the two known
// spellings only as fallbacks. If no slice can be built the real value is returned
// unchanged: safety beats spoofing, because a malformed slice cannot be caught by CI.
package org.pacer.hook.hooks.system

import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.env.FakeRadioEnvironment
import org.pacer.hook.hooks.env.WifiFabricator
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.reflect.Method

object SystemWifiServiceHooker {

    /** Slice spellings tried after the hooked method's own return type. */
    private val sliceClassNames = listOf(
        "android.content.pm.ParceledListSlice",
        "com.android.wifi.x.com.android.modules.utils.ParceledListSlice",
    )

    fun install(classLoader: ClassLoader) {
        var hooked = 0
        SystemClassLocator.locate(SystemComponent.WIFI_SERVICE, classLoader)?.let { service ->
            hooked += hookScanResults(service, "getScanResults", classLoader)
            hooked += hookConnectionInfo(service, classLoader)
        }
        SystemClassLocator.locate(SystemComponent.WIFI_SCANNER_SERVICE, classLoader)?.let { scanner ->
            hooked += hookScanResults(scanner, "getSingleScanResults", classLoader)
        }
        PacerLog.i("system wifi hooks installed (targets=$hooked)")
    }

    // --- scan results --------------------------------------------------------

    private fun hookScanResults(clazz: Class<*>, methodName: String, classLoader: ClassLoader): Int =
        HookUtils.hookAllMethods(clazz, methodName) { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            val real = chain.proceed(chain.args.toTypedArray())
            val fake = try {
                // The real slice doubles as the constructor template.
                val aps = WifiFabricator.scanAps(cfg, System.currentTimeMillis())
                WifiFabricator.buildScanResults(
                    classLoader,
                    aps,
                    sliceList(real)?.firstOrNull(),
                    SystemClock.elapsedRealtimeNanos() / 1000L,
                )
            } catch (t: Throwable) {
                PacerLog.w("system-wifi-scan", t)
                null
            }
            if (fake.isNullOrEmpty()) return@hookAllMethods real
            wrapScanResults(chain, fake) ?: real
        }

    /**
     * Wraps the fabricated list in whatever the hooked method promises to return:
     * a plain ArrayList for an older List-returning build, otherwise a
     * ParceledListSlice. Null means nothing could be built and the caller keeps
     * the real value.
     */
    private fun wrapScanResults(chain: XposedInterface.Chain, list: List<Any>): Any? {
        val returnType = (chain.executable as? Method)?.returnType ?: return null
        if (java.util.List::class.java.isAssignableFrom(returnType)) return ArrayList(list)
        for (candidate in sliceCandidates(returnType)) {
            buildSlice(candidate, list)?.let { return it }
        }
        return null
    }

    /**
     * The method's own return type first: on the APEX it is already the
     * jarjar-renamed class, so it is the only spelling that is guaranteed to be
     * loadable by the same loader. The named spellings follow for a build whose
     * declared return type is an interface or an unrenamed base.
     */
    private fun sliceCandidates(returnType: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>(sliceClassNames.size + 1)
        out.add(returnType)
        for (name in sliceClassNames) {
            val loaded = loadClass(name, returnType.classLoader) ?: continue
            if (out.none { it.name == loaded.name }) out.add(loaded)
        }
        return out
    }

    private fun loadClass(name: String, loader: ClassLoader?): Class<*>? =
        HookUtils.findClassIfExists(name, loader) ?: HookUtils.findClassIfExists(name, null)

    /**
     * A one-argument constructor taking the List, or a no-arg constructor plus a
     * setList-style method. A constructed slice is read back through getList()
     * before it is accepted, so a wrong constructor cannot hand out a slice that
     * does not hold the fabricated list.
     */
    private fun buildSlice(clazz: Class<*>, list: List<Any>): Any? {
        for (ctor in clazz.declaredConstructors) {
            if (ctor.parameterCount != 1) continue
            val param = ctor.parameterTypes[0]
            if (param == Any::class.java || !param.isAssignableFrom(list.javaClass)) continue
            try {
                ctor.isAccessible = true
                val slice = ctor.newInstance(list)
                if (isExpectedSlice(slice, list.size)) return slice
            } catch (_: Throwable) {
            }
        }

        for (ctor in clazz.declaredConstructors) {
            if (ctor.parameterCount != 0) continue
            try {
                ctor.isAccessible = true
                val slice = ctor.newInstance()
                val setter = setListMethod(clazz) ?: continue
                setter.invoke(slice, list)
                if (isExpectedSlice(slice, list.size)) return slice
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun setListMethod(clazz: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (method in c.declaredMethods) {
                if (method.name != "setList" || method.parameterCount != 1) continue
                if (!method.parameterTypes[0].isAssignableFrom(ArrayList::class.java)) continue
                method.isAccessible = true
                return method
            }
            c = c.superclass
        }
        return null
    }

    /** A slice whose getList() is absent or still holds the fabricated list. */
    private fun isExpectedSlice(slice: Any, expectedSize: Int): Boolean {
        val read = sliceList(slice) ?: return true
        return read.size == expectedSize
    }

    /** The list inside a slice, or the value itself when it is already a List. */
    private fun sliceList(value: Any?): List<*>? {
        if (value is List<*>) return value
        if (value == null) return null
        return try {
            HookUtils.callMethod(value, "getList") as? List<*>
        } catch (_: Throwable) {
            null
        }
    }

    // --- connection info -----------------------------------------------------

    private fun hookConnectionInfo(clazz: Class<*>, classLoader: ClassLoader): Int =
        HookUtils.hookAllMethods(clazz, "getConnectionInfo") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            // WifiInfo has no usable constructor, so keep the real object and
            // rewrite it in place; if that fails the real value is returned.
            val real = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
            val ap = connectedAp(cfg) ?: return@hookAllMethods real
            try {
                WifiFabricator.mutateWifiInfo(real, ap, classLoader)
            } catch (t: Throwable) {
                PacerLog.w("system-wifi-info", t)
            }
            real
        }

    private fun connectedAp(cfg: PacerRuntimeConfig.Snapshot): FakeRadioEnvironment.WifiAp? =
        try {
            val fix = CurrentFix.of(cfg)
            FakeRadioEnvironment.connectedAp(
                fix.lat,
                fix.lon,
                EnvGeo.sessionSeed(cfg.startTimestamp),
                SystemClock.elapsedRealtime(),
            )
        } catch (t: Throwable) {
            PacerLog.w("system-wifi-env", t)
            null
        }
}
