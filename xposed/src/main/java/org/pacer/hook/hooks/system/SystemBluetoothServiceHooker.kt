// Bluetooth process side of the BLE environment.
//
// WHY THIS LAYER EXISTS: the app-process BLE hooks only cover an app that talks to
// BluetoothLeScanner. Every other app - and every app whose scan goes through a
// path the app layer does not wrap - is answered by the Bluetooth process itself,
// which is where the scan service the framework calls into lives.
//
// STRUCTURE: the real results are swallowed on the CALLBACK object the app
// registered (the only point a scan result can reach an app through), and the
// module delivers its own beacons to the registered scanners on a heartbeat.
// Suppression and delivery are deliberately one decision: the callback a real
// result arrives for is ADOPTED as a delivery target before that result is
// dropped, so nothing is ever swallowed that is not then served. Adopting on
// arrival also covers a scanner that registered before this module was injected
// into an already running process, which a registration-only approach silently
// misses and then leaves with no results at all.
//
// IMPROVEMENTS over the reference, each one a defect recorded there:
//   - the stop side is hooked. The reference has no stopScan handling, so an app
//     that stops scanning keeps receiving synthetic beacons for as long as its
//     binder lives.
//   - the heartbeat runs only while there is a scanner to serve, and drops to a slow
//     tick while no simulation is active, instead of waking the Bluetooth process
//     every 1.2 s forever.
//   - delivery runs on a private thread rather than the Bluetooth main thread, since
//     each delivery is a blocking binder transaction into another process.
//   - binder death is one record per binder, so a repeated registration cannot pile
//     up death recipients.
//
// NOT COVERED, deliberately: bonded devices are left alone here. Hiding them is a
// user fingerprint rather than a location leak, and doing it device-wide would hide
// the user's own paired devices from Settings and from any app that pairs hardware.
// The app-process layer still hides them for a scoped app.
package org.pacer.hook.hooks.system

