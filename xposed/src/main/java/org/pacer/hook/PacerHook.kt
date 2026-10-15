// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.pacer.hook.hooks.AntiDetectionHooker
import org.pacer.hook.hooks.LocationInjector
import org.pacer.hook.hooks.SensorStepHooker
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

/**
 * LSPosed entry point. Installs the anti-detection and sensor-simulation hooks
 * into the scoped processes. All hooks no-op unless [PacerRuntimeConfig.active]
 * is set, so an idle module never disturbs the device.
 */
class PacerHook : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookUtils.bind(this)
        PacerLog.i("module loaded in process")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        HookUtils.runCatchingLogged("location-injection") {
            LocationInjector.install(classLoader)
        }
        HookUtils.runCatchingLogged("anti-detection") {
            AntiDetectionHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("sensor-steps") {
            SensorStepHooker.install(classLoader)
        }
    }
}
