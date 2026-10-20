// Route engine for the hook side: turns the configured route polyline plus
// speed preset into a humanized run (speed curve, steps, signal traits) using
// core-motion, then hands out the sample matching the elapsed time. This keeps
// the hook's output physically self-consistent (distance <-> steps <-> cadence)
// instead of a straight constant-speed line.
package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.FixSample
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionSamples
import org.pacer.motion.MotionSeed
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteClock
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan

object RouteEngine {

    @Volatile private var cacheKey: String? = null
    @Volatile private var fixes: List<FixSample> = emptyList()

    /** One lap's length in seconds, fixed when a repeating run is first built. */
    @Volatile private var lapSeconds: Double = 0.0

    /** The run that [lapSeconds] belongs to, so a new run starts counting again. */
    @Volatile private var lapRunStartedAt: Long = 0L

    /** Sample for the current elapsed time, or null when no route is configured. */
    fun sampleAt(nowMs: Long = System.currentTimeMillis()): FixSample? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active) return null
        val route = parseRoute(cfg.routeJson) ?: return null
        if (cfg.startTimestamp != lapRunStartedAt) {
            lapRunStartedAt = cfg.startTimestamp
            lapSeconds = 0.0
        }
        // The route clock, not the wall clock: a paused run has to answer with one
        // frozen second for as long as it stays paused, and a resumed run has to
        // continue from the second it stopped at. See RouteClock.
        val elapsed = RouteClock.secondsAt(
            offsetSec = cfg.routeOffsetMs / 1000.0,
            resumedAtMs = if (cfg.routeResumedAtMs > 0L) cfg.routeResumedAtMs else cfg.startTimestamp,
            nowMs = nowMs,
            paused = cfg.routePaused,
        )
        // A repeating run: which lap this moment is in, and how far into it. The lap length
        // comes from the first build of the run and then stays fixed, because every lap is
        // built with its own noise and would otherwise move its own ending.
        val lap = if (cfg.loopRoute) {
            MotionSamples.lapAt(elapsed, lapSeconds)
        } else {
            MotionSamples.lapAt(elapsed, 0.0)
        }
        // The switches, the advertised accuracy and the lap are part of the cache key: they
        // change the samples (accuracy, altitude, bearing, satellites, dropout), so a run
        // configured differently - or a later lap, which is humanized again - must not
        // reuse the previous build. Two laps of one route repeat exactly otherwise, which
        // is the easiest thing a detector could look for.
        //
        // The route is named by its length and hash, not by its text: a road route is
        // thousands of points, and this key is rebuilt on every sensor callback.
        val key = cfg.routeJson.length.toString() + ":" + cfg.routeJson.hashCode() + "|" +
            cfg.speedMps + "|" + cfg.startTimestamp + "|" +
            cfg.realism + "|" + cfg.accuracyMeters + "|" + (cfg.plan?.format() ?: "") +
            "|loop=" + cfg.loopRoute + "|lap=" + lap.index
        if (key != cacheKey) {
            // The seed is a function of (start timestamp, lap), not a fresh random value:
            // the app draws its live marker from this same run, and it can only agree if
            // both sides build the same track. It also means a rebuild caused by a config
            // change reproduces the track instead of teleporting the subject.
            build(
                route = route,
                key = key,
                speedMps = cfg.speedMps,
                toggles = cfg.realism,
                accuracyMeters = cfg.accuracyMeters,
                plan = cfg.plan,
                seed = MotionSeed.forRun(cfg.startTimestamp, lap.index),
            )
            if (cfg.loopRoute && lapSeconds <= 0.0) {
                lapSeconds = fixes.lastOrNull()?.tSec ?: 0.0
            }
        }
        val list = fixes
        if (list.isEmpty()) return null
        // Binary search, not a scan: the sensor callbacks ask this question tens of times a
        // second and a run holds one sample per second for minutes.
        val base = MotionSamples.at(list, lap.offsetSec) ?: return null
        val sample = MotionSamples.afterLaps(
            sample = base,
            laps = lap.index,
            lapMeters = list.last().distanceMeters,
            lapSteps = list.last().cumulativeSteps,
            tSec = elapsed,
        )
        // A paused run keeps its position but not its motion: the sample it reports is
        // the one it stopped at, with speed and cadence zeroed. Reporting the stopped
        // second's cruising speed while the coordinates do not move is the same
        // contradiction as a runner standing still with a running cadence.
        return if (cfg.routePaused) sample.copy(speedMps = 0.0, cadenceSpm = 0.0) else sample
    }

    private fun build(
        route: RoutePath,
        key: String,
        speedMps: Double,
        toggles: RealismToggles,
        accuracyMeters: Float,
        plan: SpeedPlan?,
        seed: Long,
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
            fixes = sim.simulate(dtSec = 1.0, seed = seed)
            cacheKey = key
            PacerLog.i(
                "route engine built: ${fixes.size} samples, ${route.totalLengthMeters.toInt()} m, " +
                    "seed=${java.lang.Long.toHexString(seed)}",
            )
        } catch (t: Throwable) {
            fixes = emptyList()
            cacheKey = null
        }
    }

    /** The route JSON last parsed, and the result, so a hot path does not re-parse it. */
    @Volatile private var parsedRouteJson: String? = null

    @Volatile private var parsedRoute: RoutePath? = null

    /** Route JSON is the shared [RouteJson] wire format: [[lat,lon],...]. */
    fun parseRoute(json: String): RoutePath? {
        if (json.isBlank()) return null
        // Identity first: the config holder keeps the same String instance until a new
        // config arrives, so the common case costs a reference comparison. The equality
        // fallback covers a fresh instance carrying the same text.
        if (json === parsedRouteJson || json == parsedRouteJson) return parsedRoute
        val parsed = try {
            val points = RouteJson.decode(json)
            if (points.size >= 2) RoutePath(points) else null
        } catch (t: Throwable) {
            PacerLog.w("route-parse", t)
            null
        }
        parsedRouteJson = json
        parsedRoute = parsed
        return parsed
    }
}
