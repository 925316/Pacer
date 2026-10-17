// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// BLE environment handling. A risk-control SDK can fingerprint the environment
// from bonded devices and from LE scan results, so while Bluetooth mocking is
// enabled the real bonded set is hidden and the scan stream is served from the
// deterministic beacon model.
//
// DELIVERY, following the structure the mature implementations use: the real
// results are SWALLOWED and the module delivers its own beacon set on a
// heartbeat. That is what makes the app's view independent of what happens to be
// advertising nearby - rewriting the real results would leave the app with
// nothing to see in a quiet environment. The heartbeat is started from startScan
// and cancelled from stopScan, and delivery runs inside a CallScope so the
// module's own callback interception can tell its own delivery from the app's
// call and let it through.
//
// The startScan overload that delivers to a PendingIntent broadcast is not
// rewritten HERE, because this layer never sees that callback: the platform builds
// it itself and turns results into broadcasts. It is covered by the bluetooth
// process layer (SystemBluetoothServiceHooker), which serves whatever callback the
// scan service was handed, that one included.
package org.pacer.hook.hooks.env

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.CallScope
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.ConcurrentHashMap

object BleSpoofHooker {

    private const val SCAN_CALLBACK = "android.bluetooth.le.ScanCallback"
    private const val LEGACY_CALLBACK = "android.bluetooth.BluetoothAdapter\$LeScanCallback"

    /** Roughly the advertising interval of a real beacon. */
    private const val DELIVER_INTERVAL_MS = 1200L

    /**
     * The beacon model re-jitters its levels on this period, so the payload cache
     * follows it instead of inventing its own.
     */
    private const val JITTER_BUCKET_MS = 1500L

    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()

    /** One heartbeat per registered callback, cancelled when the scan stops. */
    private val deliveries = ConcurrentHashMap<Any, Runnable>()

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private var classLoader: ClassLoader? = null

    private data class PayloadCache(val areaKey: Long, val bucket: Long, val results: List<Any>)

    @Volatile private var cache: PayloadCache? = null

    fun install(loader: ClassLoader) {
        classLoader = loader
        val adapter = HookUtils.findClassIfExists("android.bluetooth.BluetoothAdapter", loader) ?: return
        hookBondedDevices(adapter)
        hookLeScanner(loader)
        hookLegacyLeScan(adapter)
        hookStopScan(loader, adapter)
        hookScanFilter(loader)
        PacerLog.i("ble environment hooks installed")
    }

    // --- bonded devices ------------------------------------------------------

