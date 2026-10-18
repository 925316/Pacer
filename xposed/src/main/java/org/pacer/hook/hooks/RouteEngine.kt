// Route engine for the hook side: turns the configured route polyline plus
// speed preset into a humanized run (speed curve, steps, signal traits) using
// core-motion, then hands out the sample matching the elapsed time. This keeps
// the hook's output physically self-consistent (distance <-> steps <-> cadence)
// instead of a straight constant-speed line.
package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.MotionConfig
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan
import org.pacer.motion.FixSample

object RouteEngine {

    @Volatile private var cacheKey: String? = null
    @Volatile private var fixes: List<FixSample> = emptyList()

    /** Sample for the current elapsed time, or null when no route is configured. */
    fun sampleAt(nowMs: Long = System.currentTimeMillis()): FixSample? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return null
        val route = parseRoute(cfg.routeJson) ?: return null
        // The switches and the advertised accuracy are part of the cache key: they change
        // the samples (accuracy, altitude, bearing, satellites, dropout), so a run
        // configured differently must not reuse the previous build.
        val key = cfg.routeJson + "|" + cfg.speedMps + "|" + cfg.startTimestamp + "|" +
            cfg.realism + "|" + cfg.accuracyMeters + "|" + (cfg.plan?.format() ?: "")
        if (key != cacheKey) {
            build(route, key, cfg.speedMps, cfg.realism, cfg.accuracyMeters, cfg.plan)
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

    private fun build(
        route: RoutePath,
        key: String,
        speedMps: Double,
        toggles: RealismToggles,
        accuracyMeters: Float,
        plan: SpeedPlan?,
    ) {
        try {
            val config = if (speedMps > 0.0) {
                MotionConfig(baseSpeedMps = speedMps, plan = plan)
            } else {
                MotionConfig.run().copy(plan = plan)
            }
            val sim = RouteSimulator(
                route,
                config,
                toggles = toggles,
                steadyAccuracyMeters = accuracyMeters,
            )
            fixes = sim.simulate(dtSec = 1.0)
            cacheKey = key
            PacerLog.i("route engine built: ${fixes.size} samples, ${route.totalLengthMeters.toInt()} m")
        } catch (t: Throwable) {
            fixes = emptyList()
            cacheKey = null
        }
    }

    /** Route JSON is the shared [RouteJson] wire format: [[lat,lon],...]. */
    fun parseRoute(json: String): RoutePath? {
        if (json.isBlank()) return null
        return try {
            val points = RouteJson.decode(json)
            if (points.size >= 2) RoutePath(points) else null
        } catch (t: Throwable) {
            PacerLog.w("route-parse", t)
            null
        }
    }
}
