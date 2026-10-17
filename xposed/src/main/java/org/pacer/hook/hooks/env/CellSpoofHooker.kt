// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
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

    /** A device occupies one coarse area at a time, so one entry is enough. */
    private data class AreaCache(
        val areaKey: Long,
        val seed: Long,
        val operator: String,
        val cells: List<Any>,
    )

    @Volatile private var cache: AreaCache? = null

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
            // Null matches the empty list above: a device that sees no cells has
            // no serving cell location either.
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
            if (suppressing()) return@hookAllMethods null
            chain.proceed(chain.args.toTypedArray())
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

    // ---- payload ----

    /**
     * Fabricated cells for the current fix, or null when the caller must
     * suppress. Cached per coarse area, seed and operator, because the tower set
     * only changes at an area boundary while building it costs a dozen
     * reflective constructions.
     */
    private fun cellsFor(): List<Any>? {
        if (!suppressing()) return null
        val cfg = PacerRuntimeConfig.snapshot()
        val seed = EnvGeo.sessionSeed(cfg.startTimestamp)
        val areaKey = FakeRadioEnvironment.cellAreaKey(cfg.latitude, cfg.longitude)
        val operator = realOperator()

        cache?.let {
            if (it.areaKey == areaKey && it.seed == seed && it.operator == operator) return it.cells
        }

        // A device without a usable SIM has no cells to report; inventing some
        // would contradict getSimState().
        if (!simUsable()) return null

        val model = FakeRadioEnvironment.cells(cfg.latitude, cfg.longitude, seed)
        val built = CellFabricator.fabricate(onRealOperator(model, operator), classLoader) ?: return null
        cache = AreaCache(areaKey, seed, operator, built)
        return built
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

    /**
     * Puts the fabricated cells on the operator the device is really camped on.
     * Everything else about a cell (area, identity, radio code, signal) is what
     * changes as a device moves, so nothing is lost by taking the operator from
     * the device instead of from the model.
     */
    private fun onRealOperator(
        cells: List<FakeRadioEnvironment.Cell>,
        operator: String,
    ): List<FakeRadioEnvironment.Cell> {
        val parsed = parseOperator(operator) ?: return cells
        return cells.map { it.copy(mcc = parsed.first, mnc = parsed.second) }
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

    /** "46001" -> (460, 1). Null when the string is absent or malformed. */
    private fun parseOperator(raw: String): Pair<Int, Int>? {
        if (raw.length < 5 || !raw.all { it in '0'..'9' }) return null
        val mcc = raw.substring(0, 3).toIntOrNull() ?: return null
        val mnc = raw.substring(3).toIntOrNull() ?: return null
        if (mcc <= 0) return null
        return mcc to mnc
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
