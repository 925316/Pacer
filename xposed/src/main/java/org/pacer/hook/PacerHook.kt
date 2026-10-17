package org.pacer.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.pacer.hook.config.ConfigSync
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.AntiDetectionHooker
import org.pacer.hook.hooks.GnssStatusHooker
import org.pacer.hook.hooks.GmsFusedHooker
import org.pacer.hook.hooks.LocationInjector
import org.pacer.hook.hooks.MapSdkHooker
import org.pacer.hook.hooks.NmeaHooker
import org.pacer.hook.hooks.RouteReplayHooker
import org.pacer.hook.hooks.SensorStepHooker
import org.pacer.hook.hooks.env.BleSpoofHooker
import org.pacer.hook.hooks.env.CellSpoofHooker
import org.pacer.hook.hooks.env.ConnectivitySpoofHooker
import org.pacer.hook.hooks.env.WifiSpoofHooker
import org.pacer.hook.hooks.system.RawGnssSuppressionHooker
import org.pacer.hook.hooks.system.SystemAppOpsHooker
import org.pacer.hook.hooks.system.SystemLocationServiceHooker
import org.pacer.hook.hooks.system.SystemSettingsProviderHooker
import org.pacer.hook.hooks.system.vendor.ProcessIdentity
import org.pacer.hook.hooks.system.vendor.VendorRegistry
import org.pacer.hook.utils.HookStatus
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
        ConfigSync.install(this)
        PacerLog.i("module loaded in process")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        HookUtils.runCatchingLogged("location-injection") {
            LocationInjector.install(classLoader)
        }
        HookUtils.runCatchingLogged("route-replay") {
            RouteReplayHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("gnss-status") {
            GnssStatusHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("map-sdks") {
            MapSdkHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("gms-fused") {
            GmsFusedHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("nmea") {
            NmeaHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("anti-detection") {
            AntiDetectionHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("sensor-steps") {
            SensorStepHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("env-wifi") {
            WifiSpoofHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("env-connectivity") {
            ConnectivitySpoofHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("env-cell") {
            CellSpoofHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("env-ble") {
            BleSpoofHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("raw-gnss") {
            RawGnssSuppressionHooker.install(classLoader)
        }
        logInstallReport()
    }

    /**
     * system_server side layer. In system_server this callback replaces the
     * first onPackageLoaded phase, so the global hooks are installed here. The
     * hooks only act while a simulation is active.
     */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val classLoader = param.classLoader
        HookUtils.runCatchingLogged("system-location") {
            SystemLocationServiceHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("system-appops") {
            SystemAppOpsHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("system-settings") {
            SystemSettingsProviderHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("system-rawgnss") {
            RawGnssSuppressionHooker.install(classLoader)
        }
        logInstallReport()
    }

    /**
     * One summary line per process, so a device test can be read straight out
     * of logcat: which installers attached how many hooks, which failed, and
     * whether the config channel actually delivered a simulation.
     */
    private fun logInstallReport() {
        val cfg = PacerRuntimeConfig.snapshot()
        val vendor = VendorRegistry.active
        PacerLog.i(
            "pacer process=${ProcessIdentity.current()} vendor=${vendor.id} " +
                "family=${VendorRegistry.profile.family} ui=${VendorRegistry.profile.majorVersion}",
        )
        PacerLog.i(
            "pacer config: active=${cfg.active} speed=${cfg.speedMps} " +
                "at=(${cfg.latitude},${cfg.longitude}) route=${cfg.routeJson.isNotEmpty()} " +
                "steps=${cfg.enableStepSimulation}",
        )
        PacerLog.i(HookStatus.summary())
    }
}
