// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Location injection hooker. Adapted for Pacer from the public GPL-3.0
// LocationSpoofer sources (see THIRD_PARTY_NOTICES.md). Installs a fabricated
// android.location.Location into the scoped process's LocationManager reads and
// scrubs the mock-location flags from every Location the process sees.
package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object LocationInjector {

    private val realism = org.pacer.motion.SignalRealism(seed = 7L)
    private val jitter = org.pacer.motion.StationaryJitter(seed = 7L)
    @Volatile private var lastNanos = 0L

    /** Always expose a fabricated position while a simulation is active. */
    fun install(classLoader: ClassLoader) {
        val lmClass = HookUtils.findClassIfExists("android.location.LocationManager", classLoader)
        if (lmClass != null) {
            HookUtils.hookAllMethods(lmClass, "getLastKnownLocation") { chain ->
                fakeIfActive(chain.args.firstOrNull() as? String)
                    ?: chain.proceed(chain.args.toTypedArray())
            }
            if (Build.VERSION.SDK_INT >= 30) {
                HookUtils.hookAllMethods(lmClass, "getCurrentLocation") { chain ->
                    fakeIfActive(chain.args.firstOrNull() as? String)
                        ?: chain.proceed(chain.args.toTypedArray())
                }
            }
        }
        scrubLocationFlags(classLoader)
        PacerLog.i("location injection hooks installed")
    }

    private fun fakeIfActive(provider: String?): Location? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return buildLocation(provider ?: LocationManager.GPS_PROVIDER, cfg, nowNanos = SystemClock.elapsedRealtimeNanos())
    }

    /** Build a Location object and force every mock flag to false. */
    fun buildLocation(
        provider: String,
        cfg: PacerRuntimeConfig.Snapshot,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Location {
        val dtSec = if (lastNanos == 0L) 1.0 else ((nowNanos - lastNanos).coerceAtLeast(0L)) / 1e9
        lastNanos = nowNanos
        val elapsedSec = if (cfg.startTimestamp > 0L) {
            (System.currentTimeMillis() - cfg.startTimestamp).coerceAtLeast(0L) / 1000.0
        } else 0.0
        val fx = realism.next(elapsedSec, cfg.speedMps, dtSec, cfg.bearingDeg)

        var lat = cfg.latitude
        var lon = cfg.longitude
        var accuracy = fx.accuracyMeters
        var altitude = cfg.altitudeMeters + fx.altitudeMeters
        var bearing = fx.bearingDeg
        var satellites = fx.satellites
        var speed = cfg.speedMps

        // Route mode: route + humanized speed curve take precedence.
        val sample = RouteEngine.sampleAt()
        if (sample != null) {
            lat = sample.point.lat
            lon = sample.point.lon
            accuracy = sample.accuracyMeters
            altitude = sample.altitudeMeters
            bearing = sample.bearingDeg
            satellites = sample.satellites
            speed = sample.speedMps
        } else if (cfg.speedMps < 0.3) {
            // Straight-line idle: add micro-movement so the point is not pixel-still.
            val off = jitter.next(dtSec)
            lat += off.northMeters / 111320.0
            lon += off.eastMeters / (111320.0 * kotlin.math.cos(Math.toRadians(lat)))
        }

        val location = Location(provider)
        location.latitude = lat
        location.longitude = lon
        location.accuracy = accuracy
        location.altitude = altitude
        location.bearing = bearing
        location.speed = speed.toFloat()
        location.time = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 17) location.elapsedRealtimeNanos = nowNanos
        val bundle = Bundle()
        bundle.putInt("satellites", satellites)
        bundle.putInt("satellitesvalue", satellites)
        try {
            location.extras = bundle
        } catch (_: Throwable) {
        }
        scrub(location)
        return location
    }

    /** Clear the AOSP mock flags on a Location instance via reflection. */
    fun scrub(location: Location) {
        HookUtils.setBooleanField(location, "mIsFromMockProvider", false)
        HookUtils.setBooleanField(location, "mMock", false)
        HookUtils.setBooleanField(location, "mIsMock", false)
    }

    private fun scrubLocationFlags(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("android.location.Location", classLoader) ?: return
        HookUtils.hookAllMethods(locClass, "isFromMockProvider") { chain ->
            if (PacerRuntimeConfig.snapshot().active) return@hookAllMethods false
            chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(locClass, "isMock") { chain ->
            if (PacerRuntimeConfig.snapshot().active) return@hookAllMethods false
            chain.proceed(chain.args.toTypedArray())
        }
    }
}
