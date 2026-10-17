// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Phone process side of the cell environment.
//
// WHY THIS LAYER EXISTS, from field evidence: the mature GPL reference
// implementation marks cell fabrication as working only in the PHONE process,
// while its app-process cell hook still carries an open crash report. The app
// process hooker can only speak for the one app Pacer is scoped into; everything
// else - system apps, direct binder clients, an app the user did not scope -
// reaches the telephony stack through PhoneInterfaceManager, which is this
// process. Answering here is therefore the wider and, per that evidence, the more
// reliable path.
//
// Both layers feed the same CellPayload, so the two views describe one device.
package org.pacer.hook.hooks.system

import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CellLocationShapes
import org.pacer.hook.hooks.env.CellPayload
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.reflect.Method

object SystemTelephonyServiceHooker {

    /**
     * gsm.operator.numeric carries the operator the device is camped on. Read
     * through the property service because this process has no Context, and
     * because inventing an operator would contradict the untouched
     * getNetworkOperator() that any app can read.
     */
    private const val OPERATOR_PROPERTY = "gsm.operator.numeric"

    /**
     * The callback an app passes to requestCellInfoUpdate arrives here as the
     * app's REMOTE proxy for the internal interface. Matching on the internal name
     * is what finds it; the public TelephonyManager.CellInfoCallback name is kept
     * as a fallback for builds that pass a local one.
     */
    private const val INTERNAL_CELL_INFO_CALLBACK = "android.telephony.ICellInfoCallback"
    private const val PUBLIC_CELL_INFO_CALLBACK = "android.telephony.TelephonyManager\$CellInfoCallback"

    /** Both spellings: the second was added later and returns a result code. */
    private val REQUEST_METHODS = arrayOf(
        "requestCellInfoUpdate",
        "requestCellInfoUpdateWithWorkSource",
    )

    fun install(classLoader: ClassLoader) {
        val manager = SystemClassLocator.locate(SystemComponent.TELEPHONY_PHONE_MANAGER, classLoader) ?: return
        var hooked = 0

        hooked += HookUtils.hookAllMethods(manager, "getAllCellInfo") { chain ->
            if (!serving()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            // Any failure must degrade to "no cells", never to an exception in the
            // telephony stack: this process serves every app on the device.
            payload(classLoader) ?: ArrayList<Any>()
        }

        hooked += HookUtils.hookAllMethods(manager, "getCellLocation") { chain ->
            // Never the real serving cell: the answer is the same serving cell
            // getAllCellInfo reports, via the shared adapter, or null, which is what a
            // device with no service reports. One payload, one formula, on both processes.
            if (!serving()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val identity = CellLocationShapes.servingIdentity(payload(classLoader))
            if (identity == null) null
            else CellLocationShapes.fit(identity, (chain.executable as? Method)?.returnType)
        }

        for (method in REQUEST_METHODS) {
            hooked += HookUtils.hookAllMethods(manager, method) { chain ->
                if (!serving()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                if (answerCallback(chain, classLoader)) null
                else chain.proceed(chain.args.toTypedArray())
            }
        }

        PacerLog.i("phone process cell hooks installed (targets=$hooked)")
    }

    /**
     * Answers the caller's callback directly and skips the original.
     *
     * The argument is the app's remote proxy, so invoking its onCellInfo() is
     * itself the reverse IPC that delivers the list to the app: no wrapper is
     * needed, and none is used. An earlier version of this file claimed a dynamic
     * proxy was required; that was wrong, and a wrapper would only have to
     * reimplement binder death and threading for no benefit.
     *
     * Returning false leaves the original call in place, so a build whose callback
     * cannot be found or invoked still behaves exactly as it did before.
     */
    private fun answerCallback(chain: XposedInterface.Chain, classLoader: ClassLoader): Boolean {
        val callback = chain.args.firstOrNull { isCellInfoCallback(it) } ?: return false
        val onCellInfo = callback.javaClass.methods
            .firstOrNull { it.name == "onCellInfo" && it.parameterCount == 1 } ?: return false
        return try {
            // An empty list rather than the real answer: the async path must not
            // leak the real surroundings just because fabrication failed.
            onCellInfo.invoke(callback, payload(classLoader) ?: ArrayList<Any>())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCellInfoCallback(arg: Any?): Boolean {
        if (arg == null) return false
        return HookUtils.hasTypeByName(arg.javaClass, INTERNAL_CELL_INFO_CALLBACK) ||
            HookUtils.hasTypeByName(arg.javaClass, PUBLIC_CELL_INFO_CALLBACK)
    }

    private fun payload(classLoader: ClassLoader): List<Any>? =
        runCatching { CellPayload.payload(HookUtils.systemProperty(OPERATOR_PROPERTY), classLoader) }.getOrNull()

    private fun serving(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && EnvGeo.hasFix(cfg.latitude, cfg.longitude)
    }
}
