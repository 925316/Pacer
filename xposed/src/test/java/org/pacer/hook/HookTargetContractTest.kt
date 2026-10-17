// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Every hook in this module names its target as a STRING, because the class is
// looked up inside the target process at runtime. The Kotlin compiler therefore
// verifies nothing about those names: a typo, or a platform member that was
// renamed, compiles cleanly and only misbehaves on a device. This test closes
// that gap by resolving every name against the compile SDK.
//
// Honest limits, so the result is not over-read:
//  - Only PUBLIC platform API can be checked. android.jar is the SDK stub jar
//    and carries no @hide members, so the hidden classes and fields the hooks
//    also touch (SystemSensorManager$SensorEventQueue, the obfuscated
//    CellIdentity fields, ...) cannot be verified here. Those remain a
//    device-verification concern.
//  - Names are checked, not signatures. The risk being guarded against is a
//    renamed or misspelled member; a name-level check catches that without
//    failing every time AOSP widens a parameter list.
//  - Members AOSP deprecated and later removed are reported, not asserted. A
//    hook aimed at one is expected to degrade to a no-op where it is gone, so
//    failing the build over it would be wrong. Printing them documents what
//    this SDK still offers.
package org.pacer.hook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

class HookTargetContractTest {

    @Test
    fun everyNamedHookTargetResolvesAgainstTheCompileSdk() {
        val missing = mutableListOf<String>()

        for (name in REQUIRED_CLASSES) {
            if (load(name) == null) missing += "class $name"
        }
        for ((className, members) in REQUIRED_MEMBERS) {
            val clazz = load(className)
            if (clazz == null) {
                missing += "class $className"
                continue
            }
            for (member in members) {
                if (!hasMemberNamed(clazz, member)) missing += "$className#$member"
            }
        }

        if (missing.isNotEmpty()) {
            // Printed before failing so the list is always visible in the CI log,
            // not only in the HTML report.
            println("MISSING HOOK TARGETS:\n" + missing.joinToString("\n"))
        }

        assertTrue(
            "Hook targets that do not exist in the compile SDK. Fix the string, or " +
                "move it to PROBED_PLATFORM_MEMBERS if the API was deliberately " +
                "removed and the hook is meant to degrade:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    /**
     * Informational and never failing: prints the surface this SDK actually
     * exposes, so a hook that must degrade gracefully is designed against facts
     * rather than recollection. Read it in the CI log.
     */
    @Test
    fun reportPlatformSurface() {
        val report = StringBuilder("\n===== Pacer hook target surface =====\n")
        report.append("Caveat: \"absent\" means absent from the SDK stub jar. @hide\n")
        report.append("members never appear here even though they exist on a device.\n")

        report.append("-- constructors of the types a fabricated radio payload needs --\n")
        for (name in CELL_TYPES + WIFI_TYPES + BT_TYPES) {
            val clazz = load(name)
            if (clazz == null) {
                report.append("  $name: ABSENT\n")
                continue
            }
            val ctors = clazz.declaredConstructors
            report.append("  $name: ${ctors.size} declared constructor(s)\n")
            for (ctor in ctors.sortedBy { it.parameterCount }) {
                report.append("      ${visibility(ctor)} arity ${ctor.parameterCount}: ")
                report.append(ctor.parameterTypes.joinToString(", ") { it.simpleName })
                report.append("\n")
            }
        }

        report.append("-- members whose presence depends on the release --\n")
        for ((className, members) in PROBED_PLATFORM_MEMBERS) {
            val clazz = load(className)
            if (clazz == null) {
                report.append("  $className: ABSENT\n")
                continue
            }
            for (member in members) {
                val present = if (hasMemberNamed(clazz, member)) "present" else "absent "
                report.append("  $present  $className#$member\n")
            }
        }
        report.append("=====================================\n")
        println(report)
    }

    private fun visibility(ctor: Constructor<*>): String =
        if (Modifier.isPublic(ctor.modifiers)) "public" else "hidden"

    private fun load(name: String): Class<*>? =
        try {
            Class.forName(name)
        } catch (_: Throwable) {
            null
        }

    private fun hasMemberNamed(clazz: Class<*>, name: String): Boolean =
        clazz.methods.any { it.name == name } ||
            clazz.declaredMethods.any { it.name == name } ||
            clazz.fields.any { it.name == name } ||
            clazz.declaredFields.any { it.name == name }

    private companion object {

        /** Public types the hooks name. Keep in sync with the hookers. */
        val REQUIRED_CLASSES = listOf(
            "android.location.LocationManager",
            "android.location.Location",
            "android.location.GnssStatus",
            "android.telephony.TelephonyManager",
            "android.telephony.TelephonyManager\$CellInfoCallback",
            "android.telephony.TelephonyCallback",
            "android.telephony.CellInfo",
            "android.telephony.CellInfoLte",
            "android.telephony.CellInfoGsm",
            "android.telephony.CellInfoWcdma",
            "android.telephony.CellInfoNr",
            "android.telephony.CellIdentityLte",
            "android.telephony.CellIdentityGsm",
            "android.telephony.CellIdentityWcdma",
            "android.telephony.CellIdentityNr",
            "android.telephony.CellSignalStrengthLte",
            "android.telephony.CellSignalStrengthGsm",
            "android.telephony.CellSignalStrengthWcdma",
            "android.telephony.CellSignalStrengthNr",
            "android.telephony.ServiceState",
            "android.telephony.SignalStrength",
            "android.app.AppOpsManager",
            "android.provider.Settings\$Secure",
            "android.net.ConnectivityManager",
            "android.net.NetworkCapabilities",
            "android.net.NetworkInfo",
            "android.net.wifi.WifiManager",
            "android.net.wifi.WifiInfo",
            "android.net.wifi.ScanResult",
            "android.net.wifi.WifiSsid",
            "android.bluetooth.BluetoothAdapter",
            "android.bluetooth.BluetoothDevice",
            "android.bluetooth.BluetoothManager",
            "android.bluetooth.le.BluetoothLeScanner",
            "android.bluetooth.le.ScanResult",
            "android.bluetooth.le.ScanRecord",
            "android.bluetooth.le.ScanFilter",
            "android.bluetooth.le.ScanCallback",
            "android.bluetooth.le.ScanSettings",
            "android.os.ParcelUuid",
            "android.content.pm.PackageManager",
            "android.hardware.Sensor",
            "android.hardware.SensorManager",
            "android.os.SystemClock",
        )

        /** Members the hooks reach for by name. Name-level, not signature-level. */
        val REQUIRED_MEMBERS = mapOf(
            "android.location.LocationManager" to listOf(
                "getLastKnownLocation", "requestLocationUpdates", "isProviderEnabled",
                "getProviders", "getAllProviders", "registerGnssStatusCallback",
                "getCurrentLocation",
            ),
            "android.location.GnssStatus" to listOf(
                "getSatelliteCount", "getSvid", "getCn0DbHz", "usedInFix",
                "getConstellationType", "getElevationDegrees", "getAzimuthDegrees",
            ),
            "android.location.Location" to listOf(
                "getLatitude", "getLongitude", "getAccuracy", "getAltitude",
                "getBearing", "getSpeed", "getTime", "getElapsedRealtimeNanos",
                "getProvider", "setLatitude", "setLongitude", "setAccuracy",
                "setAltitude", "setBearing", "setSpeed", "setTime",
                "distanceTo", "bearingTo",
            ),
            "android.telephony.TelephonyManager" to listOf(
                "getAllCellInfo", "getNetworkOperator", "getNetworkOperatorName",
                "getNetworkType", "getDataNetworkType", "getPhoneType",
                "getServiceState", "getSignalStrength", "requestCellInfoUpdate",
                "registerTelephonyCallback", "unregisterTelephonyCallback",
            ),
            "android.telephony.CellInfo" to listOf(
                "isRegistered", "getTimeStamp", "getCellIdentity", "getCellSignalStrength",
            ),
            "android.telephony.CellInfoLte" to listOf("getCellIdentity", "getCellSignalStrength"),
            "android.telephony.CellInfoGsm" to listOf("getCellIdentity", "getCellSignalStrength"),
            "android.telephony.CellInfoWcdma" to listOf("getCellIdentity", "getCellSignalStrength"),
            "android.telephony.CellIdentityLte" to listOf(
                "getMcc", "getMnc", "getCi", "getPci", "getTac",
            ),
            "android.telephony.CellIdentityGsm" to listOf("getMcc", "getMnc", "getLac", "getCid"),
            "android.telephony.CellIdentityWcdma" to listOf(
                "getMcc", "getMnc", "getLac", "getCid", "getPsc",
            ),
            "android.telephony.CellSignalStrengthLte" to listOf("getLevel"),
            "android.telephony.CellSignalStrengthGsm" to listOf("getLevel"),
            "android.telephony.CellSignalStrengthWcdma" to listOf("getLevel"),
            "android.app.AppOpsManager" to listOf(
                "checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow", "startWatchingMode",
            ),
            "android.provider.Settings\$Secure" to listOf(
                "getString", "getInt", "getFloat", "getLong", "putString",
            ),
            "android.net.ConnectivityManager" to listOf(
                "getNetworkCapabilities", "registerDefaultNetworkCallback",
            ),
            "android.net.NetworkCapabilities" to listOf(
                "getSignalStrength", "getTransportInfo", "hasTransport",
            ),
            "android.net.wifi.WifiManager" to listOf(
                "getScanResults", "getConnectionInfo", "isWifiEnabled", "getWifiState",
                "calculateSignalLevel",
            ),
            "android.net.wifi.WifiInfo" to listOf(
                "getBSSID", "getSSID", "getRssi", "getLinkSpeed", "getFrequency",
                "getNetworkId", "getSupplicantState",
            ),
            "android.net.wifi.ScanResult" to listOf(
                // BSSID / SSID / frequency / level are public FIELDS on ScanResult,
                // not getters: a hook aimed at getBSSID() would silently do nothing.
                "BSSID", "SSID", "frequency", "level", "timestamp", "getWifiSsid",
            ),
            "android.bluetooth.BluetoothAdapter" to listOf(
                "getRemoteDevice", "isEnabled", "getState", "getBluetoothLeScanner",
                "getBondedDevices", "startDiscovery", "cancelDiscovery",
            ),
            "android.bluetooth.BluetoothDevice" to listOf(
                "getAddress", "getName", "getType", "getBondState", "getUuids", "connectGatt",
            ),
            "android.bluetooth.le.BluetoothLeScanner" to listOf("startScan", "stopScan"),
            "android.bluetooth.le.ScanRecord" to listOf(
                "getDeviceName", "getServiceUuids", "getManufacturerSpecificData",
                "getTxPowerLevel",
            ),
            "android.bluetooth.le.ScanResult" to listOf(
                "getDevice", "getRssi", "getScanRecord", "getTimestampNanos",
            ),
            "android.bluetooth.BluetoothManager" to listOf(
                "getAdapter", "getConnectedDevices", "getDevicesMatchingConnectionStates",
            ),
            "android.bluetooth.le.ScanCallback" to listOf(
                "onScanResult", "onBatchScanResults", "onScanFailed",
            ),
            "android.bluetooth.le.ScanSettings" to listOf("CALLBACK_TYPE_ALL_MATCHES", "getCallbackType"),
            "android.bluetooth.le.ScanFilter" to listOf("matches"),
            "android.os.ParcelUuid" to listOf("fromString"),
            "android.content.pm.PackageManager" to listOf("hasSystemFeature"),
            "android.hardware.SensorManager" to listOf(
                "getDefaultSensor", "registerListener", "unregisterListener", "getSensorList",
            ),
            "android.hardware.Sensor" to listOf(
                "getType", "getName", "getMaxDelay", "getMinDelay",
                "getPower", "getVendor", "getVersion",
            ),
            "android.os.SystemClock" to listOf(
                "elapsedRealtimeNanos", "elapsedRealtime", "uptimeMillis",
            ),
            "android.telephony.ServiceState" to listOf(
                "getState", "getOperatorNumeric",
            ),
            "android.telephony.SignalStrength" to listOf(
                "getLevel", "getCellSignalStrengths",
            ),
        )

        /** Printed, never asserted: expected to disappear across releases. */
        val PROBED_PLATFORM_MEMBERS = mapOf(
            "android.location.LocationManager" to listOf(
                "addGpsStatusListener", "removeGpsStatusListener", "requestSingleUpdate",
                "addTestProvider", "removeTestProvider", "setTestProviderEnabled",
                "setTestProviderLocation", "getGnssAntennaInfos", "addGnssAntennaInfoListener",
                "addGnssMeasurementsListener", "addGnssNavigationMessageListener",
                "registerGnssMeasurementsCallback", "registerGnssNavigationMessageCallback",
            ),
            "android.location.Location" to listOf(
                "setElapsedRealtimeNanos", "isFromMockProvider", "isMock",
            ),
            "android.telephony.CellInfo" to listOf("getCellConnectionStatus"),
            "android.telephony.CellInfoNr" to listOf("getCellIdentity", "getCellSignalStrength"),
            "android.telephony.CellIdentityLte" to listOf("getEarfcn", "getBands"),
            "android.telephony.CellIdentityGsm" to listOf("getArfcn", "getBsic"),
            "android.telephony.CellIdentityWcdma" to listOf("getUarfcn"),
            "android.telephony.CellIdentityNr" to listOf(
                "getMccString", "getMncString", "getNci", "getPci", "getTac", "getNrArfcn",
            ),
            "android.telephony.CellSignalStrengthLte" to listOf(
                "getDbm", "getRsrp", "getRsrq", "getRssnr", "getTimingAdvance", "getCqi",
            ),
            "android.telephony.CellSignalStrengthGsm" to listOf("getDbm", "getBitErrorRate"),
            "android.telephony.CellSignalStrengthWcdma" to listOf("getDbm"),
            "android.content.pm.ParceledListSlice" to listOf("getList", "setList"),
            "android.net.wifi.WifiInfo\$Builder" to listOf("setBssid", "setSsid", "setRssi", "build"),
            "android.net.wifi.WifiSsid" to listOf("fromBytes", "createFromAsciiEncoded", "getBytes"),
            "android.telephony.ServiceState" to listOf(
                "getOperatorAlphaLong", "getOperatorAlphaShort", "getNrState", "getCellIdentity",
            ),
            "android.telephony.SignalStrength" to listOf("isGsm"),
            "android.location.GpsStatus" to listOf("getSatelliteCount"),
            "android.telephony.TelephonyManager" to listOf(
                "getCellLocation", "getNeighboringCellInfo", "getSimOperator",
                "getServiceStateForSlot", "listen",
            ),
            "android.net.ConnectivityManager" to listOf(
                "getActiveNetworkInfo", "getNetworkInfo",
            ),
            "android.net.wifi.WifiManager" to listOf(
                "getConfiguredNetworks", "getDhcpInfo",
            ),
            "android.net.wifi.WifiInfo" to listOf(
                "getMacAddress", "getIpAddress", "getWifiStandard", "getHiddenSSID",
            ),
            "android.bluetooth.BluetoothAdapter" to listOf(
                "startLeScan", "stopLeScan", "getAddress", "getLeState",
                "getRemoteDevice", "checkBluetoothAddress",
            ),
            "android.bluetooth.BluetoothAdapter\$LeScanCallback" to listOf("onLeScan"),
            "android.bluetooth.BluetoothDevice" to listOf("getBluetoothClass", "createBond"),
            "android.bluetooth.le.ScanRecord" to listOf(
                "getBytes", "parseFromBytes", "getAdvertiseFlags",
            ),
            "android.os.ParcelUuid" to listOf("getUuid", "toString"),
            "android.bluetooth.le.ScanRecord" to listOf("getBytes"),
            "android.hardware.Sensor" to listOf("getHandle", "getStringType", "getReportingMode"),
            "android.app.AppOpsManager" to listOf("strOpToOp", "opToName"),
        )

        /** Types whose constructors decide how a fabricated cell can be built. */
        val CELL_TYPES = listOf(
            "android.telephony.CellInfo",
            "android.telephony.CellInfoLte",
            "android.telephony.CellInfoGsm",
            "android.telephony.CellInfoWcdma",
            "android.telephony.CellInfoNr",
            "android.telephony.CellIdentityLte",
            "android.telephony.CellIdentityGsm",
            "android.telephony.CellIdentityWcdma",
            "android.telephony.CellIdentityNr",
            "android.telephony.CellSignalStrengthLte",
            "android.telephony.CellSignalStrengthGsm",
            "android.telephony.CellSignalStrengthWcdma",
            "android.telephony.CellSignalStrengthNr",
            "android.telephony.ServiceState",
            "android.telephony.SignalStrength",
        )

        /**
         * Types whose constructors decide how a fabricated WiFi payload can be
         * built, including the parcelable slice that the system-side scan result
         * call returns across Binder.
         */
        val WIFI_TYPES = listOf(
            "android.net.wifi.ScanResult",
            "android.net.wifi.WifiInfo",
            "android.net.wifi.WifiSsid",
            "android.content.pm.ParceledListSlice",
        )

        /**
         * Types whose constructors decide how a fabricated Bluetooth payload can
         * be built. BluetoothDevice and ScanResult both have hidden constructors
         * whose parameter shapes moved between releases, so the shipped code
         * validates whatever it builds by reading it back.
         */
        val BT_TYPES = listOf(
            "android.bluetooth.BluetoothDevice",
            "android.bluetooth.le.ScanResult",
            "android.bluetooth.le.ScanRecord",
            "android.os.ParcelUuid",
        )
    }
}
