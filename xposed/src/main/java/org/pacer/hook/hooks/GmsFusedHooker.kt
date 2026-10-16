// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Google Play Services fused-location hooks. Apps that use
// FusedLocationProviderClient receive their fixes through
// com.google.android.gms.location.LocationResult, so we replace the location
// it hands out. Pacer's own implementation.
package org.pacer.hook.hooks

import android.location.Location
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object GmsFusedHooker {

    fun install(classLoader: ClassLoader) {
        val resultClass = HookUtils.findClassIfExists("com.google.android.gms.location.LocationResult", classLoader)
            ?: return

        HookUtils.hookAllMethods(resultClass, "getLastLocation") { chain ->
            fakeFused(null) ?: chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(resultClass, "getLocations") { chain ->
            val fake = fakeFused(null)
            if (fake != null) listOf(fake) else chain.proceed(chain.args.toTypedArray())
        }
        PacerLog.i("gms fused hooks installed")
    }

    private fun fakeFused(provider: String?): Location? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return LocationInjector.buildLocation(
            provider ?: "fused",
            cfg,
            SystemClock.elapsedRealtimeNanos(),
        )
    }
}
