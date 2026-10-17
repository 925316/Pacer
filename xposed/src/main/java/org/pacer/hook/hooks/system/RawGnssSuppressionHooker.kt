// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Refuses raw GNSS callback registrations that would leak real pseudoranges,
// navigation messages or antenna state to a client.
//
// BOTH registration families are covered, because the names really did change.
// AOSP shipped registerGnssMeasurementsCallback / registerGnssNavigationMessageCallback
// / registerGnssAntennaInfoCallback / registerGnssBatchingCallback, deprecated
// them in favour of addGnssMeasurementsListener / addGnssNavigationMessageListener
// / addGnssAntennaInfoListener / addGnssBatchingCallback, and some firmware has
// since dropped the old names outright - a ColorOS 16 / API 36 report has
// registerGnssMeasurementsCallback and registerGnssNavigationMessageCallback
// removed from the platform, which silently disabled suppression for a module
// that only hooked the old pair. Matching by NAME and skipping whatever a ROM
// does not have is the portable answer; matching by parameter shape is not,
// because the two families do not share an argument layout (the add* ones lead
// with a request object, the register* ones do not).
//
// GNSS status registrations are deliberately NOT refused: GnssStatusHooker answers
// those with a sanitised status, and refusing them would leave the app with no
// satellite view at all.
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
        // Current naming.
        "addGnssMeasurementsListener",
        "addGnssNavigationMessageListener",
        "addGnssAntennaInfoListener",
        "addGnssBatchingCallback",
        "startGnssBatch",
        // Deprecated naming: the only spelling on older releases, and still the
        // one a few ROMs kept while others removed it.
        "registerGnssMeasurementsCallback",
        "registerGnssNavigationMessageCallback",
        "registerGnssAntennaInfoCallback",
        "registerGnssBatchingCallback",
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
