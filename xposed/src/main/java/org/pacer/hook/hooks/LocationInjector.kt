// Location injection hooker: installs a fabricated android.location.Location
// into the scoped process's LocationManager reads and scrubs the mock-location
// flags from every Location the process sees.
package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.CoordinatePolicy
import org.pacer.motion.CoordinateSystem
import org.pacer.motion.GeoPoint
import org.pacer.motion.GeoMath
import org.pacer.motion.SatelliteStatus
import org.pacer.motion.SignalRealism
import org.pacer.motion.PositionJitter
import java.util.concurrent.Executor
import java.util.function.Consumer

object LocationInjector {

    /** Android 12+ exposes the fused provider under this name. */
    private const val FUSED_PROVIDER = "fused"

    /** How long the coordinate getter hook may reuse the last computed position. */
    private const val TARGET_TTL_MS = 500L

    /** How long a reported fix stays answerable to the adapters of other SDKs. */
    private const val STALE_REPORT_MS = 2000L

    /**
     * Satellites a receiver tracks but does not use. A fix that uses every
     * satellite it can see is a receiver that never had to drop a weak signal,
     * which is not what a phone in a pocket looks like.
     */
    private const val IN_VIEW_MARGIN = 6

    /**
     * Altitude reported when a fix would otherwise carry altitude, bearing and
     * speed all at exactly zero. See [buildLocation] for why that triple must
     * never leave this module.
     */
    private const val ALTITUDE_FLOOR_METERS = 1.0

    /**
     * The noise engines, one set per run.
     *
     * The seed is derived from the run's start timestamp, so consecutive runs produce
     * different offsets. A fixed seed made the same input always produce the same
     * track, which is a fingerprint of the module rather than a simulation of a person
     * - the same reason the environment layers seed from the session.
     *
     * The engines hold state (the bearing held while standing still), so a new run gets
     * fresh instances instead of a reseeded one, which would carry the previous run's
     * phase into the new one.
     */
    private class RunNoise(
        val key: Any,
        val realism: SignalRealism,
        val jitter: PositionJitter,
    )

    @Volatile private var noise: RunNoise? = null

    private fun noiseFor(cfg: PacerRuntimeConfig.Snapshot): RunNoise {
        // Keyed on the run AND the switches: flipping one of them has to take effect
        // immediately, and both engines carry state that must not survive into a run
        // configured differently.
        val key = Triple(cfg.startTimestamp, cfg.realism, cfg.accuracyMeters)
        noise?.let { if (it.key == key) return it }
        synchronized(this) {
            noise?.let { if (it.key == key) return it }
            val seed = EnvGeo.sessionSeed(cfg.startTimestamp)
            val fresh = RunNoise(
                key = key,
                realism = SignalRealism(
                    toggles = cfg.realism,
                    // The advertised accuracy is the app's, not an engine constant: it is
                    // the number a location SDK is told, and the two must not differ.
                    steadyAccuracyMeters = cfg.accuracyMeters,
                    seed = seed,
                ),
                jitter = PositionJitter(seed = seed xor 0x5DEECE66DL),
            )
            noise = fresh
            return fresh
        }
    }

    @Volatile private var lastNanos = 0L

    /**
     * The state of the last fix this module reported.
     *
     * Adapters that spoof their OWN API surface - the map SDKs - have to answer
     * accuracy, bearing and speed as well, and reading the raw configuration would make
     * them describe a different fix from the one every Location getter describes: the
     * accuracy warm-up and the altitude drift live in the reported values, not in the
     * configuration. Null when the last report is old, so a client that asks before any
     * injection has happened falls back instead of being handed a stale fix.
     */
    data class Reported(
        val accuracyMeters: Float,
        val speedMps: Float,
        val bearingDeg: Float,
        val altitudeMeters: Double,
    )

    /** See [Reported]. */
    fun lastReported(): Reported? {
        val at = targetReportedAtMs
        if (at == 0L || SystemClock.elapsedRealtime() - at > STALE_REPORT_MS) return null
        return Reported(targetAccuracy, targetSpeed, targetBearing, targetAltitude)
    }

