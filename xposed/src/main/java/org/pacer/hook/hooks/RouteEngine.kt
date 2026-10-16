// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Route engine for the hook side: turns the configured route polyline plus
// speed preset into a humanized run (speed curve, steps, signal traits) using
// core-motion, then hands out the sample matching the elapsed time. This keeps
// the hook's output physically self-consistent (distance <-> steps <-> cadence)
// instead of a straight constant-speed line.
package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.FixSample

object RouteEngine {

    @Volatile private var cacheKey: String? = null
    @Volatile private var fixes: List<FixSample> = emptyList()

    /** Sample for the current elapsed time, or null when no route is configured. */
    fun sampleAt(nowMs: Long = System.currentTimeMillis()): FixSample? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return null
        val route = parseRoute(cfg.routeJson) ?: return null
        val key = cfg.routeJson + "|" + cfg.speedMps + "|" + cfg.startTimestamp
        if (key != cacheKey) {
            build(route, key, cfg.speedMps)
        }
        val list = fixes
        if (list.isEmpty()) return null
        val elapsed = ((nowMs - cfg.startTimestamp).coerceAtLeast(0L)) / 1000.0
        // Linear scan is fine: runs are minutes long at 1 s sampling.
        var best = list.first()
        for (f in list) {
            if (f.tSec <= elapsed) best = f else break
        }
        return best
    }

    private fun build(route: RoutePath, key: String, speedMps: Double) {
        try {
            val config = if (speedMps > 0.0) {
                MotionConfig(baseSpeedMps = speedMps)
            } else {
                MotionConfig.run()
            }
            val sim = RouteSimulator(route, config)
            fixes = sim.simulate(dtSec = 1.0)
            cacheKey = key
            PacerLog.i("route engine built: ${fixes.size} samples, ${route.totalLengthMeters.toInt()} m")
        } catch (t: Throwable) {
            fixes = emptyList()
            cacheKey = null
        }
    }

    /** Route JSON is [[lat,lon],...] (Pacer's own compact form). */
    fun parseRoute(json: String): RoutePath? {
        if (json.isBlank()) return null
        return try {
            val points = org.json.JSONArray(json)
            val out = ArrayList<GeoPoint>(points.length())
            for (i in 0 until points.length()) {
                val pair = points.getJSONArray(i)
                out.add(GeoPoint(pair.getDouble(0), pair.getDouble(1)))
            }
            if (out.size >= 2) RoutePath(out) else null
        } catch (t: Throwable) {
            PacerLog.w("route-parse", t)
            null
        }
    }
}
