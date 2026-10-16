// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
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

import android.os.SystemClock
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
                buildScanResults(cfg, classLoader, real.firstOrNull())
            } catch (t: Throwable) {
                PacerLog.w("wifi-scan", t)
                null
            }
            if (fake.isNullOrEmpty()) return@hookAllMethods real
            fake
        }
    }

    private fun buildScanResults(
        cfg: PacerRuntimeConfig.Snapshot,
        classLoader: ClassLoader,
        template: Any?,
    ): List<Any>? {
        val scanResultClass =
            HookUtils.findClassIfExists("android.net.wifi.ScanResult", classLoader) ?: return null
        val aps = FakeRadioEnvironment.scanResults(
            cfg.latitude,
            cfg.longitude,
            EnvGeo.sessionSeed(cfg.startTimestamp),
            System.currentTimeMillis(),
        )
        if (aps.isEmpty()) return null

        val list = ArrayList<Any>(aps.size)
        for (ap in aps) {
            val result = newScanResult(scanResultClass, template) ?: continue
            injectScanResult(result, ap, classLoader)
            list.add(result)
        }
        return list
    }

    /**
     * ScanResult's no-arg constructor is hidden before API 30 and the copy
     * constructor is hidden on some OEM builds, so try both and give up
     * quietly if neither is reachable.
     */
    private fun newScanResult(clazz: Class<*>, template: Any?): Any? {
        try {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            return ctor.newInstance()
        } catch (_: Throwable) {
        }
        if (template != null && clazz.isInstance(template)) {
            try {
                val ctor = clazz.getDeclaredConstructor(clazz)
                ctor.isAccessible = true
                return ctor.newInstance(template)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun injectScanResult(result: Any, ap: FakeRadioEnvironment.WifiAp, classLoader: ClassLoader) {
        HookUtils.setFieldQuietly(result, "SSID", ap.ssid)
        HookUtils.setFieldQuietly(result, "BSSID", ap.bssid)
        HookUtils.setFieldQuietly(result, "capabilities", ap.capabilities)
        HookUtils.setFieldQuietly(result, "level", ap.level)
        HookUtils.setFieldQuietly(result, "frequency", ap.frequency)
        HookUtils.setFieldQuietly(result, "timestamp", SystemClock.elapsedRealtimeNanos() / 1000L)
        HookUtils.setFieldQuietly(result, "wifiStandard", ap.wifiStandard)
        HookUtils.setFieldQuietly(result, "centerFreq0", ap.centerFreq0)
        HookUtils.setFieldQuietly(result, "centerFreq1", ap.centerFreq1)
        HookUtils.setFieldQuietly(result, "channelWidth", ap.channelWidth)
        HookUtils.setFieldQuietly(result, "antenna", 0)
        HookUtils.setFieldQuietly(result, "is80211mcResponder", false)
        setWifiSsidField(result, "wifiSsid", ap.ssid, classLoader)
        // Empty arrays keep OEM scan statistics from NPE-ing on missing radio
        // metadata; we deliberately do not fabricate InformationElements.
        setEmptyArrayField(result, "informationElements", "android.net.wifi.ScanResult\$InformationElement", classLoader)
        setEmptyArrayField(result, "radioChainInfos", "android.net.wifi.ScanResult\$RadioChainInfo", classLoader)
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

    private fun mutateWifiInfo(info: Any, ap: FakeRadioEnvironment.WifiAp, classLoader: ClassLoader) {
        HookUtils.setFieldQuietly(info, "mSSID", EnvGeo.quoteSsid(ap.ssid))
        HookUtils.setFieldQuietly(info, "mBSSID", ap.bssid)
        HookUtils.setFieldQuietly(info, "mMacAddress", ap.bssid)
        HookUtils.setFieldQuietly(info, "mRssi", ap.level)
        HookUtils.setFieldQuietly(info, "mFrequency", ap.frequency)
        HookUtils.setFieldQuietly(info, "mLinkSpeed", ap.linkSpeedMbps)
        HookUtils.setFieldQuietly(info, "mNetworkId", ap.networkId)
        HookUtils.setFieldQuietly(info, "mWifiStandard", ap.wifiStandard)
        HookUtils.setFieldQuietly(info, "mIpAddress", IPV4_192_168_1_100)
        setWifiSsidField(info, "mWifiSsid", ap.ssid, classLoader)
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
            val config = newInstance(configClass, template) ?: return@forEachIndexed
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
            "getIpAddress" to { IPV4_192_168_1_100 },
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

    private fun connectedAp(cfg: PacerRuntimeConfig.Snapshot): FakeRadioEnvironment.WifiAp? {
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

    private fun newInstance(clazz: Class<*>, template: Any?): Any? {
        try {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            return ctor.newInstance()
        } catch (_: Throwable) {
        }
        if (template != null && clazz.isInstance(template)) {
            try {
                val ctor = clazz.getDeclaredConstructor(clazz)
                ctor.isAccessible = true
                return ctor.newInstance(template)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun setWifiSsidField(target: Any, fieldName: String, ssid: String, classLoader: ClassLoader) {
        if (ssid.isEmpty()) return
        try {
            val wifiSsidClass =
                HookUtils.findClassIfExists("android.net.wifi.WifiSsid", classLoader) ?: return
            val create = wifiSsidClass.getDeclaredMethod("createFromAsciiEncoded", String::class.java)
            create.isAccessible = true
            val wifiSsid = create.invoke(null, ssid) ?: return
            HookUtils.setFieldQuietly(target, fieldName, wifiSsid)
        } catch (_: Throwable) {
        }
    }

    private fun setEmptyArrayField(target: Any, fieldName: String, componentName: String, classLoader: ClassLoader) {
        try {
            val component = HookUtils.findClassIfExists(componentName, classLoader) ?: return
            HookUtils.setFieldQuietly(target, fieldName, java.lang.reflect.Array.newInstance(component, 0))
        } catch (_: Throwable) {
        }
    }

    /** 192.168.1.100 in the little-endian integer form used by WifiInfo. */
    private const val IPV4_192_168_1_100 = 0x6401A8C0

    private const val UNKNOWN_SSID = "<unknown ssid>"
}
