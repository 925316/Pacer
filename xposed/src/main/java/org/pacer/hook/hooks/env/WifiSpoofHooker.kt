// WiFi environment spoofing. Risk-control SDKs cross-check the visible AP list
// and the associated network against the reported position, so a moving fix
// surrounded by the real neighbourhood is a tell. When a simulation is active
// the scan list and every WifiInfo getter are replaced with the deterministic
// virtual environment from FakeRadioEnvironment.
//
// WifiInfo cannot be constructed off the phone stack (its setters are hidden and
// it has no usable public constructor), so the getters are hooked directly and
// the object returned by getConnectionInfo is additionally mutated in place.
//
// FOLLOW THE REAL RADIO, do not override it. Everything here is gated on the real
// state of the radio rather than on the simulation alone: no access points are
// invented when the real scan list is empty (WiFi off, or no scan permission), and
// no association is claimed for a WifiInfo that the platform itself left
// unassociated. The alternative - answering "WiFi is on and connected" while the
// radio is off - would contradict the device's own state twice over: the active
// network would still be cellular, and Settings.Global.WIFI_ON would still say 0.
// For the same reason getWifiState and isWifiEnabled are deliberately NOT hooked;
// they are the honest report of the state everything else here follows.
package org.pacer.hook.hooks.env

