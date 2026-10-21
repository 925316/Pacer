package org.pacer.app

import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionSamples
import org.pacer.motion.MotionSeed
import org.pacer.motion.PacePlan
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

    /** One lap's distance and steps, frozen with [lapSeconds] for the same reason. */
    private var lapMeters = 0.0
    private var lapSteps = 0L

    private var parsedRouteJson: String? = null
    private var parsedRoute: RoutePath? = null

    /** Where the subject is, or null when the run has no usable position yet. */
    fun point(stored: PacerConfigClient.Stored, nowMs: Long = System.currentTimeMillis()): GeoPoint? =
        sample(stored, nowMs)?.point

    /**
     * The whole sample the stored run reports at this instant.
     *
     * The self-check compares the injected fix field by field, and speed and altitude are not
     * the configured ones once a run owns its own motion: a route carries its own speed curve
     * and its own drift. Asking for the sample rather than re-deriving those two from the
     * settings is what keeps the check from failing a correct run.
     */
    fun sample(
        stored: PacerConfigClient.Stored,
        nowMs: Long = System.currentTimeMillis(),
    ): org.pacer.motion.FixSample? {
        val routePath = routeOf(stored)
        if (routePath != null) {
            val elapsed = stored.routeSeconds(nowMs)
            if (stored.startedAtMs != lapRunStartedAt) {
                lapRunStartedAt = stored.startedAtMs
                lapSeconds = 0.0
                lapMeters = 0.0
                lapSteps = 0L
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
                lapMeters,
                lapSteps,
                elapsed,
            )
        }
        val elapsed = stored.straightSeconds(nowMs)
        // A pace curve is indexed by distance, so it cannot answer "how far at time t" the
        // way a timed plan can. Invert it instead: the curve knows how long a distance
        // takes, so walk to the moment and read the distance off that inverse.
        val pace = PacePlan.parse(stored.plan)
        val meters = if (pace != null) {
            pace.metersAt(elapsed)
        } else {
            SpeedPlan.parse(stored.plan)?.metersAt(elapsed) ?: (stored.speed * elapsed)
        }
        val point = StraightLine.byDistance(
            GeoPoint(stored.lat, stored.lon),
            stored.bearing.toDouble(),
            meters,
        )
        // A straight run reports the configured values; only a route owns a speed curve and
        // a drift of its own. satellites and steps are not part of this comparison.
        return org.pacer.motion.FixSample(
            tSec = elapsed,
            point = point,
            speedMps = stored.speed,
            bearingDeg = stored.bearing,
            distanceMeters = meters,
            phase = org.pacer.motion.MotionPhase.CRUISE,
            accuracyMeters = stored.accuracy,
            altitudeMeters = 0.0,
            satellites = 0,
            signalDropout = false,
            cumulativeSteps = 0L,
            cadenceSpm = 0.0,
        )
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
                pacePlan = PacePlan.parse(stored.plan),
            ),
            toggles = stored.realism,
            steadyAccuracyMeters = stored.accuracy,
        ).simulate(dtSec = 1.0, seed = MotionSeed.forRun(stored.startedAtMs, lapIndex))
        if (stored.loopRoute && lapSeconds <= 0.0) {
            // Distance and steps are frozen with the lap length, so every boundary adds the
            // same amount. Reading them from the current lap made the total jump by a
            // growing margin - a step counter that bursts once per lap is a machine tell.
            lapSeconds = samples.lastOrNull()?.tSec ?: 0.0
            lapMeters = samples.lastOrNull()?.distanceMeters ?: 0.0
            lapSteps = samples.lastOrNull()?.cumulativeSteps ?: 0L
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
