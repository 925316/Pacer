package org.pacer.app

import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionSamples
import org.pacer.motion.MotionSeed
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan
import org.pacer.motion.StraightLine

/**
 * Where the stored run says the subject is right now.
 *
 * This used to live inside the map page, which was fine while the map was the only
 * consumer. The joystick recorder needs the same answer once a second, and a second
 * copy of this arithmetic would be a second thing to keep in step with the module -
 * the map would then draw one track while the recorder stored another.
 *
 * The one-run cache lives here too: building a lap means simulating it, and the map
 * polls once a second, so the cache is what keeps that from being a per-second rebuild.
 * A single instance belongs to whoever is polling (the map page, or the recorder);
 * two independent pollers with different runs are not a case that exists, because the
 * stored run is the same for both.
 */
class LiveSubject {

    private class Lap(val key: String, val samples: List<org.pacer.motion.FixSample>)

    private var lap: Lap? = null
    private var lapRunStartedAt = 0L
    private var lapSeconds = 0.0

    private var parsedRouteJson: String? = null
    private var parsedRoute: RoutePath? = null

    /** Where the subject is, or null when the run has no usable position yet. */
    fun point(stored: PacerConfigClient.Stored, nowMs: Long = System.currentTimeMillis()): GeoPoint? {
        val elapsed = stored.routeSeconds(nowMs)
        val routePath = routeOf(stored)
        if (routePath != null) {
            if (stored.startedAtMs != lapRunStartedAt) {
                lapRunStartedAt = stored.startedAtMs
                lapSeconds = 0.0
                lap = null
            }
            val lapInfo = MotionSamples.lapAt(elapsed, if (stored.loopRoute) lapSeconds else 0.0)
            val key = keyOf(stored) + "|lap=" + lapInfo.index
            val samples = lap?.takeIf { it.key == key }?.samples
                ?: buildLap(stored, routePath, key, lapInfo.index)
            if (samples.isEmpty()) return null
            val base = MotionSamples.at(samples, lapInfo.offsetSec) ?: return null
            return MotionSamples.afterLaps(
                base,
                lapInfo.index,
                samples.last().distanceMeters,
                samples.last().cumulativeSteps,
                elapsed,
            ).point
        }
        val meters = SpeedPlan.parse(stored.plan)?.metersAt(elapsed) ?: (stored.speed * elapsed)
        return StraightLine.byDistance(GeoPoint(stored.lat, stored.lon), stored.bearing.toDouble(), meters)
    }

    /**
     * Builds one lap of the stored run, seeded exactly as the module seeds it.
     *
     * The lap length is learned from the first build and then kept for the run, matching
     * RouteEngine: a later lap is humanized separately and would otherwise move its own
     * ending.
     */
    private fun buildLap(
        stored: PacerConfigClient.Stored,
        routePath: RoutePath,
        key: String,
        lapIndex: Long,
    ): List<org.pacer.motion.FixSample> {
        val samples = RouteSimulator(
            routePath,
            MotionConfig(
                baseSpeedMps = if (stored.speed > 0.0) stored.speed else 3.0,
                plan = SpeedPlan.parse(stored.plan),
            ),
            toggles = stored.realism,
            steadyAccuracyMeters = stored.accuracy,
        ).simulate(dtSec = 1.0, seed = MotionSeed.forRun(stored.startedAtMs, lapIndex))
        if (stored.loopRoute && lapSeconds <= 0.0) {
            lapSeconds = samples.lastOrNull()?.tSec ?: 0.0
        }
        lap = Lap(key, samples)
        return samples
    }

    private fun routeOf(stored: PacerConfigClient.Stored): RoutePath? {
        if (stored.routeJson.isBlank()) return null
        if (stored.routeJson === parsedRouteJson) return parsedRoute
        val parsed = try {
            val points = RouteJson.decode(stored.routeJson)
            if (points.size >= 2) RoutePath(points) else null
        } catch (_: Throwable) {
            null
        }
        parsedRouteJson = stored.routeJson
        parsedRoute = parsed
        return parsed
    }

    /**
     * Everything that changes the samples, so one lap is rebuilt only when it must be.
     *
     * The route is named by its length and hash rather than its text: this runs once a
     * second on the poll, and a long route is a long string.
     */
    private fun keyOf(stored: PacerConfigClient.Stored): String =
        stored.startedAtMs.toString() + "|" + stored.routeJson.length + ":" +
            stored.routeJson.hashCode() + "|" + stored.speed + "|" +
            stored.plan + "|" + stored.loopRoute + "|" + stored.realism + "|" + stored.accuracy
}
