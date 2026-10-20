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
import org.pacer.hook.hooks.system.SystemBluetoothServiceHooker
import org.pacer.hook.hooks.system.SystemConnectivityServiceHooker
import org.pacer.hook.hooks.system.SystemLocationServiceHooker
import org.pacer.hook.hooks.system.SystemSettingsProviderHooker
import org.pacer.hook.hooks.system.SystemTelephonyRegistryHooker
import org.pacer.hook.hooks.system.SystemTelephonyServiceHooker
import org.pacer.hook.hooks.system.SystemWifiServiceHooker
import org.pacer.hook.hooks.system.vendor.ProcessIdentity
import org.pacer.hook.hooks.system.vendor.SystemProcess
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

    /** One layer per process; both package callbacks race to set this. */
    private val layerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookUtils.bind(this)
        ConfigSync.install(this)
        PacerLog.i("module loaded in process")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        installLayer(classLoaderQuietly(param))
    }

    /**
     * The package whose class loader is ready.
     *
     * Both callbacks are kept, and the guard in [installLayer] makes whichever
     * arrives first win. That matters because the interface documents
     * onPackageLoaded's class loader accessor as API 29+: on Android 8 and 9 it
     * is not there, and relying on onPackageLoaded alone would leave those
     * releases with no hooks at all while everything still looked fine.
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        installLayer(param.classLoader)
    }

    private fun classLoaderQuietly(param: XposedModuleInterface.PackageLoadedParam): ClassLoader? =
        try {
            param.defaultClassLoader
        } catch (_: Throwable) {
            null
        }

    /**
     * Installs the layer that matches THIS process, once.
     *
     * The process matters because a module declaring system processes in its
     * scope is injected into them too, and running the app-process environment
     * hooks inside the telephony or Bluetooth process is a known cause of system
     * instability. Those two get only their own layer, system server has its own
     * callback, and everything else is an ordinary app.
     */
    private fun installLayer(classLoader: ClassLoader?) {
        if (classLoader == null || !layerInstalled.compareAndSet(false, true)) return
        when (ProcessIdentity.current()) {
            SystemProcess.PHONE -> HookUtils.runCatchingLogged("phone-cells") {
                SystemTelephonyServiceHooker.install(classLoader)
            }

            SystemProcess.BLUETOOTH ->
                // Only the scan-service layer. Running the app-process environment
                // hooks in this process is a known source of system instability.
                HookUtils.runCatchingLogged("bluetooth-scan") {
                    SystemBluetoothServiceHooker.install(classLoader)
                }

            SystemProcess.SETTINGS ->
                // The provider that answers Settings.Secure reads for every other app.
                HookUtils.runCatchingLogged("settings-provider") {
                    SystemSettingsProviderHooker.install(classLoader)
                }

            SystemProcess.SYSTEM_SERVER ->
                PacerLog.i("system server layer comes from onSystemServerStarting")

            SystemProcess.UNKNOWN -> installAppLayer(classLoader)
        }
        logInstallReport()
    }

    private fun installAppLayer(classLoader: ClassLoader) {
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
        hookRealApplicationLoader(classLoader)
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
    }

    /**
     * Re-hooks the map SDKs against the application's OWN class loader.
     *
     * The loader the framework hands to onPackageReady does not see every SDK class.
     * AMap keeps its location classes behind the app's own loader, and Baidu ships
     * extra dex files it loads later, so a single install at the framework's callback
     * finds nothing: measured as `map-sdks=0` in AMap's process, with the consequence
     * that Pacer looked installed and changed nothing on the map.
     *
     * Both callbacks are hooked because neither is guaranteed on every release:
     * attachBaseContext fires before the app's own loader is fully populated on some
     * builds, and onCreate fires after the SDK singleton may already have been built
     * on others. MapSdkHooker.install is idempotent per loader, so running twice is
     * free; running zero times is the failure being fixed.
     */
    private fun hookRealApplicationLoader(classLoader: ClassLoader) {
        for (method in listOf("attachBaseContext", "onCreate")) {
            HookUtils.runCatchingLogged("app-loader/$method") {
                val app = HookUtils.findClassIfExists("android.app.Application", classLoader)
                    ?: return@runCatchingLogged
                HookUtils.hookAllMethods(app, method) { chain ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    try {
                        val loader = (chain.thisObject as? android.content.Context)?.classLoader
                        if (loader != null) MapSdkHooker.install(loader)
                    } catch (_: Throwable) {
                    }
                    result
                }
            }
        }
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
        HookUtils.runCatchingLogged("system-rawgnss") {
            RawGnssSuppressionHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("system-wifi") {
            SystemWifiServiceHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("system-telephony-push") {
            SystemTelephonyRegistryHooker.install(classLoader)
        }
        HookUtils.runCatchingLogged("system-connectivity") {
            SystemConnectivityServiceHooker.install(classLoader)
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
        val profile = VendorRegistry.profile
        PacerLog.i(
            "pacer process=${ProcessIdentity.current()} vendor=${vendor.id} " +
                "family=${profile.family} ui=${profile.majorVersion}",
        )
        // The ROM properties the adapter matched on. Without them a device report says only
        // "the wrong adapter was chosen" and the cause has to be guessed; with them the
        // value that misled RomRules is on the same page as the result.
        if (profile.properties.isNotEmpty()) {
            PacerLog.i(
                "pacer device: " + profile.properties.entries.joinToString(" ") { "${it.key}=${it.value}" },
            )
        }
        PacerLog.i(
            "pacer config: active=${cfg.active} speed=${cfg.speedMps} " +
                "at=(${cfg.latitude},${cfg.longitude}) route=${cfg.routeJson.isNotEmpty()} " +
                "steps=${cfg.enableStepSimulation}",
        )
        PacerLog.i(HookStatus.summary())
    }
}
