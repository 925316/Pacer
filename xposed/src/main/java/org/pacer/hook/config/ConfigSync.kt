// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Cross-process config channel: the module app writes a preference group into
// the Xposed framework service; every hooked process reads it and pushes it
// into PacerRuntimeConfig. Read-only from the hook side (LSPosed enforces
// this). Keys are Pacer's own naming.
package org.pacer.hook.config

import android.content.SharedPreferences
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object ConfigSync {

    const val GROUP = "pacer"

    fun install(module: io.github.libxposed.api.XposedInterface) {
        try {
            val prefs = module.getRemotePreferences(GROUP)
            apply(prefs)
            prefs.registerOnSharedPreferenceChangeListener { p, _ -> apply(p) }
            PacerLog.i("config sync installed (active=${PacerRuntimeConfig.active})")
        } catch (t: Throwable) {
            // Framework without remote-preference support: hooks stay idle-safe.
            PacerLog.w("config-sync", t)
        }
    }

    internal fun apply(prefs: SharedPreferences) {
        PacerRuntimeConfig.active = prefs.getBoolean("active", false)
        PacerRuntimeConfig.speedMps = doubleOf(prefs, "speed_mps", 0.0)
        PacerRuntimeConfig.startTimestamp = prefs.getLong("start_ts", 0L)
        PacerRuntimeConfig.latitude = doubleOf(prefs, "lat", 0.0)
        PacerRuntimeConfig.longitude = doubleOf(prefs, "lon", 0.0)
        PacerRuntimeConfig.accuracyMeters = prefs.getFloat("accuracy", 5f)
        PacerRuntimeConfig.altitudeMeters = doubleOf(prefs, "altitude", 0.0)
        PacerRuntimeConfig.bearingDeg = prefs.getFloat("bearing", 0f)
        PacerRuntimeConfig.enableStepSimulation = prefs.getBoolean("step_enabled", true)
        PacerRuntimeConfig.mockBluetooth = prefs.getBoolean("mock_bt", false)
    }

    private fun doubleOf(p: SharedPreferences, key: String, def: Double): Double =
        // SharedPreferences stores doubles as Float; accept both forms.
        try {
            java.lang.Double.longBitsToDouble(p.getLong(key, java.lang.Double.doubleToRawLongBits(def)))
        } catch (_: Throwable) {
            try {
                p.getFloat(key, def.toFloat()).toDouble()
            } catch (_: Throwable) {
                def
            }
        }
}
