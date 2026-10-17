// The AOSP baseline: the class names used when a ROM does not rename anything,
// and the fallback that keeps an unidentified device working. It always applies
// and always has the lowest priority.
package org.pacer.hook.hooks.system.vendor.profiles

import org.pacer.hook.hooks.system.vendor.DeviceProfile
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.hooks.system.vendor.SystemHookVendor
import org.pacer.motion.RomFamily

object AospVendor : SystemHookVendor {

    override val id = "aosp"

    override val family = RomFamily.AOSP

    override val priority = Int.MIN_VALUE

    override fun appliesTo(profile: DeviceProfile): Boolean = true

    override fun classCandidates(component: SystemComponent): List<String> = when (component) {
        SystemComponent.LOCATION_MANAGER_SERVICE -> listOf(
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService",
        )

        SystemComponent.LOCATION_PROVIDER_MANAGER ->
            listOf("com.android.server.location.provider.LocationProviderManager")

        SystemComponent.WIFI_SERVICE ->
            listOf("com.android.server.wifi.WifiServiceImpl")

        SystemComponent.WIFI_SCANNER_SERVICE ->
            listOf("com.android.server.wifi.scanner.WifiScanningServiceImpl")

        // Android 14 moved connectivity into the tethering APEX, where jarjar
        // renamed the class; a real-device diagnosis (OnePlus, Android 16)
        // recorded the new spelling after lookups by the old name started
        // failing. Both spellings are kept, newest first.
        SystemComponent.CONNECTIVITY_SERVICE -> listOf(
            "android.net.connectivity.com.android.server.ConnectivityService",
            "com.android.server.ConnectivityService",
        )

        SystemComponent.APPOPS_SERVICE -> listOf(
            "com.android.server.appop.AppOpsService",
            "com.android.server.AppOpsService",
        )

        SystemComponent.SETTINGS_PROVIDER ->
            listOf("com.android.providers.settings.SettingsProvider")

        SystemComponent.TELEPHONY_REGISTRY -> listOf(
            "com.android.server.telephony.TelephonyRegistry",
            "com.android.server.TelephonyRegistry",
        )

        SystemComponent.TELEPHONY_PHONE_MANAGER ->
            listOf("com.android.phone.PhoneInterfaceManager")

        // Bluetooth scanning moved between releases (gatt.GattService earlier,
        // le_scan.ScanBinder on Android 17) and between firmwares even on the
        // same vendor version, so the list is deliberately long. Order is
        // "most likely on a current release" first.
        SystemComponent.BLUETOOTH_SCAN_SERVICE -> listOf(
            "com.android.bluetooth.le_scan.ScanBinder",
            "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.le_scan.TransitionalScanHelper",
            "com.android.bluetooth.gatt.GattService",
            "com.android.bluetooth.btservice.AdapterService",
        )
    }
}
