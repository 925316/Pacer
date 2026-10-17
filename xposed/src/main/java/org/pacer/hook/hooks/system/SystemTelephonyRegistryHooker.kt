// system_server side of the cell PUSH path.
//
// Pacer answers cell queries in three places, and this is the third:
//   1. hooks/env/CellSpoofHooker.kt - an app that calls TelephonyManager itself,
//      but only for an app inside the module's scope.
//   2. hooks/system/SystemTelephonyServiceHooker.kt - synchronous calls into
//      PhoneInterfaceManager, answered in the phone process.
//   3. this file - the asynchronous path. An app registers a telephony callback
//      and the platform later delivers cell changes to it from TelephonyRegistry
//      in system_server. A risk-control SDK that registers such a callback
//      receives the REAL cell here however well layers 1 and 2 answer, because
//      the value never passes through either of them. This layer rewrites what
//      the registry delivers to every registered callback.
//
// THE TRAP: TelephonyRegistry holds the app's IPhoneStateListener binder and
// invokes it directly. The object inside this process is therefore the app's
// remote proxy, so rewriting argument 0 just before the proxy marshals it is
// enough; no callback is reimplemented and no registration is altered. Two
// things about that are non-obvious:
//   - There is no single registration method. The listen(~) family gained
//     overloads across releases (notifyNow, subId, feature), so it is matched by
//     NAME PREFIX. An exact-name helper would silently cover only one release.
//   - Hooking the listener is not sufficient on its own: the registry's own call
//     sites into it can be inlined by the JIT, and an inlined call never reaches
//     the hook. The registry is therefore deoptimized so the runtime stops
//     inlining it. This is required for the layer to work at all, not tuning.
//
// Everything runs in system_server, so no interceptor may let a Throwable escape:
// a broken hook here can take the whole telephony stack down with it. A failed
// transform always falls through to the original arguments, and the notification
// itself is always allowed to proceed because it has effects beyond the value
// Pacer cares about.
//
// DELIBERATELY ABSENT, and not dead code to be added back:
//   - Per-caller uid/package filtering. It would mean walking the registry's
//     mRecords list to find who registered each callback and answering only that
//     caller. Pacer's system layer answers EVERYONE while a simulation is active
//     (see SystemWifiServiceHooker), so that walk - and its precondition on an
//     mRecords field - buys nothing here and is not implemented.
//   - A hot-reload close()/unhook protocol. Pacer's hookers have none.
package org.pacer.hook.hooks.system

