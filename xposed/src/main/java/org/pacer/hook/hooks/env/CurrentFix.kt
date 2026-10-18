// The one place that answers "where is the subject now".
//
// WHY THIS EXISTS, and it is the fix for a fault that no single file could show: the
// position used to live in the config holder, advanced by writing back to
// PacerRuntimeConfig.latitude/longitude from a one-second timer. Two things followed.
// The config channel re-reads the app's values every few seconds, so a straight-line
// run was yanked back to its start on that cadence; and route mode never wrote back at
// all, so the injected location walked the route while the WiFi, cell and BLE views -
// and every map SDK adapter - described the start point for the whole run.
//
// So the position is DERIVED here from inputs every layer already has (start point,
// heading or route, speed, start timestamp) instead of being stored and advanced. Two
// callers cannot disagree about a value they both compute the same way, and nothing
// that rewrites a stored position can move the subject.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.RouteEngine
import org.pacer.motion.GeoPoint
import org.pacer.motion.StraightLine

object CurrentFix {

    /**
     * The simulated position at [nowMs] (wall clock, the same base as the run's start
     * timestamp), in WGS84.
     *
     * Route mode is answered by the route engine, which is the very sample the
     * injected location uses, so the two agree by construction rather than by matching
     * formulas. Everything else is a straight line from the configured start, which is
     * also what an inactive simulation reports - callers gate on [EnvGeo.hasFix] before
     * using the result.
     */
    fun of(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long = System.currentTimeMillis()): GeoPoint {
        RouteEngine.sampleAt(nowMs)?.let { return GeoPoint(it.point.lat, it.point.lon) }
        val elapsed = elapsedSeconds(cfg, nowMs)
        // With an interval plan the distance is the plan's own integral rather than
        // speed x time, so the position stays a pure function of the clock either way
        // and the reported speed can follow the plan without contradicting it.
        val meters = cfg.plan?.metersAt(elapsed) ?: (cfg.speedMps * elapsed)
        return StraightLine.byDistance(
            start = GeoPoint(cfg.latitude, cfg.longitude),
            bearingDeg = cfg.bearingDeg.toDouble(),
            meters = meters,
        )
    }

    /**
     * Seconds since the run started, or 0 without a usable start timestamp - which
     * leaves the subject at the configured point instead of at an arbitrary offset
     * from the epoch.
     */
    private fun elapsedSeconds(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long): Double {
        if (cfg.startTimestamp <= 0L) return 0.0
        return ((nowMs - cfg.startTimestamp).coerceAtLeast(0L)) / 1000.0
    }

    /**
     * The simulated speed at [nowMs].
     *
     * Derived from the same inputs as [of]: the route sample's speed in route mode, the
     * interval plan's current step when there is one, and the flat configured speed
     * otherwise. A caller cannot report a speed its own displacement contradicts, and
     * the step counter has to ask the same question as the coordinates do - steps that
     * accumulate at a rate the track does not show are a contradiction an audit finds.
     */
    fun speedAt(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long = System.currentTimeMillis()): Double {
        RouteEngine.sampleAt(nowMs)?.let { return it.speedMps }
        return cfg.plan?.speedAt(elapsedSeconds(cfg, nowMs)) ?: cfg.speedMps
    }
}
