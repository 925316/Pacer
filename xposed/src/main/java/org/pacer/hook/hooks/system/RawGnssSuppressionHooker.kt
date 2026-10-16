// Refuses raw GNSS callback registrations that would leak real pseudoranges,
// navigation messages or antenna state to a client. Matching is deliberately
// by method name only: the registration entry points keep their names across
// AOSP generations even when their parameter lists move, and a candidate class
// that does not exist on the ROM is skipped quietly.
package org.pacer.hook.hooks.system

import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object RawGnssSuppressionHooker {

    private val targetClasses = listOf(
        "com.android.server.location.gnss.GnssManagerService",
        "com.android.server.location.LocationManagerService",
        "android.location.LocationManager",
    )

    private val refusedMethods = arrayOf(
        "addGnssMeasurementsListener",
        "addGnssNavigationMessageListener",
        "addGnssAntennaInfoListener",
        "addGnssBatchingCallback",
        "startGnssBatch",
    )

    fun install(classLoader: ClassLoader) {
        var hooked = 0
        for (name in targetClasses) {
            val clazz = HookUtils.findClassIfExists(name, classLoader) ?: continue
            for (method in refusedMethods) {
                hooked += HookUtils.hookAllMethods(clazz, method) { chain -> refuse(chain) }
            }
        }
        PacerLog.i("raw gnss suppression hooks installed (targets=$hooked)")
    }

    /**
     * Skips the registration without invoking the original: a boolean
     * registration reports failure, a void one is simply never registered.
     * Anything with another return shape keeps the original contract instead
     * of risking a class-cast or a bogus handle.
     */
    private fun refuse(chain: XposedInterface.Chain): Any? {
        if (!PacerRuntimeConfig.snapshot().active) return chain.proceed(chain.args.toTypedArray())
        val returnType = (chain.executable as? java.lang.reflect.Method)?.returnType
        if (returnType === java.lang.Boolean.TYPE) return false
        if (returnType != null && returnType !== java.lang.Void.TYPE) {
            return chain.proceed(chain.args.toTypedArray())
        }
        return null
    }
}
