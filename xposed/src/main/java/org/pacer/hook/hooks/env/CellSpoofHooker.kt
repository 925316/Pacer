// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Cell environment handling. A moving fix that keeps reporting the real
// neighbourhood towers is an inconsistency a risk-control SDK can detect, so
// the real cell data must not be exposed while a simulation is active.
//
// LIMITATION: this hooker suppresses cell data instead of fabricating it.
// Building real CellInfo / CellIdentity / CellSignalStrength objects off the
// phone stack is fragile: their constructors are hidden, the identity fields
// are final on most builds, and many OEMs mutate the returned objects in place
// and NPE if the nested arrays or signal objects are missing. Suppressing the
// data is the safe choice: it never fabricates a malformed object and it never
// contradicts the simulated coordinate. Callers see an empty list / null, which
// is also what a device with no service reports.
//
// The deterministic tower model in FakeRadioEnvironment is intentionally not
// wired to a hook yet; it is kept for a future, per-OEM-safe delivery path.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object CellSpoofHooker {

    fun install(classLoader: ClassLoader) {
        val telephonyManager =
            HookUtils.findClassIfExists("android.telephony.TelephonyManager", classLoader) ?: return
        hookAllCellInfo(telephonyManager)
        hookCellLocation(telephonyManager)
        hookNeighboringCellInfo(telephonyManager)
        PacerLog.i("cell environment hooks installed (suppress-only)")
    }

    private fun hookAllCellInfo(telephonyManager: Class<*>) {
        HookUtils.hookAllMethods(telephonyManager, "getAllCellInfo") { chain ->
            if (!suppressing()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            java.util.ArrayList<Any>()
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
            java.util.ArrayList<Any>()
        }
    }

    private fun suppressing(): Boolean {
        val cfg = PacerRuntimeConfig.snapshot()
        return cfg.active && EnvGeo.hasFix(cfg.latitude, cfg.longitude)
    }
}