import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IInterface
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.BleFabricator
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.env.FakeRadioEnvironment
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.BinderDeaths
import org.pacer.hook.utils.CallScope
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object SystemBluetoothServiceHooker {

    /** The internal callback an app's scan registers with the Bluetooth stack. */
    private const val SCANNER_CALLBACK = "android.bluetooth.le.IScannerCallback"

    /** Which of these exists depends on the release; all are tried. */
    private val ENTRY_METHODS = arrayOf("registerScanner", "startScan", "registerAndStartScan")

    /** The deregistration spellings seen across releases. */
    private val STOP_METHODS = arrayOf(
        "stopScan",
        "unregisterScanner",
        "unregisterScannerCallback",
        "unregisterClient",
    )

    /** Roughly when a real scan first reports; the first delivery waits this long. */
    private const val FIRST_DELIVERY_MS = 400L

    /** Beacon advertising interval, matching the app-process layer. */
    private const val ACTIVE_INTERVAL_MS = 1200L

    /** While no simulation is active: only here to notice it being switched on. */
    private const val IDLE_INTERVAL_MS = 10_000L

    /** The beacon model re-jitters its levels on this period; the cache follows it. */
    private const val JITTER_BUCKET_MS = 1500L

    /** Delivery targets, keyed by the callback's binder (or the callback itself). */
    private val scanners = ConcurrentHashMap<Any, WeakReference<Any>>()

    /** scannerId -> scanner, learned from registration and from the first startScan. */
    private val scannerIds = ConcurrentHashMap<Int, Any>()

    private val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    private var classLoader: ClassLoader? = null

    private data class PayloadCache(val areaKey: Long, val bucket: Long, val results: List<Any>)

    @Volatile private var cache: PayloadCache? = null

    /**
     * Delivery gets its own thread: calling the app's callback is a blocking binder
     * transaction, and the Bluetooth process's main thread has work to do.
     */
    private val worker: HandlerThread by lazy { HandlerThread("pacer-bluetooth").apply { start() } }

    private val handler: Handler by lazy { Handler(worker.looper) }

    private val heartbeat = object : Runnable {
        override fun run() {
            try {
                dispatch()
            } catch (t: Throwable) {
                PacerLog.w("system-ble-heartbeat", t)
            }
            if (scanners.isNotEmpty()) {
                handler.postDelayed(this, if (suppressing()) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS)
            }
        }
    }

    fun install(classLoader: ClassLoader) {
        this.classLoader = classLoader
        val service = SystemClassLocator.locate(SystemComponent.BLUETOOTH_SCAN_SERVICE, classLoader)
        if (service == null) {
            PacerLog.i("bluetooth scan service not found; no bluetooth layer installed here")
            return
        }
        var hooked = hookEntries(service)
        hooked += hookStops(service)
        PacerLog.i("bluetooth process scan hooks installed (targets=$hooked)")
    }

    // --- registration --------------------------------------------------------

    private fun hookEntries(service: Class<*>): Int {
        var hooked = 0
        for (name in ENTRY_METHODS) {
            hooked += HookUtils.hookAllMethods(service, name) { chain ->
                // Proceed first so the scanner is registered even if anything below
                // misbehaves, then learn what this call revealed about it.
                val result = chain.proceed(chain.args.toTypedArray())
                // Registered even while no simulation is active, so a scanner that
                // is already waiting when the simulation starts is served at once
                // instead of never.
                val callback = chain.args.firstOrNull { isScannerCallback(it) }
                if (callback != null) remember(callback, chain.args, result)
                result
            }
        }
        return hooked
    }

    /**
     * The stop side. Which argument identifies the scanner differs by release - an
     * int scannerId, or the callback itself - so both are honoured and the original
     * call always proceeds.
     */
    private fun hookStops(service: Class<*>): Int {
        var hooked = 0
        for (name in STOP_METHODS) {
            hooked += HookUtils.hookAllMethods(service, name) { chain ->
                for (arg in chain.args) {
                    when {
                        arg is Int -> scannerIds.remove(arg)?.let { forget(it) }
                        arg is IBinder -> forget(arg)
                        isScannerCallback(arg) -> forget(keyOf(arg!!))
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
        return hooked
    }

    /**
     * Adopts [callback] as a delivery target and learns the scanner id this call
     * revealed, if any. Returns the key it is filed under.
     */
    private fun remember(callback: Any, args: List<Any?>, result: Any?): Any {
        val key = adopt(callback)
        for (arg in args) if (arg is Int) rememberId(arg, key)
        if (result is Int) rememberId(result, key)
        return key
    }

    private fun adopt(callback: Any): Any {
        val key = keyOf(callback)
        val previous = scanners.put(key, WeakReference(callback))
        hookCallbackClass(callback.javaClass)
        if (previous == null) {
            (key as? IBinder)?.let { binder -> BinderDeaths.watch(binder) { forget(key) } }
            startHeartbeat()
        }
        return key
    }

    private fun rememberId(value: Int, key: Any) {
        // Scanner ids start at 1, so zero is a status code, not an id.
        if (value > 0) scannerIds[value] = key
    }

    private fun keyOf(callback: Any): Any =
        (callback as? IInterface)?.asBinder() ?: (callback as? IBinder) ?: callback

    private fun forget(key: Any) {
        scanners.remove(key)
        scannerIds.entries.removeAll { it.value == key }
        (key as? IBinder)?.let { BinderDeaths.unwatch(it) }
    }

    private fun startHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, FIRST_DELIVERY_MS)
    }

    // --- the callback object -------------------------------------------------

    private fun hookCallbackClass(clazz: Class<*>) {
        if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return
        // The four places a scan result can reach an app through this object: the
        // two result deliveries, and the registration confirmation, which is where
        // the scanner id is reported on builds that do not return it.
        HookUtils.hookAllMethods(clazz, "onScanResult") { chain -> swallow(chain) }
        HookUtils.hookAllMethods(clazz, "onBatchScanResults") { chain -> swallow(chain) }
        HookUtils.hookAllMethods(clazz, "onScannerRegistered") { chain ->
            val ints = chain.args.filterIsInstance<Int>()
            ints.forEach { rememberId(it, keyOf(chain.thisObject)) }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    /**
     * Drops a real result. The caller is adopted first, so the module is guaranteed
     * to be able to serve whoever it just denied. CallScope is set while the module
     * delivers its own beacons, which is what lets those through.
     */
    private fun swallow(chain: XposedInterface.Chain): Any? {
        if (CallScope.active() || !suppressing()) return chain.proceed(chain.args.toTypedArray())
        chain.thisObject?.let { adopt(it) }
        return null
    }

    // --- delivery ------------------------------------------------------------

    private fun dispatch() {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || !cfg.mockBluetooth || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) return
        val results = payload(cfg) ?: return
        for ((key, ref) in scanners) {
            if (key is IBinder && !key.isBinderAlive) {
                forget(key)
                continue
            }
            val callback = ref.get()
            if (callback == null) {
                forget(key)
                continue
            }
            deliver(callback, results)
        }
    }

    private fun deliver(callback: Any, results: List<Any>) {
        try {
            CallScope.enter {
                var sent = 0
                for (result in results) if (callOnScanResult(callback, result)) sent++
                if (sent > 0) callOnBatchScanResults(callback, ArrayList(results))
            }
        } catch (t: Throwable) {
            PacerLog.w("system-ble-deliver", t)
        }
    }

    /**
     * The internal callback takes (ScanResult); a build that hands the app's own
     * callback over instead takes (int callbackType, ScanResult). Both are tried,
     * and a callback with neither shape is left alone rather than guessed at.
     */
    private fun callOnScanResult(callback: Any, result: Any): Boolean {
        for (method in callback.javaClass.methods) {
            if (method.name != "onScanResult") continue
            val types = method.parameterTypes
            val args: Array<Any?> = when {
                types.size == 1 && types[0].isInstance(result) -> arrayOf(result)
                types.size == 2 && types[0] == Int::class.javaPrimitiveType && types[1].isInstance(result) ->
                    arrayOf(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)

                else -> continue
            }
            return try {
                method.invoke(callback, *args)
                true
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    private fun callOnBatchScanResults(callback: Any, results: List<Any>) {
        val method = callback.javaClass.methods.firstOrNull {
            it.name == "onBatchScanResults" && it.parameterCount == 1 && it.parameterTypes[0].isInstance(results)
        } ?: return
        try {
            method.invoke(callback, results)
        } catch (_: Throwable) {
        }
    }

    // --- payload -------------------------------------------------------------

    /**
     * The fabricated results for the current fix, cached per beacon area and jitter
     * bucket: the heartbeat delivers repeatedly, each rebuild is a reflective parse,
     * and the model only changes its levels once per bucket.
     */
    private fun payload(cfg: PacerRuntimeConfig.Snapshot): List<Any>? {
        val loader = classLoader ?: return null
        val now = SystemClock.elapsedRealtime()
        val bucket = now / JITTER_BUCKET_MS
        // The cache key follows the CURRENT position, not the configured start, or a run
        // that moves on would keep serving the origin's beacon set from the cache.
        val fix = CurrentFix.of(cfg)
        val areaKey = FakeRadioEnvironment.beaconAreaKey(fix.lat, fix.lon)
        cache?.let {
            if (it.areaKey == areaKey && it.bucket == bucket) return it.results
        }
        val built = BleFabricator.scanResults(cfg, loader, now) ?: return null
        cache = PayloadCache(areaKey, bucket, built)
        return built
    }

    // --- helpers -------------------------------------------------------------

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && cfg.mockBluetooth
    }

    /**
     * Whether [arg] is the object a scan registers to receive results. The internal
     * interface has been renamed by jarjar in some builds, so the shape is checked
     * as well as the name.
     */
    private fun isScannerCallback(arg: Any?): Boolean {
        if (arg == null) return false
        val clazz = arg.javaClass
        if (HookUtils.hasTypeByName(clazz, SCANNER_CALLBACK)) return true
        if (clazz.name.contains("IScannerCallback")) return true
        return clazz.methods.any { it.name == "onScanResult" }
    }
}