    private fun hookBondedDevices(adapter: Class<*>) {
        HookUtils.hookAllMethods(adapter, "getBondedDevices") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            java.util.HashSet<Any>()
        }
    }

    // --- modern LE scans -----------------------------------------------------

    private fun hookLeScanner(loader: ClassLoader) {
        val scanner = HookUtils.findClassIfExists("android.bluetooth.le.BluetoothLeScanner", loader) ?: return
        HookUtils.hookAllMethods(scanner, "startScan") { chain ->
            val callback = chain.args.firstOrNull { it != null && HookUtils.hasTypeByName(it.javaClass, SCAN_CALLBACK) }
            val settings = chain.args.firstOrNull { it is ScanSettings } as? ScanSettings
            if (callback != null && suppressing()) {
                // The callback class is only intercepted when the delivery that serves
                // it is actually running. Intercepting a class that nothing will
                // answer with is one of those bugs that only shows up as "no
                // results", so the two halves start together.
                if (startModernDelivery(callback, settings?.callbackType ?: ScanSettings.CALLBACK_TYPE_ALL_MATCHES)) {
                    hookScanCallback(callback.javaClass)
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    /**
     * The app's own filter has to accept what this module delivers, otherwise its
     * callback would be handed devices it explicitly asked not to match. Only the
     * module's own delivery is affected: the CallScope check keeps the app's real
     * filtering behaviour intact when the app filters a result itself.
     */
    private fun hookScanFilter(loader: ClassLoader) {
        val filter = HookUtils.findClassIfExists("android.bluetooth.le.ScanFilter", loader) ?: return
        HookUtils.hookAllMethods(filter, "matches") { chain ->
            if (CallScope.active()) return@hookAllMethods true
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookScanCallback(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) != null) return
        HookUtils.hookAllMethods(clazz, "onScanResult") { chain -> swallow(chain) }
        HookUtils.hookAllMethods(clazz, "onBatchScanResults") { chain -> swallow(chain) }
    }

    private fun startModernDelivery(callback: Any, callbackType: Int): Boolean {
        val typed = callback as? ScanCallback ?: return false
        startDelivery(callback) {
            CallScope.enter {
                for (result in payload().orEmpty()) {
                    if (result is ScanResult) {
                        typed.onScanResult(callbackType, result)
                    }
                }
            }
        }
        return true
    }

    // --- deprecated LE scans -------------------------------------------------

    /**
     * startLeScan is deprecated since API 21, but an app still using it is served
     * from the same beacon set so old and new apps see one virtual environment.
     */
    private fun hookLegacyLeScan(adapter: Class<*>) {
        HookUtils.hookAllMethods(adapter, "startLeScan") { chain ->
            val callback = chain.args.firstOrNull { it != null && HookUtils.hasTypeByName(it.javaClass, LEGACY_CALLBACK) }
            if (callback != null && suppressing()) {
                if (startLegacyDelivery(callback)) hookLegacyCallback(callback.javaClass)
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookLegacyCallback(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) != null) return
        HookUtils.hookAllMethods(clazz, "onLeScan") { chain -> swallow(chain) }
    }

    private fun startLegacyDelivery(callback: Any): Boolean {
        val typed = callback as? BluetoothAdapter.LeScanCallback ?: return false
        startDelivery(callback) {
            CallScope.enter {
                for (result in payload().orEmpty()) {
                    if (result is ScanResult) {
                        typed.onLeScan(result.device, result.rssi, result.scanRecord?.bytes ?: ByteArray(0))
                    }
                }
            }
        }
        return true
    }

    // --- delivery plumbing ---------------------------------------------------

    /**
     * Swallows a real result, but only one this layer is serving. The delivery that
     * answers a callback is started in the same call that intercepts its class, so
     * a callback with no delivery is left alone instead of being silenced. That
     * matters because the bluetooth process layer can hand a result to a callback
     * whose app-side delivery never started; swallowing that one would leave the
     * app with nothing at all. CallScope is checked first, since it marks the
     * module's own delivery.
     */
    private fun swallow(chain: XposedInterface.Chain): Any? {
        val target = chain.thisObject
        if (!suppressing() || CallScope.active() || target == null || !deliveries.containsKey(target)) {
            return chain.proceed(chain.args.toTypedArray())
        }
        return null
    }

    private fun startDelivery(key: Any, deliver: () -> Unit) {
        stopDelivery(key)
        val runnable = object : Runnable {
            override fun run() {
                if (!suppressing()) {
                    // Stopping the simulation must stop the heartbeat too, instead
                    // of leaving a timer delivering nothing forever.
                    deliveries.remove(key)
                    return
                }
                try {
                    deliver()
                } catch (_: Throwable) {
                    // A delivery failure must not kill the heartbeat silently
                    // forever, so the next tick is still scheduled below.
                }
                if (deliveries[key] === this) handler.postDelayed(this, DELIVER_INTERVAL_MS)
            }
        }
        deliveries[key] = runnable
        handler.postDelayed(runnable, DELIVER_INTERVAL_MS)
    }

    private fun stopDelivery(key: Any) {
        deliveries.remove(key)?.let { handler.removeCallbacks(it) }
    }

    private fun hookStopScan(loader: ClassLoader, adapter: Class<*>) {
        HookUtils.findClassIfExists("android.bluetooth.le.BluetoothLeScanner", loader)?.let { scanner ->
            HookUtils.hookAllMethods(scanner, "stopScan") { chain ->
                chain.args.forEach { if (it != null) stopDelivery(it) }
                chain.proceed(chain.args.toTypedArray())
            }
        }
        HookUtils.hookAllMethods(adapter, "stopLeScan") { chain ->
            chain.args.forEach { if (it != null) stopDelivery(it) }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    // --- payload -------------------------------------------------------------

    /**
     * The fabricated results for the current fix, cached per beacon area and
     * jitter bucket: a heartbeat delivers repeatedly and each rebuild costs a
     * reflective parse, while the model only changes its levels once per bucket.
     */
    private fun payload(): List<Any>? {
        val loader = classLoader ?: return null
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || !cfg.mockBluetooth) return null
        if (!EnvGeo.hasFix(cfg.latitude, cfg.longitude)) return null

        val now = SystemClock.elapsedRealtime()
        val bucket = now / JITTER_BUCKET_MS
        val areaKey = FakeRadioEnvironment.beaconAreaKey(cfg.latitude, cfg.longitude)
        cache?.let {
            if (it.areaKey == areaKey && it.bucket == bucket) return it.results
        }

        val built = BleFabricator.scanResults(cfg, loader, now) ?: return null
        cache = PayloadCache(areaKey, bucket, built)
        return built
    }

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && cfg.mockBluetooth
    }
}