import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object WifiSpoofHooker {

    /**
     * The last fabricated scan: the access points it reported and the moment it
     * finished. Real scan results do not change between scans, so a caller that
     * polls getScanResults() twice must see the same list twice - with the same
     * timestamps - and only a scheduled scan may replace it.
     */
    private class ScanSnapshot(val aps: List<FakeRadioEnvironment.WifiAp>, val baseUs: Long)

    @Volatile private var lastScan: ScanSnapshot? = null

    /**
     * When the results of an accepted startScan() become available, or 0 when no
     * scan is pending. A scan takes a moment to walk its channels; results that
     * appear in the same microsecond as the request are a timing signal.
     */
    @Volatile private var scanReadyAtUs = 0L

    /** WifiInfo.INVALID_NETWORK_ID: the platform's "not associated with anything". */
    private const val NOT_ASSOCIATED = -1

    /**
     * Time a scan needs before its results are visible. Long enough for a
     * channel-dwell scan, short enough that a caller polling once a second still
     * sees the update on its next poll.
     */
    private const val SCAN_SETTLE_US = 1_200_000L

    fun install(classLoader: ClassLoader) {
        val wifiManager = HookUtils.findClassIfExists("android.net.wifi.WifiManager", classLoader) ?: return
        hookScanResults(wifiManager, classLoader)
        hookStartScan(wifiManager)
        hookConnectionInfo(wifiManager, classLoader)
        hookConfiguredNetworks(wifiManager, classLoader)
        hookDhcpInfo(wifiManager, classLoader)
        hookWifiInfoGetters(classLoader)
        PacerLog.i("wifi environment hooks installed")
    }

    // --- startScan -----------------------------------------------------------

    /**
     * startScan() tells the caller whether a scan was actually scheduled, and the
     * platform throttles callers. Mirroring the real answer keeps the story straight:
     * a scheduled scan replaces the cached results, a throttled one must leave them
     * exactly as they were. Serving freshly timestamped results after a throttled
     * call would be a contradiction between two calls describing one radio, and no
     * throttling model of our own is needed to avoid it.
     */
    private fun hookStartScan(wifiManager: Class<*>) {
        HookUtils.hookAllMethods(wifiManager, "startScan") { chain ->
            val started = chain.proceed(chain.args.toTypedArray())
            if (started == true) {
                scanReadyAtUs = SystemClock.elapsedRealtimeNanos() / 1000L + SCAN_SETTLE_US
            }
            started
        }
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
            // Follow the real radio: the platform answers with an empty list when WiFi
            // is off, and that is the state every other WiFi answer here follows too.
            // Inventing access points for a disabled radio would be a claim the device
            // itself contradicts (the active network is still cellular, and
            // Settings.Global.WIFI_ON still says 0).
            if (real.isEmpty()) return@hookAllMethods real
            val fake = try {
                val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
                val cached = lastScan
                val ready = scanReadyAtUs
                val snapshot = if (cached == null || (ready != 0L && nowUs >= ready)) {
                    ScanSnapshot(
                        aps = WifiFabricator.scanAps(cfg, System.currentTimeMillis()),
                        baseUs = nowUs,
                    ).also {
                        lastScan = it
                        if (ready != 0L && nowUs >= ready) scanReadyAtUs = 0L
                    }
                } else {
                    cached
                }
                WifiFabricator.buildScanResults(classLoader, snapshot.aps, real.firstOrNull(), snapshot.baseUs)
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
            // Follow the real radio: a WifiInfo the platform left unassociated belongs
            // to a radio that is off or between networks, and filling in an SSID there
            // would claim a connection that the active network (still cellular) and the
            // supplicant state both contradict.
            if (!reallyAssociated(info)) return@hookAllMethods info
            try {
                mutateWifiInfo(info, ap, classLoader)
            } catch (t: Throwable) {
                PacerLog.w("wifi-info", t)
            }
            info
        }
    }

    /**
     * Whether the platform itself put an association into [info], read from the
     * backing field rather than through getSSID(): the getters are hooked further
     * down, so asking them would answer from the virtual environment and always look
     * associated. A missing field reads as 0, which is treated as associated, so a
     * build that renames it keeps working instead of silently going quiet.
     */
    internal fun reallyAssociated(info: Any): Boolean =
        HookUtils.getIntField(info, "mNetworkId") != NOT_ASSOCIATED

    // --- getDhcpInfo ---------------------------------------------------------

    private fun hookDhcpInfo(wifiManager: Class<*>, classLoader: ClassLoader) {
        HookUtils.hookAllMethods(wifiManager, "getDhcpInfo") { chain ->
            if (connectedAp(PacerRuntimeConfig.snapshot()) == null) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            val info = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
            // A radio that is off or unassociated reports an all-zero lease, and that
            // is left alone: rewriting it would describe a network the device says it
            // is not on. A real lease, on the other hand, carries the user's home
            // gateway and DNS, and disagrees with the address WifiInfo reports.
            if (HookUtils.getIntField(info, "ipAddress") == 0) return@hookAllMethods info
            WifiFabricator.mutateDhcpInfo(info)
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
        val fix = CurrentFix.of(cfg)
        val aps = FakeRadioEnvironment.scanResults(
            fix.lat,
            fix.lon,
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
                // Follow the real radio: an instance the platform left unassociated
                // keeps its real answer, so a device that is not on WiFi does not
                // report an SSID (or a signal) for an association that does not exist.
                if (!associatedOrUnknown(chain.thisObject)) {
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
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
                // Follow the real radio: an instance the platform left unassociated
                // keeps its real answer, so a device that is not on WiFi does not
                // report an SSID (or a signal) for an association that does not exist.
                if (!associatedOrUnknown(chain.thisObject)) {
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                extractor(ap)
            }
        }

        HookUtils.hookAllMethods(wifiInfo, "getSupplicantState") { chain ->
            val ap = connectedAp(PacerRuntimeConfig.snapshot())
                ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (!associatedOrUnknown(chain.thisObject)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            val state = if (ap.ssid.isEmpty()) null else supplicantStateCompleted(classLoader)
            state ?: chain.proceed(chain.args.toTypedArray())
        }
    }

    /** As [reallyAssociated]; a receiver that cannot be checked is answered. */
    private fun associatedOrUnknown(target: Any?): Boolean =
        target == null || reallyAssociated(target)

    // --- helpers -------------------------------------------------------------

    internal fun connectedAp(cfg: PacerRuntimeConfig.Snapshot): FakeRadioEnvironment.WifiAp? {
        if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) return null
        return try {
            val fix = CurrentFix.of(cfg)
            FakeRadioEnvironment.connectedAp(
                fix.lat,
                fix.lon,
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
