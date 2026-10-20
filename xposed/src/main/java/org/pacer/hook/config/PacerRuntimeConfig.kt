package org.pacer.hook.config

import org.pacer.motion.CoordinateOverride
import org.pacer.motion.CoordinateSystem
import org.pacer.motion.RealismToggles
import org.pacer.motion.SpeedPlan

/**
 * In-process runtime configuration consulted by the hooks.
 *
 * The remote-config channel (app -> framework service -> hook) is a later
 * milestone; until then this holder keeps the hook logic testable and lets the
 * module compile with safe defaults (everything off).
 */
object PacerRuntimeConfig {

    @Volatile var active: Boolean = false

    /** Current simulated speed in m/s, 0 means stationary. */
    @Volatile var speedMps: Double = 0.0

    @Volatile var enableStepSimulation: Boolean = true

    /**
     * Whether the Bluetooth environment is simulated. True before any config has
     * arrived, matching the app's default and ConfigSync's: the environment layers
     * agree with each other, and this one is no exception.
     */
    @Volatile var mockBluetooth: Boolean = true

    /**
     * Which signal-realism behaviours to simulate. Held as the engine's own type so the
     * hook passes it straight through: the switch that a user flips and the value the
     * model reads are then the same thing rather than two lists that have to agree.
     */
    @Volatile var realism: RealismToggles = RealismToggles()

    /** Wall-clock millis when the current simulation started. */
    @Volatile var startTimestamp: Long = 0L

    // --- target position (WGS84) ---
    @Volatile var latitude: Double = 0.0
    @Volatile var longitude: Double = 0.0
    @Volatile var accuracyMeters: Float = 5f
    @Volatile var altitudeMeters: Double = 0.0
    @Volatile var bearingDeg: Float = 0f

    /** Compact route polyline as [[lat,lon],...] (empty = straight-line mode). */
    @Volatile var routeJson: String = ""

    /**
     * Interval plan for the run, or null for one steady speed. Parsed once per config
     * change rather than per read: the engine asks for it on every sample.
     */
    @Volatile var plan: SpeedPlan? = null

    /**
     * Whether a route run starts over when it reaches the end instead of stopping there.
     * The position returns to the first waypoint while distance and steps keep growing;
     * the arithmetic lives in MotionSamples.atLooped.
     */
    @Volatile var loopRoute: Boolean = false

    /**
     * Whether the route run is frozen at [routeOffsetMs].
     *
     * A paused run still owns the position - it is the route's own sample at the frozen
     * second - but reports no speed and no cadence: standing still with a running
     * cadence is the contradiction a pause exists to avoid.
     */
    @Volatile var routePaused: Boolean = false

    /** Route time reached at the last pause, in milliseconds. */
    @Volatile var routeOffsetMs: Long = 0L

    /** Wall clock of the last resume; 0 for a run that was never resumed. */
    @Volatile var routeResumedAtMs: Long = 0L

    /**
     * Per-app coordinate overrides, already parsed.
     *
     * The fix is produced in WGS-84 and converted once at the boundary, so this only
     * changes what the boundary hands out. See CoordinatePolicy.
     */
    @Volatile var coordinateOverrides: List<CoordinateOverride> = emptyList()

    /** The datum this process's fixes should carry, resolved once per config change. */
    @Volatile var coordinateSystem: CoordinateSystem = CoordinateSystem.AUTO

    /**
     * The datum to use for [apiDefault], honouring any override for this process.
     *
     * Resolved at the call site rather than cached once, because "the default" is a
     * property of the API being hooked, not of the process: the same app asked through
     * `LocationManager` expects WGS-84 while the AMap SDK inside it expects GCJ-02. An
     * explicit override still wins for both.
     */
    fun datumFor(apiDefault: CoordinateSystem): CoordinateSystem =
        CoordinatePolicy.resolve(
            processName = ProcessIdentity.processName(),
            overrides = coordinateOverrides,
            apiDefault = apiDefault,
        )

    fun snapshot(): Snapshot = Snapshot(
        active = active,
        speedMps = speedMps,
        enableStepSimulation = enableStepSimulation,
        mockBluetooth = mockBluetooth,
        realism = realism,
        startTimestamp = startTimestamp,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        altitudeMeters = altitudeMeters,
        bearingDeg = bearingDeg,
        routeJson = routeJson,
        plan = plan,
        loopRoute = loopRoute,
        routePaused = routePaused,
        routeOffsetMs = routeOffsetMs,
        routeResumedAtMs = routeResumedAtMs,
        coordinateOverrides = coordinateOverrides,
        coordinateSystem = coordinateSystem,
    )

    data class Snapshot(
        val active: Boolean,
        val speedMps: Double,
        val enableStepSimulation: Boolean,
        val mockBluetooth: Boolean,
        val realism: RealismToggles,
        val startTimestamp: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val altitudeMeters: Double,
        val bearingDeg: Float,
        val routeJson: String,
        val plan: SpeedPlan?,
        val loopRoute: Boolean,
        val routePaused: Boolean,
        val routeOffsetMs: Long,
        val routeResumedAtMs: Long,
        val coordinateOverrides: List<CoordinateOverride>,
        /** The datum this process should report, resolved from the overrides above. */
        val coordinateSystem: CoordinateSystem,

        /**
         * The datum for a fix delivered through an API whose default is [apiDefault].
         *
         * Resolved here rather than baked into [coordinateSystem] because the default is
         * a property of the API, not of the process: the same package asked through
         * `LocationManager` expects WGS-84 while the AMap SDK inside it expects GCJ-02.
         * An explicit override wins for both.
         */
        fun datumFor(apiDefault: CoordinateSystem): CoordinateSystem =
            if (coordinateSystem == CoordinateSystem.AUTO) {
                apiDefault
            } else {
                coordinateSystem
            }
    )
}
