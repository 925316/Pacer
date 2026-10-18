package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.hooks.env.EnvGeo
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.SignalRealism
import org.pacer.motion.StationaryJitter

object LocationInjector {

    /** Android 12+ exposes the fused provider under this name. */
    private const val FUSED_PROVIDER = "fused"

    /** How long the coordinate getter hook may reuse the last computed position. */
    private const val TARGET_TTL_MS = 500L

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
        val jitter: StationaryJitter,
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
                jitter = StationaryJitter(seed = seed xor 0x5DEECE66DL),
            )
            noise = fresh
            return fresh
        }
    }

    @Volatile private var lastNanos = 0L

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
                    fakeIfActive(chain.args.firstOrNull() as? String)
                        ?: chain.proceed(chain.args.toTypedArray())
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

    /** Build a Location object and force every mock flag to false. */
    fun buildLocation(
        provider: String,
        cfg: PacerRuntimeConfig.Snapshot,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Location {
        val dtSec = if (lastNanos == 0L) 1.0 else ((nowNanos - lastNanos).coerceAtLeast(0L)) / 1e9
        lastNanos = nowNanos
        val elapsedSec = if (cfg.startTimestamp > 0L) {
            (System.currentTimeMillis() - cfg.startTimestamp).coerceAtLeast(0L) / 1000.0
        } else 0.0
        val engines = noiseFor(cfg)
        val fx = engines.realism.next(elapsedSec, cfg.speedMps, dtSec, cfg.bearingDeg)

        // The nominal position for this moment, from the one source every layer uses.
        // Route mode replaces it with the route sample below, which carries the rest of
        // the reported state; the straight-line case is the reason to ask here at all,
        // because it is now derived from the clock instead of accumulated by a timer.
        val nominal = CurrentFix.of(cfg)
        var lat = nominal.lat
        var lon = nominal.lon
        var accuracy = fx.accuracyMeters
        var altitude = cfg.altitudeMeters + fx.altitudeMeters
        var bearing = fx.bearingDeg
        var satellites = fx.satellites
        var speed = cfg.speedMps

        // Route mode: route + humanized speed curve take precedence.
        val sample = RouteEngine.sampleAt()
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
            val off = engines.jitter.next(dtSec)
            lat += off.northMeters / 111320.0
            lon += off.eastMeters / (111320.0 * kotlin.math.cos(Math.toRadians(lat)))
        }

        val location = Location(provider)
        targetLat = lat
        targetLon = lon
        targetAtMs = SystemClock.elapsedRealtime()
        location.latitude = lat
        location.longitude = lon
        location.accuracy = accuracy
        location.altitude = altitude
        location.bearing = bearing
        location.speed = speed.toFloat()
        location.time = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 17) location.elapsedRealtimeNanos = nowNanos
        val bundle = Bundle()
        bundle.putInt("satellites", satellites)
        bundle.putInt("satellitesvalue", satellites)
        try {
            location.extras = bundle
        } catch (_: Throwable) {
        }
        scrub(location)
        return location
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
            location
        } catch (_: Throwable) {
            null
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
