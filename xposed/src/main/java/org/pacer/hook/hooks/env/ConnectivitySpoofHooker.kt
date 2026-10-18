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

    /** NetworkCapabilities.TRANSPORT_WIFI. */
    private const val TRANSPORT_WIFI = 1

    /** NetworkInfo.TYPE_WIFI. */
    private const val TYPE_WIFI = 1

    fun install(classLoader: ClassLoader) {
        val capabilities = HookUtils.findClassIfExists("android.net.NetworkCapabilities", classLoader) ?: return

        // API 29+. Returns the RSSI for the active transport - which is only a WiFi RSSI
        // when the transport IS WiFi. Answering with the virtual access point's level for
        // a cellular capability would report a signal that transport cannot have.
        HookUtils.hookAllMethods(capabilities, "getSignalStrength") { chain ->
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
            if (ap == null || !isWifiTransport(chain.thisObject)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            ap.level
        }

        // API 31+. Hands back the transport specific info; for WiFi that is a
        // WifiInfo, which must show the same association as getConnectionInfo.
        HookUtils.hookAllMethods(capabilities, "getTransportInfo") { chain ->
            val real = chain.proceed(chain.args.toTypedArray())
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
            if (real != null && ap != null && real.javaClass.name == WIFI_INFO_CLASS) {
                // Same rule as the WifiManager path: an instance the platform left
                // unassociated is not given an association here either.
                if (!WifiSpoofHooker.reallyAssociated(real)) return@hookAllMethods real
                try {
                    WifiSpoofHooker.mutateWifiInfo(real, ap, classLoader)
                } catch (t: Throwable) {
                    PacerLog.w("connectivity-info", t)
                }
            }
            real
        }

        hookNetworkInfoExtraInfo(classLoader)
        PacerLog.i("connectivity environment hooks installed")
    }

    /**
     * The second path to the connected SSID. getActiveNetworkInfo() and friends hand
     * back a NetworkInfo whose extra info IS the SSID on a Wi-Fi transport, so an app
     * that asks ConnectivityManager instead of WifiManager would read the real one
     * while every other WiFi answer is virtual. Only the Wi-Fi case is touched: on a
     * mobile network the extra info is the APN name, and a Wi-Fi NetworkInfo whose real
     * extra info is absent belongs to a radio that is not associated.
     */
    private fun hookNetworkInfoExtraInfo(loader: ClassLoader) {
        val networkInfo = HookUtils.findClassIfExists("android.net.NetworkInfo", loader) ?: return
        HookUtils.hookAllMethods(networkInfo, "getExtraInfo") { chain ->
            val real = chain.proceed(chain.args.toTypedArray())
            if (real !is String || real.isEmpty()) return@hookAllMethods real
            val target = chain.thisObject ?: return@hookAllMethods real
            if (HookUtils.intAccessor(target, "getType") != TYPE_WIFI) return@hookAllMethods real
            val ap = WifiSpoofHooker.connectedAp(PacerRuntimeConfig.snapshot())
                ?: return@hookAllMethods real
            EnvGeo.quoteSsid(ap.ssid)
        }
    }

    /**
     * Whether a NetworkCapabilities describes the WiFi transport. An unreadable answer
     * means "not WiFi", which leaves the real value in place rather than answering with
     * a WiFi strength for a transport that cannot have one.
     */
    private fun isWifiTransport(caps: Any?): Boolean {
        if (caps == null) return false
        return try {
            HookUtils.callMethod(caps, "hasTransport", TRANSPORT_WIFI) as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }
}
