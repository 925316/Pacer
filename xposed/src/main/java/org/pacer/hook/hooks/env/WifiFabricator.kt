// Builds the host objects that carry a fabricated WiFi view: the ScanResult
// list behind a scan and the field mutations applied to a WifiInfo. Both the
// app-process WifiManager hooks and the system_server WiFi service hooks answer
// from the same deterministic model, so the construction lives here instead of
// in either hooker.
//
// WHY IT IS REFLECTIVE. ScanResult's no-arg constructor is hidden before API 30
// and its copy constructor is hidden on some OEM builds; every field is written
// by name because the field set moved between releases. Construction therefore
// tries several strategies and gives up quietly, letting the caller keep the
// real value instead of a half-filled object.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils

object WifiFabricator {

    /**
     * Access points visible at the current position. The current position, not the
     * configured start: a run that walks away from where it began must not keep
     * reporting the access points around its origin.
     */
    internal fun scanAps(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long): List<FakeRadioEnvironment.WifiAp> {
        val fix = CurrentFix.of(cfg)
        return FakeRadioEnvironment.scanResults(
            fix.lat,
            fix.lon,
            EnvGeo.sessionSeed(cfg.startTimestamp),
            nowMs,
        )
    }

    /**
     * A fabricated ScanResult list built from [aps], or null when the class or a
     * constructor is unreachable on this build. [baseTimestampUs] is when the scan
     * finished; results are backdated across the dwell time that preceded it.
     */
    internal fun buildScanResults(
        classLoader: ClassLoader,
        aps: List<FakeRadioEnvironment.WifiAp>,
        template: Any?,
        baseTimestampUs: Long,
    ): List<Any>? {
        if (aps.isEmpty()) return null
        val scanResultClass =
            HookUtils.findClassIfExists("android.net.wifi.ScanResult", classLoader) ?: return null
        // A scan is not instantaneous: the radio walks its channels, so the results
        // arrive spread over the dwell time and carry different timestamps. One
        // timestamp for every result is something only a fabricator produces.
        val stepUs = SCAN_DWELL_US / aps.size
        val list = ArrayList<Any>(aps.size)
        for ((index, ap) in aps.withIndex()) {
            val result = newInstance(scanResultClass, template) ?: continue
            injectScanResult(result, ap, classLoader, baseTimestampUs - (aps.size - 1 - index) * stepUs)
            list.add(result)
        }
        return list
    }

    /** Overwrites every radio detail a WifiInfo getter can read. */
    internal fun mutateWifiInfo(info: Any, ap: FakeRadioEnvironment.WifiAp, classLoader: ClassLoader) {
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

    /**
     * No-arg constructor first, then a copy constructor taking an existing
     * instance as the template: both are hidden on some builds, so try both and
     * give up quietly when neither is reachable.
     */
    internal fun newInstance(clazz: Class<*>, template: Any?): Any? {
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

    private fun injectScanResult(result: Any, ap: FakeRadioEnvironment.WifiAp, classLoader: ClassLoader, timestampUs: Long) {
        HookUtils.setFieldQuietly(result, "SSID", ap.ssid)
        HookUtils.setFieldQuietly(result, "BSSID", ap.bssid)
        HookUtils.setFieldQuietly(result, "capabilities", ap.capabilities)
        HookUtils.setFieldQuietly(result, "level", ap.level)
        HookUtils.setFieldQuietly(result, "frequency", ap.frequency)
        HookUtils.setFieldQuietly(result, "timestamp", timestampUs)
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

    /**
     * Rewrites a real DhcpInfo so the lease describes the fabricated association.
     *
     * The addresses are little-endian integers, which is how the platform stores and
     * prints them: 0x6401A8C0 is 192.168.1.100, not 100.1.168.192. They are taken from
     * the same subnet as the address WifiInfo reports, because a lease and an
     * association that disagree is a contradiction between two APIs describing one
     * radio - and the real lease also carries the user's home gateway and DNS.
     */
    internal fun mutateDhcpInfo(info: Any) {
        HookUtils.setFieldQuietly(info, "ipAddress", IPV4_192_168_1_100)
        HookUtils.setFieldQuietly(info, "gateway", IPV4_192_168_1_1)
        HookUtils.setFieldQuietly(info, "netmask", IPV4_255_255_255_0)
        HookUtils.setFieldQuietly(info, "dns1", IPV4_192_168_1_1)
        HookUtils.setFieldQuietly(info, "dns2", IPV4_8_8_8_8)
        HookUtils.setFieldQuietly(info, "serverAddress", IPV4_192_168_1_1)
    }

    /** 192.168.1.100 in the little-endian integer form used by WifiInfo. */
    internal const val IPV4_192_168_1_100 = 0x6401A8C0

    internal const val IPV4_192_168_1_1 = 0x0101A8C0

    internal const val IPV4_255_255_255_0 = 0x00FFFFFF

    internal const val IPV4_8_8_8_8 = 0x08080808

    /**
     * Time a full scan spends walking its channels. Results are spaced across it,
     * which is the band a real scan's timestamps occupy.
     */
    private const val SCAN_DWELL_US = 200_000L
}
