// Cell environment handling. A moving fix that keeps reporting the real
// neighbourhood towers is an inconsistency a risk-control SDK can detect, so the
// real cell data must not be exposed while a simulation is active.
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
// All four are answered with the empty result a device with no service produces,
// so the answers agree with each other as well as with the simulated fix.
//
// LIMITATION: this hooker suppresses cell data instead of fabricating it.
// Building real CellInfo / CellIdentity / CellSignalStrength objects off the
// phone stack is fragile: their constructors are absent from the public SDK
// (verified in CI by HookTargetContractTest), the identity fields are final on
// most builds, and many OEMs mutate the returned objects in place and NPE when
// nested signal objects are missing. Suppressing is the safe choice: it never
// fabricates a malformed object and never contradicts the simulated coordinate.
//
// The deterministic tower model in FakeRadioEnvironment is intentionally not
// wired to a hook yet; it is kept for a future, per-OEM-safe delivery path.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.ConcurrentHashMap

object CellSpoofHooker {

    private const val CELL_INFO_CALLBACK = "android.telephony.TelephonyManager\$CellInfoCallback"
    private const val TELEPHONY_CALLBACK = "android.telephony.TelephonyCallback"

    /** Concrete callback classes already intercepted, so each is hooked once. */
    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()

    fun install(classLoader: ClassLoader) {
        val telephonyManager =
            HookUtils.findClassIfExists("android.telephony.TelephonyManager", classLoader) ?: return
        hookAllCellInfo(telephonyManager)
        hookCellLocation(telephonyManager)
        hookNeighboringCellInfo(telephonyManager)
        hookCellInfoUpdate(telephonyManager)
        hookTelephonyCallbackRegistration(telephonyManager)
        PacerLog.i("cell environment hooks installed (suppress-only)")
    }

    private fun hookAllCellInfo(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "getAllCellInfo") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            ArrayList<Any>()
        }
    }

    private fun hookCellLocation(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "getCellLocation") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            null
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
     * callback it was given is intercepted, so whatever the system eventually
     * delivers carries no cells. The callback type is matched by name because
     * the argument order differs between the two overloads.
     */
    private fun hookCellInfoUpdate(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "requestCellInfoUpdate") { chain ->
            if (suppressing()) {
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
            chain.proceed(emptyFirstListArg(chain.args))
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
            chain.proceed(emptyFirstListArg(chain.args))
        }
        HookUtils.hookAllMethods(clazz, "onCellLocationChanged") { chain ->
            if (suppressing()) return@hookAllMethods null
            chain.proceed(chain.args.toTypedArray())
        }
    }

    /**
     * Replaces the first argument (a list of cells) with an empty one. The state
     * is re-checked at delivery time, not at registration time: a simulation can
     * be stopped between the two.
     */
    private fun emptyFirstListArg(args: List<Any?>): Array<Any?> {
        val out = args.toTypedArray()
        if (suppressing() && out.isNotEmpty()) out[0] = ArrayList<Any>()
        return out
    }

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && EnvGeo.hasFix(cfg.latitude, cfg.longitude)
    }
}
