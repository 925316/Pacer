// BLE environment handling. A business risk-control SDK can fingerprint the
// environment from bonded devices and from LE scan results, so while Bluetooth
// mocking is enabled the real bonded set is hidden.
//
// LIMITATION: LE scan results are not fabricated. Delivering a fake
// ScanCallback.onScanResult requires a real android.bluetooth.le.ScanResult,
// which in turn requires a BluetoothDevice built through the hidden
// constructor and a ScanRecord parsed from raw bytes; device methods then
// round-trip through the live Bluetooth service. That path is version and OEM
// dependent and would risk RemoteException / NPE inside the host app, so it is
// deliberately left out rather than shipped fragile. The deterministic beacon
// model lives in FakeRadioEnvironment for a future safe delivery path.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object BleSpoofHooker {

    fun install(classLoader: ClassLoader) {
        val adapter = HookUtils.findClassIfExists("android.bluetooth.BluetoothAdapter", classLoader) ?: return
        hookBondedDevices(adapter)
        PacerLog.i("ble environment hooks installed (suppress-only)")
    }

    private fun hookBondedDevices(adapter: Class<*>) {
        HookUtils.hookAllMethods(adapter, "getBondedDevices") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            java.util.HashSet<Any>()
        }
    }

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && cfg.mockBluetooth
    }
}
