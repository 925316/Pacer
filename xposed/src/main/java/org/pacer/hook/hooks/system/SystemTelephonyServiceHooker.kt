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

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CellPayload
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.hooks.system.vendor.SystemClassLocator
import org.pacer.hook.hooks.system.vendor.SystemComponent
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object SystemTelephonyServiceHooker {

    /**
     * gsm.operator.numeric carries the operator the device is camped on. Read
     * through the property service because this process has no Context, and
     * because inventing an operator would contradict the untouched
     * getNetworkOperator() that any app can read.
     */
    private const val OPERATOR_PROPERTY = "gsm.operator.numeric"

    fun install(classLoader: ClassLoader) {
        val manager = SystemClassLocator.locate(SystemComponent.TELEPHONY_PHONE_MANAGER, classLoader) ?: return
        var hooked = 0

        hooked += HookUtils.hookAllMethods(manager, "getAllCellInfo") { chain ->
            if (!serving()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            // Any failure must degrade to "no cells", never to an exception in the
            // telephony stack: this process serves every app on the device.
            runCatching { CellPayload.payload(HookUtils.systemProperty(OPERATOR_PROPERTY), classLoader) }
                .getOrNull() ?: ArrayList<Any>()
        }

        hooked += HookUtils.hookAllMethods(manager, "getCellLocation") { chain ->
            // Matches the app-process hooker: a device that reports no cells has
            // no serving cell location either. Deliberately independent of
            // whether fabrication succeeded, so the two answers stay consistent.
            if (!serving()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            null
        }

        // requestCellInfoUpdate is deliberately NOT covered yet. In this process
        // its argument is the app's remote binder callback, so the app-process
        // technique of hooking the callback class cannot work: that class is not
        // loaded here. Covering it needs a dynamic proxy around the binder
        // argument to rewrite the list it delivers - the approach the Tencent
        // SDK hooker already uses - which is worth doing once this layer has been
        // confirmed on a device.
        PacerLog.i("phone process cell hooks installed (targets=$hooked)")
    }

    private fun serving(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && EnvGeo.hasFix(cfg.latitude, cfg.longitude)
    }
}