    /** Provider names that mean "the platform produced this fix". */
    private val SYSTEM_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        FUSED_PROVIDER,
    )

    /**
     * The simulated position, kept here so the coordinate getter hook - which an
     * app can call in a tight loop - does not recompute the route on every read.
     */
    @Volatile private var targetLat = 0.0
    @Volatile private var targetLon = 0.0
    @Volatile private var targetAtMs = 0L

    /**
     * The rest of the state a fix reported last: accuracy, speed, bearing,
     * altitude and the satellite counts. An aligned system fix is given these
     * instead of keeping the real device's, so one object cannot say "you are
     * here" while carrying the real speed and altitude.
     */
    @Volatile private var targetAccuracy = 5f
    @Volatile private var targetBearing = 0f
    @Volatile private var targetSpeed = 0f
    @Volatile private var targetAltitude = 0.0
    @Volatile private var targetSky: SatelliteStatus = SatelliteStatus.withFix(11, 9)
    @Volatile private var targetReportedAtMs = 0L

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
                    if (isCancelled(chain)) return@hookAllMethods null
                    val fake = fakeIfActive(chain.args.firstOrNull() as? String)
                    // The public overloads are void and deliver through a Consumer.
                    // Returning the Location from here skipped the real implementation
                    // and never called that Consumer, so a caller waiting for its one
                    // fix waited forever.
                    val consumer = chain.args
                        .firstOrNull { it is Consumer<*> } as? Consumer<*>
                    if (fake == null || consumer == null) {
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val deliver = Runnable { deliverFake(consumer, fake) }
                    val executor = chain.args.firstOrNull { it is Executor } as? Executor
                    if (executor != null) executor.execute(deliver) else deliver.run()
                    null
                }
            }
        }
        scrubLocationFlags(classLoader)
        hideTestProviders(classLoader)
        hookCoordinateGetters(classLoader)
        PacerLog.i("location injection hooks installed")
    }

    private fun hideTestProviders(classLoader: ClassLoader) {
        val lm = HookUtils.findClassIfExists("android.location.LocationManager", classLoader) ?: return

        HookUtils.hookAllMethods(lm, "getAllProviders") { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            if (PacerRuntimeConfig.snapshot().active && result is List<*>) {
                result.filterNot { it is String && org.pacer.motion.AntiDetectionPolicy.isHiddenTestProvider(it) }
            } else {
                result
            }
        }
        HookUtils.hookAllMethods(lm, "getProviders") { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            if (PacerRuntimeConfig.snapshot().active && result is List<*>) {
                result.filterNot { it is String && org.pacer.motion.AntiDetectionPolicy.isHiddenTestProvider(it) }
            } else {
                result
            }
        }
        HookUtils.hookAllMethods(lm, "isProviderEnabled") { chain ->
            val name = chain.args.firstOrNull() as? String
            if (PacerRuntimeConfig.snapshot().active && name != null &&
                org.pacer.motion.AntiDetectionPolicy.isHiddenTestProvider(name)
            ) {
                return@hookAllMethods false
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun fakeIfActive(provider: String?): Location? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return buildLocation(provider ?: LocationManager.GPS_PROVIDER, cfg, nowNanos = SystemClock.elapsedRealtimeNanos())
    }

    /** True when the caller has already abandoned a one-shot location request. */
    private fun isCancelled(chain: XposedInterface.Chain): Boolean =
        chain.args.any { it is CancellationSignal && it.isCanceled }

    /**
     * Hands a fabricated fix to a one-shot consumer.
     *
     * The platform declares `Consumer<Location>` and Java generics are invariant, so the
     * cast cannot be checked; the hook only reaches this path when the argument really is
     * a Consumer, and a Consumer that cannot accept a Location is not one the framework
     * could have built.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deliverFake(consumer: Consumer<*>, location: Location) {
        (consumer as Consumer<Any?>).accept(location)
    }

    /** Build a Location object and force every mock flag to false. */
    fun buildLocation(
        provider: String,
        cfg: PacerRuntimeConfig.Snapshot,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Location {
        val dtSec = if (lastNanos == 0L) 1.0 else ((nowNanos - lastNanos).coerceAtLeast(0L)) / 1e9
        lastNanos = nowNanos
        val nowMs = System.currentTimeMillis()
        val elapsedSec = if (cfg.startTimestamp > 0L) {
            (nowMs - cfg.startTimestamp).coerceAtLeast(0L) / 1000.0
        } else 0.0
        val engines = noiseFor(cfg)
        // One route sample for the whole fix. Asking twice - once through
        // CurrentFix.speedAt and once here - reads the clock twice, and a boundary
        // crossed between the reads pairs sample n's speed with sample n+1's
        // position, which is exactly the kind of disagreement an audit looks for.
        val sample = RouteEngine.sampleAt(nowMs)
        // The speed this moment aims for, from the one place that derives it: the route
        // sample, the interval plan's current step, or the flat configured speed. The
        // realism engine (which holds a bearing while the subject stands still) and the
        // reported speed both use it, so the number the fix carries is the one that
        // produced the displacement.
        val motionSpeed = sample?.speedMps ?: CurrentFix.linearSpeedAt(cfg, nowMs)
        val fx = engines.realism.next(elapsedSec, motionSpeed, dtSec, cfg.bearingDeg)

        // The nominal position for this moment, from the one source every layer uses.
        // Route mode replaces it with the route sample below, which carries the rest of
        // the reported state; the straight-line case is the reason to ask here at all,
        // because it is now derived from the clock instead of accumulated by a timer.
        val nominal = CurrentFix.of(cfg, nowMs)
        var lat = nominal.lat
        var lon = nominal.lon
        var accuracy = fx.accuracyMeters
        var altitude = cfg.altitudeMeters + fx.altitudeMeters
        var bearing = fx.bearingDeg
        var satellites = fx.satellites
        var speed = motionSpeed

        // Route mode: route + humanized speed curve take precedence.
        if (sample != null) {
            lat = sample.point.lat
            lon = sample.point.lon
            accuracy = sample.accuracyMeters
            // The configured start altitude applies in route mode too: it is the ground
            // the route is walked on, and the engine's drift is added to it.
            altitude = cfg.altitudeMeters + sample.altitudeMeters
            bearing = sample.bearingDeg
            satellites = sample.satellites
            speed = sample.speedMps
        } else if (cfg.speedMps < 0.3) {
            // Straight-line idle: add micro-movement so the point is not pixel-still.
            // Scaled by this fix's own accuracy, for the same reason as in the route
            // engine: a cold-start fix claiming 30 m must not sit on the line.
            val radius = PositionJitter.radiusForAccuracy(accuracy, cfg.accuracyMeters)
            val off = engines.jitter.next(dtSec, radius)
            val moved = GeoMath.offsetMeters(GeoPoint(lat, lon), off.eastMeters, off.northMeters)
            lat = moved.lat
            lon = moved.lon
        }

        // A third mock rule, in the SDK that reads this fix: altitude, bearing and
        // speed all exactly zero at once reads as a spoofed report - "nothing moved
        // and nothing is known". Each value is legitimate on its own (standing still
        // is speed 0, no course is bearing 0, ground level is altitude 0), so the
        // module must simply never publish the three together. A stationary run with
        // the altitude drift switched off does exactly that on every fix.
        // The floor is one metre: that is within the altitude noise a consumer GPS
        // receiver reports anyway, so it is a smaller lie than the triple zero.
        if (speed == 0.0 && bearing == 0f && altitude == 0.0) altitude = ALTITUDE_FLOOR_METERS

        // The datum boundary. Everything above is WGS-84, including the cached target
        // values the environment layers read; only the object handed to the target app is
        // converted, and only when that app asked for a different datum. Converting the
        // cache as well would shift the WiFi and cell environments too, which are derived
        // from the WGS-84 fix and are not consumed as coordinates by anyone.
        val reported = CoordinatePolicy.convert(
            GeoPoint(lat, lon),
            cfg.datumFor(CoordinateSystem.WGS84),
        )

        val location = Location(provider)
        targetLat = lat
        targetLon = lon
        targetAtMs = SystemClock.elapsedRealtime()
        location.latitude = reported.lat
        location.longitude = reported.lon
        location.accuracy = accuracy
        location.altitude = altitude
        location.bearing = bearing
        location.speed = speed.toFloat()
        location.time = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 17) location.elapsedRealtimeNanos = nowNanos
        // One publication for every interface that describes the sky: the extras on
        // this fix and the GnssStatus patch read the same numbers. Publishing also
        // caps them to what the real status has proved its arrays can list.
        val sky = LastSky.publish(inView = satellites + IN_VIEW_MARGIN, usedInFix = satellites)
        targetAccuracy = accuracy
        targetBearing = bearing
        targetSpeed = speed.toFloat()
        targetAltitude = altitude
        targetSky = sky
        targetReportedAtMs = targetAtMs
        try {
            location.extras = skyExtras(sky)
        } catch (_: Throwable) {
        }
        scrub(location)
        return location
    }

    /**
     * The satellite counts a platform fix carries in its extras. Four keys because
     * different readers pick different ones, filled from one source so no two of
     * them can disagree.
     */
    private fun skyExtras(sky: SatelliteStatus): Bundle {
        val bundle = Bundle()
        bundle.putInt("satellites", sky.inView)
        bundle.putInt("satellitesvalue", sky.inView)
        bundle.putInt("satellites_in_view", sky.inView)
        bundle.putInt("satellites_used_in_fix", sky.usedInFix)
        return bundle
    }

    /** Clear the AOSP mock flags on a Location instance via reflection. */
    fun scrub(location: Location) {
        HookUtils.setBooleanField(location, "mIsFromMockProvider", false)
        HookUtils.setBooleanField(location, "mMock", false)
        HookUtils.setBooleanField(location, "mIsMock", false)
    }

    /**
     * Location objects that reach the app WITHOUT passing through this module - a
     * Play services Task result, one parcelled in from another process, one cached
     * before the hooks installed - still carry the real position.
     *
     * Both the getters and the private fields are handled, because
     * distanceTo()/bearingTo() read the FIELDS rather than the getters: answering
     * the getters alone would hand the app an object that contradicts itself, which
     * is worse than not touching it at all.
     *
     * Only provider names that mean "the platform produced this fix" are touched,
     * so a Location an app builds for its own purposes keeps its value.
     */
    private fun hookCoordinateGetters(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("android.location.Location", classLoader) ?: return
        HookUtils.hookAllMethods(locClass, "getLatitude") { chain -> coordinate(chain, "mLatitude") }
        HookUtils.hookAllMethods(locClass, "getLongitude") { chain -> coordinate(chain, "mLongitude") }
    }

    /**
     * The aligned value, or the original getter's answer when the location must be
     * left alone. Both the read and the write go through the FIELD: using the
     * property would call the hooked getter again and recurse.
     */
    private fun coordinate(chain: XposedInterface.Chain, field: String): Any? {
        val aligned = align(chain.thisObject as? Location)
        val value = if (aligned == null) null else HookUtils.doubleField(aligned, field)
        return value ?: chain.proceed(chain.args.toTypedArray())
    }

    /** Aligns a system fix with the simulated position, or null to leave it alone. */
    private fun align(location: Location?): Location? {
        if (location == null) return null
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        if (!isSystemFix(location.provider)) return null

        refreshTarget()
        return try {
            if (HookUtils.doubleField(location, "mLatitude") != targetLat) {
                HookUtils.trySetField(location, "mLatitude", targetLat)
            }
            if (HookUtils.doubleField(location, "mLongitude") != targetLon) {
                HookUtils.trySetField(location, "mLongitude", targetLon)
            }
            // Only while the last reported state is still current: asserting a speed
            // from a run that stopped a minute ago would be its own contradiction.
            if (SystemClock.elapsedRealtime() - targetReportedAtMs <= TARGET_TTL_MS) {
                copyReportedState(location)
            }
            location
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Gives an aligned system fix the rest of the state the simulation reports.
     *
     * Coordinates alone leave an object that says "you are here" while still
     * claiming the real device's speed, accuracy, altitude and satellite counts -
     * a contradiction inside one object, which needs no hook to detect. The has*
     * flags are written with the values because getSpeed() reads the value while
     * hasSpeed() reads the flag, and a reader that checks both must see them agree.
     */
    private fun copyReportedState(location: Location) {
        HookUtils.trySetField(location, "mAccuracy", targetAccuracy)
        HookUtils.trySetField(location, "mSpeed", targetSpeed)
        HookUtils.trySetField(location, "mBearing", targetBearing)
        HookUtils.trySetField(location, "mAltitude", targetAltitude)
        HookUtils.trySetField(location, "mHasAccuracy", true)
        HookUtils.trySetField(location, "mHasSpeed", true)
        HookUtils.trySetField(location, "mHasBearing", true)
        HookUtils.trySetField(location, "mHasAltitude", true)
        try {
            location.extras = skyExtras(targetSky)
        } catch (_: Throwable) {
        }
    }

    private fun isSystemFix(provider: String?): Boolean =
        provider != null && SYSTEM_PROVIDERS.contains(provider)

    /** Recomputes the simulated position at most once per TARGET_TTL_MS. */
    private fun refreshTarget() {
        val now = SystemClock.elapsedRealtime()
        if (now - targetAtMs <= TARGET_TTL_MS) return
        val fix = CurrentFix.of(PacerRuntimeConfig.snapshot())
        targetLat = fix.lat
        targetLon = fix.lon
        targetAtMs = now
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
