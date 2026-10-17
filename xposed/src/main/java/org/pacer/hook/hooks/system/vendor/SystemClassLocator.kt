// Finds the class that serves a system component.
//
// This is the load-bearing part of the system layer. Since Android 12 several of
// these services live in APEX modules (Wi-Fi, tethering/connectivity, Bluetooth,
// telephony) and are NOT reachable from the default class loader, while Android
// 14 additionally renamed ConnectivityService. A component that resolves to
// nothing does not crash anything - it makes every dependent hook a silent
// no-op - so every miss is reported through HookStatus and logged.
package org.pacer.hook.hooks.system.vendor

import dalvik.system.PathClassLoader
import org.pacer.hook.utils.HookStatus
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object SystemClassLocator {

    private val resolved = ConcurrentHashMap<SystemComponent, Class<*>>()

    /**
     * The class serving [component], or null.
     *
     * Per candidate, resolution tries in order: the caller's loader, the thread
     * context loader, the system loader, then loaders built over the APEX javalib
     * directories. The APEX layer is the reason this class exists rather than a
     * bare Class.forName.
     */
    fun locate(component: SystemComponent, classLoader: ClassLoader?): Class<*>? {
        resolved[component]?.let { return it }

        val candidates = VendorRegistry.classCandidates(component)
        for (name in candidates) {
            val found = tryLoad(name, classLoader)
                ?: tryLoad(name, Thread.currentThread().contextClassLoader)
                ?: tryLoad(name, ClassLoader.getSystemClassLoader())
                ?: tryLoadFromApex(name)
            if (found != null) {
                resolved[component] = found
                HookStatus.noteComponent(component.name, found.name)
                return found
            }
        }

        HookStatus.noteComponent(component.name, null)
        PacerLog.i("system component ${component.name}: none of ${candidates.size} candidate(s) resolved")
        return null
    }

    private fun tryLoad(name: String, loader: ClassLoader?): Class<*>? =
        try {
            Class.forName(name, false, loader)
        } catch (_: Throwable) {
            null
        }

    private fun tryLoadFromApex(name: String): Class<*>? {
        for (loader in apexLoaders) {
            val found = tryLoad(name, loader)
            if (found != null) return found
        }
        return null
    }

    /** One loader per APEX module that actually has javalib jars here. */
    private val apexLoaders: List<ClassLoader> by lazy { buildApexLoaders() }

    private val APEX_MODULES = listOf(
        "com.android.wifi",
        "com.android.tethering",
        "com.android.btservices",
        "com.android.bluetooth",
        "com.android.telephony",
    )

    private fun buildApexLoaders(): List<ClassLoader> {
        val loaders = ArrayList<ClassLoader>()
        for (module in APEX_MODULES) {
            val jars = File("/apex/$module/javalib").listFiles()
                ?.filter { it.name.endsWith(".jar") }
                ?: continue
            if (jars.isEmpty()) continue
            try {
                val path = jars.joinToString(":") { it.absolutePath }
                loaders.add(PathClassLoader(path, javaClass.classLoader))
            } catch (_: Throwable) {
                // A jar that cannot be opened costs one layer, never the lookup.
            }
        }
        if (loaders.isNotEmpty()) PacerLog.i("apex class loaders available: ${loaders.size}")
        return loaders
    }
}
