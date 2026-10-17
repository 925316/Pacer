// ConnectivityManager side of the WiFi story. WifiSpoofHooker already answers
// WifiManager.getConnectionInfo with the virtual access point; if the app also
// asks ConnectivityManager for the same radio state it would see the *real*
// signal strength and the *real* WifiInfo, and the two views would disagree.
// A disagreement between two APIs that describe one radio is exactly the kind
// of cross-check a risk-control SDK performs, so both are answered from the
// same virtual environment.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object ConnectivitySpoofHooker {

    private const val WIFI_INFO_CLASS = "android.net.wifi.WifiInfo"

    fun install(classLoader: ClassLoader) {
        val capabilities = HookUtils.findClassIfExists("android.net.NetworkCapabilities", classLoader) ?: return

        // API 29+. Returns the RSSI for the active transport.
        HookUtils.hookAllMethods(capabilities, "getSignalStrength") { chain ->
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
            if (ap != null) ap.level else chain.proceed(chain.args.toTypedArray())
        }

        // API 31+. Hands back the transport specific info; for WiFi that is a
        // WifiInfo, which must show the same association as getConnectionInfo.
        HookUtils.hookAllMethods(capabilities, "getTransportInfo") { chain ->
            val real = chain.proceed(chain.args.toTypedArray())
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
            if (real != null && ap != null && real.javaClass.name == WIFI_INFO_CLASS) {
                try {
                    WifiSpoofHooker.mutateWifiInfo(real, ap, classLoader)
                } catch (t: Throwable) {
                    PacerLog.w("connectivity-info", t)
                }
            }
            real
        }

        PacerLog.i("connectivity environment hooks installed")
    }
}
