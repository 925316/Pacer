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
        // No-op; real hooks land milestone by milestone.
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName == "org.pacer.app") return
    }
}
