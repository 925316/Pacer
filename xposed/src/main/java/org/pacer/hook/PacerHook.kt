// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Minimal LSPosed entry point. Registers no hooks yet — the anti-detection and
 * sensor-injection hooks land here milestone by milestone (M4). Having the entry
 * class wired now proves the :xposed module builds as a valid LSPosed module.
 */
class PacerHook : XposedModule() {
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(tag = "Pacer", msg = "module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "org.pacer.app") return
    }
}
