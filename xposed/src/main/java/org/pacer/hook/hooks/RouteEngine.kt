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
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteResampler
import org.pacer.motion.RouteSimulator
import org.pacer.motion.FixSample

object RouteEngine {

    /** Target arc-length spacing for the cached resampled route. */
    private const val RESAMPLE_SPACING_METERS = 1.0

    @Volatile private var cacheKey: String? = null
    @Volatile private var fixes: List<FixSample> = emptyList()

    /**
     * Uniform (~1 m) resampling of the current route polyline.
     *
     * Intended use: advancing along the route becomes "advance the index"
     * instead of repeating segment projection maths per step, which is the
     * cheap path for progress / nearest-point queries. The injected sample
     * stream itself still comes from [RouteSimulator], so this is computed
     * defensively and does not alter [sampleAt] output.
     */
    @Volatile private var resampledPoints: List<GeoPoint> = emptyList()

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

    /**
     * Uniform resampled route points currently cached (empty when no route is
     * built). Exposed for callers that want the index-walk path over the route.
     */
    internal fun resampledRoute(): List<GeoPoint> = resampledPoints

    private fun build(route: RoutePath, key: String, speedMps: Double) {
        try {
            val config = if (speedMps > 0.0) {
                MotionConfig(baseSpeedMps = speedMps)
            } else {
                MotionConfig.run()
            }
            val sim = RouteSimulator(route, config)
            fixes = sim.simulate(dtSec = 1.0)
            resampledPoints = try {
                RouteResampler.resample(route.points, RESAMPLE_SPACING_METERS)
            } catch (t: Throwable) {
                PacerLog.w("route-resample", t)
                emptyList()
            }
            cacheKey = key
            PacerLog.i("route engine built: ${fixes.size} samples, ${route.totalLengthMeters.toInt()} m")
        } catch (t: Throwable) {
            fixes = emptyList()
            resampledPoints = emptyList()
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
