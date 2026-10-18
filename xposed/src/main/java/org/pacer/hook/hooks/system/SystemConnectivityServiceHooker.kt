// system_server side of the connectivity view.
//
// WHY THIS LAYER EXISTS: the app-process ConnectivitySpoofHooker only covers an app
// that calls ConnectivityManager itself, and only while that app is in the module's
// scope. Every other caller reaches ConnectivityService across Binder, which is where
// this layer answers - the same division of labour as the wifi, cell and Bluetooth
// layers.
//
// What leaks without it, and why it is worth a layer of its own: a WiFi
// NetworkCapabilities carries the RSSI an indoor-positioning SDK uses, and its
// transport info IS a WifiInfo, so it carries the connected SSID. Both are the same
// quantities WifiManager reports - so leaving them real while WifiManager answers from
// the virtual environment is a disagreement between two APIs describing one radio,
// which is exactly the cross-check this project exists to make impossible.
//
// The objects are rewritten IN PLACE and then returned, rather than replaced: they are
// the platform's own instances, fully populated for their release, and only the fields
// Pacer has an opinion about are touched.
package org.pacer.hook.hooks.system

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.env.FakeRadioEnvironment
import org.pacer.hook.hooks.env.WifiSpoofHooker
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object SystemConnectivityServiceHooker {

    /** NetworkCapabilities.TRANSPORT_WIFI. */
    private const val TRANSPORT_WIFI = 1

    /** NetworkInfo.TYPE_WIFI. */
    private const val TYPE_WIFI = 1

    private const val WIFI_INFO_CLASS = "android.net.wifi.WifiInfo"

    /** Deprecated but still served by several releases, so both are tried. */
    private val NETWORK_INFO_METHODS = arrayOf("getActiveNetworkInfo", "getNetworkInfo")

    fun install(classLoader: ClassLoader) {
        val service = SystemClassLocator.locate(SystemComponent.CONNECTIVITY_SERVICE, classLoader)
        if (service == null) {
            PacerLog.i("connectivity service not resolved; system connectivity left untouched")
            return
        }
        var hooked = hookNetworkCapabilities(service, classLoader)
        for (name in NETWORK_INFO_METHODS) hooked += hookNetworkInfo(service, name)
        PacerLog.i("system connectivity hooks installed (targets=$hooked)")
    }

    // --- NetworkCapabilities -------------------------------------------------

    private fun hookNetworkCapabilities(service: Class<*>, classLoader: ClassLoader): Int =
        HookUtils.hookAllMethods(service, "getNetworkCapabilities") { chain ->
            val real = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
            // Only a WiFi transport is rewritten: a WiFi RSSI written into cellular
            // capabilities would be a value that transport cannot have.
            if (!isWifi(real)) return@hookAllMethods real
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
                ?: return@hookAllMethods real
            try {
                installWifiCapabilities(real, ap, classLoader)
            } catch (t: Throwable) {
                PacerLog.w("system-connectivity", t)
            }
            real
        }

    /**
     * The signal strength and the transport info of a WiFi NetworkCapabilities, from
     * the same virtual access point the WifiManager path reports. Both fields are
     * hidden, so both are set by name and the transport info is only rewritten when it
     * really is a WifiInfo.
     */
    private fun installWifiCapabilities(
        caps: Any,
        ap: FakeRadioEnvironment.WifiAp,
        classLoader: ClassLoader,
    ) {
        HookUtils.trySetField(caps, "mSignalStrength", ap.level)
        val info = HookUtils.getObjectField(caps, "mTransportInfo") ?: return
        if (info.javaClass.name == WIFI_INFO_CLASS) {
            WifiSpoofHooker.mutateWifiInfo(info, ap, classLoader)
        }
    }

    /**
     * hasTransport is how the transport is checked instead of guessing from the field
     * layout, and an unreadable answer means "leave it alone" rather than a rewrite.
     */
    private fun isWifi(caps: Any): Boolean =
        try {
            HookUtils.callMethod(caps, "hasTransport", TRANSPORT_WIFI) as? Boolean == true
        } catch (_: Throwable) {
            false
        }

    // --- NetworkInfo ---------------------------------------------------------

    private fun hookNetworkInfo(service: Class<*>, methodName: String): Int =
        HookUtils.hookAllMethods(service, methodName) { chain ->
            val real = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
            if (HookUtils.intAccessor(real, "getType") != TYPE_WIFI) return@hookAllMethods real
            // Follow the real radio, the same rule the app-process layer uses: the extra
            // info of a WiFi NetworkInfo only exists while one is really associated, and
            // it is the connected SSID an app can read without asking WifiManager.
            val extra = HookUtils.getObjectField(real, "mExtraInfo") as? String
            if (extra.isNullOrEmpty()) return@hookAllMethods real
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
                ?: return@hookAllMethods real
            HookUtils.trySetField(real, "mExtraInfo", EnvGeo.quoteSsid(ap.ssid))
            real
        }
}
