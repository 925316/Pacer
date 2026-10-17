// BLE environment handling. A risk-control SDK can fingerprint the environment
// from bonded devices and from LE scan results, so while Bluetooth mocking is
// enabled the real bonded set is hidden and scan results are replaced with
// beacons from the deterministic model.
//
// Delivery design, chosen to avoid the two things that make this fragile
// elsewhere:
//   - No timer. The app's own scan cadence is kept by REWRITING each result that
//     arrives, rather than suppressing results and pushing synthetic ones on a
//     heartbeat. There is nothing to cancel, nothing to leak on stopScan, and no
//     re-entrancy: this module never invokes the callback itself.
//   - Stable identity. Each real device address is hashed onto one fabricated
//     beacon, so the same real device keeps the same fabricated address across
//     callbacks instead of appearing to jump between beacons.
// A build where no beacon can be constructed falls back to passing the real
// result through; see BleFabricator.
//
// NOT COVERED: the startScan overload that delivers results to a PendingIntent
// broadcast rather than to a ScanCallback. Rewriting those needs the system-side
// scan service (the bluetooth process), which is a separate layer.
package org.pacer.hook.hooks.env

import android.bluetooth.le.ScanResult
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.ConcurrentHashMap

object BleSpoofHooker {

    private const val SCAN_CALLBACK = "android.bluetooth.le.ScanCallback"
    private const val LEGACY_CALLBACK = "android.bluetooth.BluetoothAdapter\$LeScanCallback"

    /**
     * The beacon model re-jitters its levels on this period, so the payload cache
     * follows it instead of inventing its own.
     */
    private const val JITTER_BUCKET_MS = 1500L

    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()

    private var classLoader: ClassLoader? = null

    private data class PayloadCache(val areaKey: Long, val bucket: Long, val results: List<Any>)

    @Volatile private var cache: PayloadCache? = null

    fun install(loader: ClassLoader) {
        classLoader = loader
        val adapter = HookUtils.findClassIfExists("android.bluetooth.BluetoothAdapter", loader) ?: return
        hookBondedDevices(adapter)
        hookLeScanner(loader)
        hookLegacyLeScan(adapter)
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
            if (suppressing()) {
                for (arg in chain.args) {
                    if (arg != null && HookUtils.hasTypeByName(arg.javaClass, SCAN_CALLBACK)) {
                        hookScanCallback(arg.javaClass)
                    }
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookScanCallback(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) != null) return
        HookUtils.hookAllMethods(clazz, "onScanResult") { chain -> rewriteSingle(chain) }
        HookUtils.hookAllMethods(clazz, "onBatchScanResults") { chain -> rewriteBatch(chain) }
    }

    private fun rewriteSingle(chain: XposedInterface.Chain): Any? {
        val args = chain.args.toTypedArray()
        if (!suppressing()) return chain.proceed(args)
        val index = args.indexOfFirst { it is ScanResult }
        if (index < 0) return chain.proceed(args)
        val replacement = mappedResult(args[index] as ScanResult) ?: return chain.proceed(args)
        args[index] = replacement
        return chain.proceed(args)
    }

    private fun rewriteBatch(chain: XposedInterface.Chain): Any? {
        val args = chain.args.toTypedArray()
        if (!suppressing()) return chain.proceed(args)
        val index = args.indexOfFirst { it is List<*> }
        if (index < 0) return chain.proceed(args)
        val real = (args[index] as List<*>).filterIsInstance<ScanResult>().firstOrNull()
        val replacement = mappedResult(real) ?: return chain.proceed(args)
        args[index] = arrayListOf(replacement)
        return chain.proceed(args)
    }

    // --- deprecated LE scans -------------------------------------------------

    /**
     * startLeScan is deprecated since API 21, but an app still using it should
     * see the same virtual beacons as one on the current API, so its
     * (device, rssi, raw bytes) callback is rewritten from the same mapped
     * result instead of being suppressed.
     */
    private fun hookLegacyLeScan(adapter: Class<*>) {
        HookUtils.hookAllMethods(adapter, "startLeScan") { chain ->
            if (suppressing()) {
                for (arg in chain.args) {
                    if (arg != null && HookUtils.hasTypeByName(arg.javaClass, LEGACY_CALLBACK)) {
                        hookLegacyCallback(arg.javaClass)
                    }
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookLegacyCallback(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) != null) return
        HookUtils.hookAllMethods(clazz, "onLeScan") { chain ->
            val args = chain.args.toTypedArray()
            if (!suppressing() || args.size < 3) return@hookAllMethods chain.proceed(args)
            val mapped = mappedResult(null) ?: return@hookAllMethods chain.proceed(args)
            args[0] = mapped.device
            args[1] = mapped.rssi
            args[2] = mapped.scanRecord?.bytes ?: ByteArray(0)
            chain.proceed(args)
        }
    }

    // --- payload -------------------------------------------------------------

    /**
     * The fabricated results for the current fix, cached per beacon area and
     * jitter bucket: a scan delivers many results per second and each rebuild
     * costs a reflective parse, while the model only changes its levels once per
     * bucket.
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

    private fun mappedResult(real: ScanResult?): ScanResult? {
        val results = payload() ?: return null
        if (results.isEmpty()) return null
        val address = real?.device?.address
        val index = if (address == null) 0 else Math.floorMod(address.hashCode(), results.size)
        return results[index] as? ScanResult
    }

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && cfg.mockBluetooth
    }
}
