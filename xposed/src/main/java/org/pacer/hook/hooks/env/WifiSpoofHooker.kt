// WiFi environment spoofing. Risk-control SDKs cross-check the visible AP list
// and the associated network against the reported position, so a moving fix
// surrounded by the real neighbourhood is a tell. When a simulation is active
// the scan list and every WifiInfo getter are replaced with the deterministic
// virtual environment from FakeRadioEnvironment.
//
// WifiInfo cannot be constructed off the phone stack (its setters are hidden and
// it has no usable public constructor), so the getters are hooked directly and
// the object returned by getConnectionInfo is additionally mutated in place.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object WifiSpoofHooker {

    fun install(classLoader: ClassLoader) {
        val wifiManager = HookUtils.findClassIfExists("android.net.wifi.WifiManager", classLoader) ?: return
        hookScanResults(wifiManager, classLoader)
        hookConnectionInfo(wifiManager, classLoader)
        hookConfiguredNetworks(wifiManager, classLoader)
        hookWifiInfoGetters(classLoader)
        PacerLog.i("wifi environment hooks installed")
    }

    // --- getScanResults ------------------------------------------------------

    private fun hookScanResults(wifiManager: Class<*>, classLoader: ClassLoader) {
        HookUtils.hookAllMethods(wifiManager, "getScanResults") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            val real = chain.proceed(chain.args.toTypedArray())
            // Keep the method contract: only a genuine List is replaced.
            if (real !is List<*>) return@hookAllMethods real
            val fake = try {
                WifiFabricator.buildScanResults(cfg, classLoader, real.firstOrNull(), System.currentTimeMillis())
            } catch (t: Throwable) {
                PacerLog.w("wifi-scan", t)
                null
            }
            if (fake.isNullOrEmpty()) return@hookAllMethods real
            fake
        }
    }

    // --- getConnectionInfo ---------------------------------------------------

    private fun hookConnectionInfo(wifiManager: Class<*>, classLoader: ClassLoader) {
        HookUtils.hookAllMethods(wifiManager, "getConnectionInfo") { chain ->
            val ap = connectedAp(PacerRuntimeConfig.snapshot())
                ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val info = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
            try {
                mutateWifiInfo(info, ap, classLoader)
            } catch (t: Throwable) {
                PacerLog.w("wifi-info", t)
            }
            info
        }
    }

    internal fun mutateWifiInfo(info: Any, ap: FakeRadioEnvironment.WifiAp, classLoader: ClassLoader) {
        WifiFabricator.mutateWifiInfo(info, ap, classLoader)
    }

    // --- getConfiguredNetworks -----------------------------------------------

    private fun hookConfiguredNetworks(wifiManager: Class<*>, classLoader: ClassLoader) {
        HookUtils.hookAllMethods(wifiManager, "getConfiguredNetworks") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            val real = chain.proceed(chain.args.toTypedArray())
            if (real !is List<*>) return@hookAllMethods real
            val fake = try {
                buildWifiConfigurations(cfg, classLoader, real.firstOrNull())
            } catch (t: Throwable) {
                PacerLog.w("wifi-configured", t)
                null
            }
            if (fake.isNullOrEmpty()) return@hookAllMethods real
            fake
        }
    }

    private fun buildWifiConfigurations(
        cfg: PacerRuntimeConfig.Snapshot,
        classLoader: ClassLoader,
        template: Any?,
    ): List<Any>? {
        val configClass =
            HookUtils.findClassIfExists("android.net.wifi.WifiConfiguration", classLoader) ?: return null
        val aps = FakeRadioEnvironment.scanResults(
            cfg.latitude,
            cfg.longitude,
            EnvGeo.sessionSeed(cfg.startTimestamp),
            System.currentTimeMillis(),
        ).filter { it.ssid.isNotEmpty() }.take(8)
        if (aps.isEmpty()) return null

        val out = ArrayList<Any>(aps.size)
        aps.forEachIndexed { index, ap ->
            val config = WifiFabricator.newInstance(configClass, template) ?: return@forEachIndexed
            HookUtils.setFieldQuietly(config, "SSID", EnvGeo.quoteSsid(ap.ssid))
            HookUtils.setFieldQuietly(config, "BSSID", ap.bssid.uppercase())
            HookUtils.setFieldQuietly(config, "networkId", index)
            HookUtils.setFieldQuietly(config, "status", 1) // ENABLED
            HookUtils.setFieldQuietly(config, "hiddenSSID", false)
            val keyManagement = java.util.BitSet()
            keyManagement.set(1) // WifiConfiguration.KeyMgmt.WPA_PSK
            HookUtils.setFieldQuietly(config, "allowedKeyManagement", keyManagement)
            out.add(config)
        }
        return out
    }

    // --- WifiInfo getters ----------------------------------------------------

    private fun hookWifiInfoGetters(classLoader: ClassLoader) {
        val wifiInfo = HookUtils.findClassIfExists("android.net.wifi.WifiInfo", classLoader) ?: return

        val stringGetters = mapOf<String, (FakeRadioEnvironment.WifiAp) -> String>(
            "getSSID" to { if (it.ssid.isEmpty()) UNKNOWN_SSID else EnvGeo.quoteSsid(it.ssid) },
            "getBSSID" to { it.bssid },
            "getMacAddress" to { it.bssid },
        )
        for ((name, extractor) in stringGetters) {
            HookUtils.hookAllMethods(wifiInfo, name) { chain ->
                val ap = connectedAp(PacerRuntimeConfig.snapshot())
                    ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                extractor(ap)
            }
        }

        val intGetters = mapOf<String, (FakeRadioEnvironment.WifiAp) -> Int>(
            "getRssi" to { it.level },
            "getLinkSpeed" to { it.linkSpeedMbps },
            "getFrequency" to { it.frequency },
            "getNetworkId" to { it.networkId },
            "getWifiStandard" to { it.wifiStandard },
            "getIpAddress" to { WifiFabricator.IPV4_192_168_1_100 },
        )
        for ((name, extractor) in intGetters) {
            HookUtils.hookAllMethods(wifiInfo, name) { chain ->
                val ap = connectedAp(PacerRuntimeConfig.snapshot())
                    ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                extractor(ap)
            }
        }

        HookUtils.hookAllMethods(wifiInfo, "getSupplicantState") { chain ->
            val ap = connectedAp(PacerRuntimeConfig.snapshot())
                ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val state = if (ap.ssid.isEmpty()) null else supplicantStateCompleted(classLoader)
            state ?: chain.proceed(chain.args.toTypedArray())
        }
    }

    // --- helpers -------------------------------------------------------------

    internal fun connectedAp(cfg: PacerRuntimeConfig.Snapshot): FakeRadioEnvironment.WifiAp? {
        if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) return null
        return try {
            FakeRadioEnvironment.connectedAp(
                cfg.latitude,
                cfg.longitude,
                EnvGeo.sessionSeed(cfg.startTimestamp),
                System.currentTimeMillis(),
            )
        } catch (t: Throwable) {
            PacerLog.w("wifi-env", t)
            null
        }
    }

    private fun supplicantStateCompleted(classLoader: ClassLoader): Any? = try {
        HookUtils.findClassIfExists("android.net.wifi.SupplicantState", classLoader)
            ?.getField("COMPLETED")?.get(null)
    } catch (_: Throwable) {
        null
    }

    private const val UNKNOWN_SSID = "<unknown ssid>"
}
