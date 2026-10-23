// The one place that answers "where is the subject now".
//
// WHY THIS EXISTS: a position stored in the config holder and advanced by writing back
// to PacerRuntimeConfig.latitude/longitude from a one-second timer fails in two ways.
// The config channel re-reads the app's values every few seconds, so a straight-line
// run is yanked back to its start on that cadence; and route mode would have to write
// back for every reader, or the injected location walks the route while the WiFi, cell
// and BLE views - and every map SDK adapter - describe the start point for the whole
// run.
//
// So the position is DERIVED here from inputs every layer already has (start point,
// heading or route, speed, start timestamp) instead of being stored and advanced. Two
// callers cannot disagree about a value they both compute the same way, and nothing
// that rewrites a stored position can move the subject.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.RouteEngine
import org.pacer.motion.GeoPoint
import org.pacer.motion.PlanDistance
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
        val step = stepAt(cfg, elapsedSeconds(cfg, nowMs))
        return StraightLine.byDistance(
            start = GeoPoint(cfg.latitude, cfg.longitude),
            bearingDeg = cfg.bearingDeg.toDouble(),
            meters = step.meters,
        )
    }

    /**
     * Seconds since the run started, or 0 without a usable start timestamp - which
     * leaves the subject at the configured point instead of at an arbitrary offset
     * from the epoch.
     */
    private fun elapsedSeconds(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long): Double {
        // The anchor, not the start: hand steering moves the line's origin without
        // restarting the run, so only the anchor says where this straight line began.
        val base = if (cfg.anchorTimestamp > 0L) cfg.anchorTimestamp else cfg.startTimestamp
        if (base <= 0L) return 0.0
        return ((nowMs - base).coerceAtLeast(0L)) / 1000.0
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
    fun speedAt(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long = System.currentTimeMillis()): Double =
        RouteEngine.sampleAt(nowMs)?.speedMps ?: linearSpeedAt(cfg, nowMs)

    /** The straight-line speed at [nowMs]: the plan's own answer, or the configured speed. */
    fun linearSpeedAt(cfg: PacerRuntimeConfig.Snapshot, nowMs: Long = System.currentTimeMillis()): Double =
        stepAt(cfg, elapsedSeconds(cfg, nowMs)).speedMps

    private fun stepAt(cfg: PacerRuntimeConfig.Snapshot, elapsedSec: Double): PlanDistance.Step =
        PlanDistance.at(
            pace = cfg.pacePlan,
            timed = cfg.plan,
            elapsedSec = elapsedSec,
            flatSpeedMps = cfg.speedMps,
        )
}
