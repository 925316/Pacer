// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Route replay: drives requestLocationUpdates listeners with fabricated
// positions. Pacer's own implementation, informed by the public GPL-3.0
// LocationSpoofer RouteEngine / LocationGetterHooker structure
// (see THIRD_PARTY_NOTICES.md). No config-file channel yet -- replays a
// straight segment from the configured lat/lon along the configured bearing
// at the configured speed, which is enough to drive a target app's run flow.
package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.concurrent.CopyOnWriteArrayList

object RouteReplayHooker {

    private data class Sink(val listener: LocationListener?, val handler: Handler?, val provider: String)

    private val sinks = CopyOnWriteArrayList<Sink>()

    @Volatile private var timerStarted = false
    private val timerLock = Any()

    fun install(classLoader: ClassLoader) {
        val lm = HookUtils.findClassIfExists("android.location.LocationManager", classLoader) ?: return

        HookUtils.hookAllMethods(lm, "requestLocationUpdates") { chain ->
            val provider = chain.args.firstOrNull { it is String } as? String ?: LocationManager.GPS_PROVIDER
            var listener: LocationListener? = null
            var handler: Handler? = null
            for (arg in chain.args) {
                if (arg is LocationListener) listener = arg
                if (arg is Handler) handler = arg
            }
            if (listener != null && PacerRuntimeConfig.snapshot().active) {
                sinks.add(Sink(listener, handler, provider))
                startTimerIfNeeded()
                listener.onProviderEnabled(provider)
                fakeIfActive(provider)?.let { listener.onLocationChanged(it) }
                LocationInjector.scrubFake(listener)
                return@hookAllMethods null
            }
            chain.proceed(chain.args.toTypedArray())
        }

        HookUtils.hookAllMethods(lm, "removeUpdates") { chain ->
            val l = chain.args.firstOrNull()
            if (l is LocationListener) sinks.removeAll { it.listener === l }
            chain.proceed(chain.args.toTypedArray())
        }
        PacerLog.i("route replay hooks installed")
    }

    private fun startTimerIfNeeded() {
        synchronized(timerLock) {
            if (timerStarted) return
            timerStarted = true
        }
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    pushTick()
                } catch (_: Throwable) {
                } finally {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
        handler.post(tick)
    }

    private fun pushTick() {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return
        advancePosition(cfg)
        val loc = fakeIfActive(LocationManager.GPS_PROVIDER) ?: return
        for (sink in sinks.toList()) {
            try {
                (sink.handler ?: Handler(Looper.getMainLooper())).post {
                    try {
                        sink.listener?.onLocationChanged(Location(loc))
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** Move the configured lat/lon forward along bearing by speed * 1s. */
    private fun advancePosition(cfg: PacerRuntimeConfig.Snapshot) {
        // Route mode advances position through RouteEngine instead.
        if (cfg.routeJson.isNotBlank()) return
        if (cfg.speedMps <= 0.0) return
        val dist = cfg.speedMps // 1s tick
        val latRad = Math.toRadians(cfg.latitude)
        val dLat = dist * Math.cos(Math.toRadians(cfg.bearingDeg.toDouble())) / 111320.0
        val dLon = dist * Math.sin(Math.toRadians(cfg.bearingDeg.toDouble())) /
            (111320.0 * Math.cos(latRad))
        PacerRuntimeConfig.latitude = cfg.latitude + dLat
        PacerRuntimeConfig.longitude = cfg.longitude + dLon
    }

    private fun fakeIfActive(provider: String): Location? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return LocationInjector.buildLocation(provider, cfg, SystemClock.elapsedRealtimeNanos())
    }
}

/** Scrub a listener's cached Location (defensive; mirrors reference behavior). */
private fun LocationInjector.scrubFake(listener: LocationListener) {
    // No-op placeholder: real per-listener scrub happens in onLocationChanged copies.
}