import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CellPayload
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object SystemTelephonyRegistryHooker {

    /**
     * gsm.operator.numeric, read the same way the phone-process hooker reads it.
     * The fabricated cells are put on the operator the device is really camped
     * on; a fabricated operator would be a one-string comparison against the
     * untouched getNetworkOperator() to notice.
     */
    private const val OPERATOR_PROPERTY = "gsm.operator.numeric"

    /**
     * The internal listener the registry invokes. The object here is the app's
     * remote proxy for it, and matching on the internal name is what finds that
     * proxy regardless of the argument order of the registration overload.
     */
    private const val PHONE_STATE_LISTENER = "com.android.internal.telephony.IPhoneStateListener"

    private const val LISTEN_PREFIX = "listen"
    private const val CALLBACK_CELL_INFO = "onCellInfoChanged"
    private const val CALLBACK_CELL_LOCATION = "onCellLocationChanged"

    /** Compared by name so no class has to be loaded to recognize the shapes. */
    private const val BUNDLE_CLASS = "android.os.Bundle"
    private const val CELL_LOCATION_CLASS = "android.telephony.CellLocation"

    /** Callback classes already intercepted, so each class is hooked once. */
    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()

    @Volatile private var classLoader: ClassLoader? = null

    fun install(classLoader: ClassLoader) {
        this.classLoader = classLoader
        val registry = SystemClassLocator.locate(SystemComponent.TELEPHONY_REGISTRY, classLoader)
        if (registry == null) {
            PacerLog.i("telephony registry not resolved; cell push path left untouched")
            return
        }

        val hooked = hookListenMethods(registry)
        deoptimizeRegistry(registry)
        PacerLog.i("system telephony registry cell hooks installed (targets=$hooked)")
    }

    // --- registration --------------------------------------------------------

    /**
     * Every declared, non-abstract listen* overload. A prefix test is used
     * because the family keeps growing: a hard-coded signature list would
     * silently stop covering one release.
     */
    private fun hookListenMethods(registry: Class<*>): Int {
        var hooked = 0
        for (method in registry.declaredMethods) {
            if (!method.name.startsWith(LISTEN_PREFIX)) continue
            if (Modifier.isAbstract(method.modifiers)) continue
            try {
                HookUtils.module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? = register(chain)
                })
                hooked++
            } catch (t: Throwable) {
                // One overload failing must not cost the others: the releases
                // differ in which overload exists and which can be hooked.
                PacerLog.i("telephony registry listen hook failed for ${method.name}: ${t.message}")
            }
        }
        return hooked
    }

    /**
     * A registration call. The callback is identified by type, not by argument
     * index, because the overloads put it in different positions.
     *
     * Discovery failures are swallowed and the registration still proceeds: the
     * framework owns the record, and a layer that cannot find the callback must
     * not make registration behave differently than it did before.
     */
    private fun register(chain: XposedInterface.Chain): Any? {
        try {
            val callback = chain.args.filterNotNull().firstOrNull {
                HookUtils.hasTypeByName(it.javaClass, PHONE_STATE_LISTENER)
            }
            if (callback != null) hookCallbackClass(callback.javaClass)
        } catch (t: Throwable) {
            PacerLog.i("telephony registry callback discovery failed: ${t.message}")
        }
        return chain.proceed(chain.args.toTypedArray())
    }

    // --- callback delivery ---------------------------------------------------

    /**
     * Hooks the two delivery methods on the concrete callback class. The target
     * is the app's remote proxy, so its methods are the last point before the
     * list or location is written to the binder parcel.
     */
    private fun hookCallbackClass(callbackClass: Class<*>) {
        if (hookedCallbacks.putIfAbsent(callbackClass, true) != null) return

        HookUtils.hookAllMethods(callbackClass, CALLBACK_CELL_INFO) { chain ->
            // An empty list rather than the real one when fabrication is
            // unavailable: the push path must not leak the real surroundings
            // just because Pacer could not invent a replacement.
            rewrite(chain) { if (serving()) cellInfoList() ?: ArrayList<Any>() else null }
        }
        HookUtils.hookAllMethods(callbackClass, CALLBACK_CELL_LOCATION) { chain ->
            rewrite(chain) { if (serving()) cellLocation(chain) else null }
        }
    }

    /**
     * Runs [transform] and hands the result to the notification.
     *
     * The original is always allowed to proceed: these are notifications whose
     * other effects (caching, bookkeeping, downstream listeners) must still
     * happen, so this never returns null to skip them. A transform failure leaves
     * argument 0 untouched instead of aborting the delivery.
     */
    private fun rewrite(chain: XposedInterface.Chain, transform: () -> Any?): Any? {
        // proceed() stays outside the try so an exception the original
        // notification itself throws is not retried as if it were a rewrite bug.
        val args = try {
            val current = chain.args.toTypedArray()
            val replacement = transform()
            if (replacement != null && current.isNotEmpty()) current[0] = replacement
            current
        } catch (t: Throwable) {
            PacerLog.i("telephony registry callback rewrite failed: ${t.message}")
            chain.args.toTypedArray()
        }
        return chain.proceed(args)
    }

    /**
     * The fabricated list, or null when fabrication is unavailable. The caller
     * turns null into an empty list; it is never the real list.
     */
    private fun cellInfoList(): List<Any>? =
        try {
            CellPayload.payload(HookUtils.systemProperty(OPERATOR_PROPERTY), classLoader)
        } catch (t: Throwable) {
            PacerLog.i("telephony registry payload failed: ${t.message}")
            null
        }

    /**
     * The serving cell identity adapted to whatever the callback's parameter 0
     * declares. Returns null - keep the original - when no identity can be built
     * or the parameter type is not one the notification historically carries.
     */
    private fun cellLocation(chain: XposedInterface.Chain): Any? {
        val identity = servingIdentity() ?: return null
        val parameter = (chain.executable as? Method)?.parameterTypes?.firstOrNull() ?: return null
        return when {
            parameter.name == BUNDLE_CLASS -> notifierBundle(identity)
            parameter.name == CELL_LOCATION_CLASS ||
                HookUtils.hasTypeByName(parameter, CELL_LOCATION_CLASS) -> asCellLocation(identity)
            // A CellIdentity (or any other shape): hand the identity over as is.
            else -> identity
        }
    }

    /**
     * The identity of the fabricated serving cell: the first entry that reports
     * itself registered, falling back to the first entry when none does. The
     * payload puts the camping cell first, so the fallback still picks a
     * plausible one.
     */
    private fun servingIdentity(): Any? {
        val cells = cellInfoList() ?: return null
        val serving = cells.firstOrNull { read(it, "isRegistered") == true }
            ?: cells.firstOrNull()
            ?: return null
        return read(serving, "getCellIdentity")
    }

    // --- location shapes -----------------------------------------------------

    /**
     * A cell-location notification Bundle.
     *
     * The platform's own writer is tried first because it owns the mapping
     * between a cell type and the keys the framework's client side reads back.
     * It is hidden, and on some releases it lives on the identity while on others
     * only on the identity's CellLocation view, so both are tried. When neither
     * wrote anything, the legacy lac/cid/psc keys are written directly; -1 is the
     * value the telephony classes themselves use for an unavailable field.
     */
    private fun notifierBundle(identity: Any): Bundle {
        val bundle = Bundle()
        if (writeWithPlatform(identity, bundle)) return bundle
        writeLegacyKeys(bundle, identity)
        return bundle
    }

    private fun writeWithPlatform(identity: Any, bundle: Bundle): Boolean {
        for (target in listOfNotNull(identity, asCellLocation(identity))) {
            try {
                HookUtils.callMethod(target, "fillInNotifierBundle", bundle)
                if (wroteKeys(bundle)) return true
            } catch (_: Throwable) {
                // Absent or hidden: try the other target, then the legacy keys.
            }
        }
        return false
    }

    private fun wroteKeys(bundle: Bundle): Boolean =
        bundle.containsKey("lac") || bundle.containsKey("cid") || bundle.containsKey("psc")

    private fun writeLegacyKeys(bundle: Bundle, identity: Any) {
        bundle.putInt("lac", intOf(identity, "getTac", "getLac"))
        bundle.putInt("cid", intOf(identity, "getCi", "getCid"))
        bundle.putInt("psc", intOf(identity, "getPsc"))
    }

    /** First readable getter wins; -1 when none exists, matching the platform. */
    private fun intOf(identity: Any, vararg getters: String): Int {
        for (getter in getters) {
            (read(identity, getter) as? Int)?.let { return it }
        }
        return -1
    }

    /** The identity's CellLocation view. Hidden, hence reflective and nullable. */
    private fun asCellLocation(identity: Any): Any? =
        try {
            HookUtils.callMethod(identity, "asCellLocation")
        } catch (_: Throwable) {
            null
        }

    // --- helpers -------------------------------------------------------------

    /**
     * Zero-argument getter lookup, null when absent or unreadable. Used for the
     * public accessors on the telephony value classes; the hidden writer goes
     * through HookUtils.callMethod instead.
     */
    private fun read(target: Any, getter: String): Any? =
        try {
            HookUtils.noArgAccessor(target.javaClass, getter)?.invoke(target)
        } catch (_: Throwable) {
            null
        }

    /**
     * Forces the registry out of compiled code so its call sites into the
     * callback are not inlined past the listener hook.
     *
     * The whole declared surface is deoptimized rather than only the listen*
     * methods: the actual delivery happens in the registry's cell-notification
     * methods, whose names differ across releases and cannot be listed. A refused
     * deoptimize is only logged; unlike the reference layer this must not abandon
     * the rest of the install, because the value of the layer is not all or
     * nothing.
     */
    private fun deoptimizeRegistry(registry: Class<*>) {
        for (method in registry.declaredMethods) {
            if (Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) continue
            try {
                if (!HookUtils.module.deoptimize(method)) {
                    PacerLog.i("telephony registry deoptimize refused for ${method.name}")
                }
            } catch (t: Throwable) {
                PacerLog.i("telephony registry deoptimize failed for ${method.name}: ${t.message}")
            }
        }
    }

    /**
     * True while a simulation is running. An idle module must not change what any
     * app receives, so every interceptor checks this before touching an argument.
     */
    private fun serving(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && EnvGeo.hasFix(cfg.latitude, cfg.longitude)
    }
}
