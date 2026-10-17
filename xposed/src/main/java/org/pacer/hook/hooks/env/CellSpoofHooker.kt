// Cell environment handling. The cell view is derived from the same
// deterministic tower model as the WiFi and BLE views, so every radio answer
// tells one story that agrees with the simulated fix.
//
// EVERY read path has to be covered, not just the obvious one. Android offers
// four independent ways to hand an app cell data, and one uncovered path leaks
// the real environment at a steady cadence:
//   1. TelephonyManager.getAllCellInfo()          - the classic polling call
//   2. TelephonyManager.requestCellInfoUpdate()   - the Android 10+ async
//      replacement, which SDKs use precisely to avoid polling getAllCellInfo
//      (the Tencent location SDK is reported to call it about every 30 seconds)
//   3. getCellLocation() / getNeighboringCellInfo() - the older calls
//   4. callback delivery: registerTelephonyCallback() on Android 12+, and the
//      CellInfoCallback handed to requestCellInfoUpdate
//
// Two things are answered from the DEVICE rather than from the model, because
// contradicting them would be free to detect:
//   - The operator. The fabricated cells are put on the operator the device is
//     really camped on (the untouched getNetworkOperator()), not on the first
//     entry of the model's regional table. A user on China Unicom reporting
//     China Mobile cells is a one-string comparison to notice.
//   - The transport type. getNetworkType / getDataNetworkType report the family
//     actually fabricated, so a 5G handset cannot end up saying "NR" while every
//     cell it lists is LTE.
//
// Fabrication is attempted first and suppression is the fallback; see
// CellFabricator for why a failure there is safe.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object CellSpoofHooker {

    private const val CELL_INFO_CALLBACK = "android.telephony.TelephonyManager\$CellInfoCallback"
    private const val TELEPHONY_CALLBACK = "android.telephony.TelephonyCallback"

    /** TelephonyManager.NETWORK_TYPE_LTE. */
    private const val NETWORK_TYPE_LTE = 13

    private const val SIM_STATE_READY = 5
    private const val SIM_STATE_LOADED = 10

    /** Concrete callback classes already intercepted, so each is hooked once. */
    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()

    private var classLoader: ClassLoader? = null

    /**
     * Last TelephonyManager seen in this process. Needed because the callback
     * objects the system hands back are not the manager, while reading the real
     * operator has to go through one.
     */
    @Volatile private var telephony: Any? = null

    fun install(loader: ClassLoader) {
        classLoader = loader
        val telephonyManager =
            HookUtils.findClassIfExists("android.telephony.TelephonyManager", loader) ?: return
        hookAllCellInfo(telephonyManager)
        hookCellLocation(telephonyManager)
        hookNeighboringCellInfo(telephonyManager)
        hookCellInfoUpdate(telephonyManager)
        hookTelephonyCallbackRegistration(telephonyManager)
        hookNetworkType(telephonyManager)
        hookServiceStateCellIdentity(loader)
        PacerLog.i("cell environment hooks installed")
    }

    // ---- the four read paths ----

    private fun hookAllCellInfo(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "getAllCellInfo") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            telephony = chain.thisObject
            cellsFor() ?: ArrayList<Any>()
        }
    }

    private fun hookCellLocation(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "getCellLocation") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            // The serving cell getAllCellInfo reports, in the shape this getter
            // declares. Answering null here while getAllCellInfo hands out cells was
            // the kind of disagreement an app can see for itself: on a device with
            // service, one of the two has to be lying.
            val identity = CellLocationShapes.servingIdentity(cellsFor()) ?: return@hookAllMethods null
            CellLocationShapes.fit(identity, (chain.executable as? Method)?.returnType)
        }
    }

    /** Removed from the public SDK in API 28 but still present on some builds. */
    private fun hookNeighboringCellInfo(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "getNeighboringCellInfo") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            ArrayList<Any>()
        }
    }

    /**
     * The request is left registered so the system contract is intact, but the
     * callback it was given is intercepted, so what the system eventually
     * delivers is replaced by the fabricated list. The callback type is matched
     * by name because the argument order differs between the two overloads.
     */
    private fun hookCellInfoUpdate(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "requestCellInfoUpdate") { chain ->
            if (suppressing()) {
                telephony = chain.thisObject
                for (arg in chain.args) {
                    if (arg != null && HookUtils.hasTypeByName(arg.javaClass, CELL_INFO_CALLBACK)) {
                        hookCellInfoCallback(arg.javaClass)
                    }
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookCellInfoCallback(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) != null) return
        HookUtils.hookAllMethods(clazz, "onCellInfo") { chain ->
            chain.proceed(replaceFirstListArg(chain.args))
        }
    }

    /**
     * Android 12+ replacement for listen(PhoneStateListener, int). The callback
     * object declares the events it wants and that cannot be narrowed after
     * registration, so the concrete callback class's delivery methods are
     * intercepted instead of the registration mask.
     */
    private fun hookTelephonyCallbackRegistration(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "registerTelephonyCallback") { chain ->
            if (suppressing()) {
                telephony = chain.thisObject
                for (arg in chain.args) {
                    if (arg != null && HookUtils.hasTypeByName(arg.javaClass, TELEPHONY_CALLBACK)) {
                        hookTelephonyCallback(arg.javaClass)
                    }
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookTelephonyCallback(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) != null) return
        HookUtils.hookAllMethods(clazz, "onCellInfoChanged") { chain ->
            chain.proceed(replaceFirstListArg(chain.args))
        }
        HookUtils.hookAllMethods(clazz, "onCellLocationChanged") { chain ->
            val identity = if (suppressing()) CellLocationShapes.servingIdentity(cellsFor()) else null
            val parameter = (chain.executable as? Method)?.parameterTypes?.firstOrNull()
            val replacement = identity?.let { CellLocationShapes.fit(it, parameter) }
            if (replacement == null) {
                // Silence, not the original value: this one may be the REAL serving
                // cell, because the system_server layer that would have rewritten it
                // is a separate entry in the module scope. A push that cannot be
                // replaced must not pass through.
                return@hookAllMethods null
            }
            val args = chain.args.toTypedArray()
            if (args.isNotEmpty()) args[0] = replacement
            chain.proceed(args)
        }
    }

    /** Keeps the reported transport type in step with the fabricated cells. */
    private fun hookNetworkType(telephonyManager: Class<*>) {
        for (method in arrayOf("getNetworkType", "getDataNetworkType")) {
            HookUtils.hookAllMethods(telephonyManager, method) { chain ->
                if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                NETWORK_TYPE_LTE
            }
        }
    }

    /**
     * Android 10+ carries the camping cell identity inside ServiceState, behind a
     * getter that is entirely separate from getAllCellInfo(). Left alone it hands
     * the app the REAL serving cell while every other cell answer is fabricated;
     * this path is easy to miss. The identity is taken from the same payload, so
     * ServiceState and getAllCellInfo agree.
     */
    private fun hookServiceStateCellIdentity(loader: ClassLoader) {
        val serviceState = HookUtils.findClassIfExists("android.telephony.ServiceState", loader) ?: return
        HookUtils.hookAllMethods(serviceState, "getCellIdentity") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            CellLocationShapes.servingIdentity(cellsFor()) ?: chain.proceed(chain.args.toTypedArray())
        }
    }

    // ---- payload ----

    /**
     * Fabricated cells for the current fix, or null when the caller must
     * suppress. The payload itself is shared with the phone-process hooker, so
     * both describe the same device; only what this process alone can answer
     * stays here.
     */
    private fun cellsFor(): List<Any>? {
        if (!suppressing()) return null
        // A device without a usable SIM has no cells to report; inventing some
        // would contradict getSimState().
        if (!simUsable()) return null
        return CellPayload.payload(realOperator(), classLoader)
    }

    /**
     * Replaces the first argument (a list of cells) with the fabricated one.
     * When no simulation is running the argument is passed through untouched:
     * a module that is idle must not change what the app receives.
     */
    private fun replaceFirstListArg(args: List<Any?>): Array<Any?> {
        val out = args.toTypedArray()
        if (out.isEmpty() || !suppressing()) return out
        out[0] = cellsFor() ?: ArrayList<Any>()
        return out
    }

    /** Raw getNetworkOperator(), or "" when unreadable. Never hooked, never faked. */
    private fun realOperator(): String {
        val manager = telephony ?: return ""
        return try {
            HookUtils.noArgAccessor(manager.javaClass, "getNetworkOperator")?.invoke(manager) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** Unknown counts as usable: better a plausible cell than a silent removal. */
    private fun simUsable(): Boolean {
        val manager = telephony ?: return true
        val state = HookUtils.intAccessor(manager, "getSimState") ?: return true
        return state == SIM_STATE_READY || state == SIM_STATE_LOADED
    }

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && EnvGeo.hasFix(cfg.latitude, cfg.longitude)
    }
}
