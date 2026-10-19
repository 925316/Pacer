package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.pacer.motion.FixSample
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.MotionSamples
import org.pacer.motion.MotionSeed
import org.pacer.motion.RealismToggles
import org.pacer.motion.RouteDraft
import org.pacer.motion.RouteJson
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import org.pacer.motion.SpeedPlan
import org.pacer.motion.StraightLine
import java.util.Locale

/**
 * The operation page: the route on a map, the simulation controls, and a marker for where
 * the simulated subject currently is.
 *
 * Tapping appends a waypoint, dragging one moves it, and a long press elsewhere moves a
 * running straight-line simulation. The draft is saved on every change so the other screen
 * sees it, and it is the single source of truth for the route.
 *
 * The map itself comes from [RouteMaps], so this page knows nothing about osmdroid or AMap
 * beyond the interface: which map a build gets depends on whether it carries a key.
 *
 * The live marker is DERIVED, not read from the module: it is computed with the same
 * core-motion code the hook side runs, from the same stored inputs, so a marker that
 * disagreed with what the target app is told would be a bug in one shared formula rather
 * than drift between two copies.
 */
class MapActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft
    private lateinit var info: TextView
    private lateinit var speedField: EditText
    private lateinit var loopCheck: CheckBox

    private var routeMap: RouteMap? = null

    /** One lap of the stored run, rebuilt only when the stored run changes. */
    private class LiveLap(val key: String, val samples: List<FixSample>)

    private var liveLap: LiveLap? = null

    /** The run [liveLapSeconds] belongs to, and one lap's length for it. */
    private var liveLapRunStartedAt: Long = 0L
    private var liveLapSeconds: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        draft = store.load()
        PacerConfigClient.init()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 8) }
        root.addView(info)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("Undo last point") {
            if (draft.size > 0) draft.removeAt(draft.size - 1)
            store.save(draft)
            render()
        })
        bar.addView(button("Clear route") {
            draft.clear()
            store.save(draft)
            render()
        })
        root.addView(bar)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Start this route") { startSimulation() })
        controls.addView(button("Stop") {
            PacerConfigClient.stop()
            info.text = "Simulation stopped."
            updateLivePosition()
        })
        controls.addView(TextView(this).apply { text = "  Speed m/s "; textSize = 13f })
        speedField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.0")
            width = 140
        }
        controls.addView(speedField)
        loopCheck = CheckBox(this).apply { text = "Loop" }
        controls.addView(loopCheck)
        root.addView(controls)

        val map = try {
            RouteMaps.create(this)
        } catch (t: Throwable) {
            null
        }
        routeMap = map
        if (map != null) {
            map.onCreate(savedInstanceState)
            map.onTap = { point ->
                draft.add(point)
                store.save(draft)
                render()
            }
            map.onWaypointDragEnd = { index, point ->
                if (draft.replaceAt(index, point)) {
                    store.save(draft)
                    render()
                }
            }
            map.onLongPress = { point ->
                if (PacerConfigClient.canTeleport()) {
                    PacerConfigClient.teleport(point.lat, point.lon)
                    info.text = String.format(Locale.US, "Simulation moved to %.5f, %.5f.", point.lat, point.lon)
                } else {
                    info.text = "Long press moves a running straight-line simulation here; none is running."
                }
            }
            map.centerOn(draft.points.lastOrNull() ?: DEFAULT_CENTER)
            root.addView(
                map.view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        } else {
            root.addView(
                TextView(this).apply {
                    text = "No map backend could start on this build. The route can still be edited " +
                        "from the previous screen."
                    textSize = 14f
                    setPadding(24, 24, 24, 24)
                },
            )
        }

        setContentView(root)
        render()
        startLivePolling()
        // The controls open on the stored run, for the same reason the other screen does: a
        // screen that opens on its own defaults silently rewrites the user's settings the
        // next time it starts something.
        PacerConfigClient.onReady {
            runOnUiThread {
                val stored = PacerConfigClient.stored() ?: return@runOnUiThread
                if (stored.speed > 0.0) {
                    speedField.setText(String.format(Locale.US, "%.1f", stored.speed))
                }
                loopCheck.isChecked = stored.loopRoute
            }
        }
    }

    override fun onResume() {
        super.onResume()
        routeMap?.onResume()
        // Reload in case another screen changed the draft while we were away.
        draft = store.load()
        render()
    }

    override fun onPause() {
        routeMap?.onPause()
        super.onPause()
    }

    /** Redraws the route from the draft; the draft is the single source. */
    private fun render() {
        updateInfo()
        routeMap?.showRoute(draft.points)
        updateLivePosition()
    }

    private fun startSimulation() {
        val route = draft.toRoutePath()
        if (route == null) {
            info.text = "Add at least two waypoints before starting."
            return
        }
        val speed = speedField.text.toString().toDoubleOrNull()?.coerceIn(0.1, 30.0) ?: 3.0
        // Everything the app does not own here comes from the stored configuration:
        // starting a route from this screen must not silently reset the signal-realism
        // switches or the interval plan the user chose on the other screen.
        val stored = PacerConfigClient.stored()
        PacerConfigClient.start(
            lat = route.points.first().lat,
            lon = route.points.first().lon,
            speed = speed,
            bearing = 0f,
            accuracy = stored?.accuracy ?: 5f,
            altitudeMeters = stored?.altitude ?: 0.0,
            routeJson = draft.toJson(),
            plan = stored?.plan ?: "",
            loopRoute = loopCheck.isChecked,
            mockBluetooth = stored?.mockBluetooth ?: true,
            stepSimulation = stored?.stepSimulation ?: true,
            realism = stored?.realism ?: RealismToggles(),
        )
        info.text = String.format(
            Locale.US,
            "Simulating %d waypoints at %.1f m/s%s.",
            draft.size,
            speed,
            if (loopCheck.isChecked) ", repeating" else "",
        )
        updateLivePosition()
    }

    // ---------------------------------------------------------------------
    // Live position
    // ---------------------------------------------------------------------

    private fun startLivePolling() {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    updateLivePosition()
                } catch (_: Throwable) {
                } finally {
                    handler.postDelayed(this, LIVE_POLL_MS)
                }
            }
        }
        handler.postDelayed(tick, LIVE_POLL_MS)
    }

    /** Moves the live marker to the position the simulation is reporting now. */
    private fun updateLivePosition() {
        val map = routeMap ?: return
        val stored = PacerConfigClient.stored()
        if (stored == null || !stored.active) {
            map.showLive(null)
            return
        }
        map.showLive(livePoint(stored))
    }

    /** Where the subject is, from the stored run: route passes, or the straight line. */
    private fun livePoint(stored: PacerConfigClient.Stored): GeoPoint? {
        val elapsed = ((System.currentTimeMillis() - stored.startedAtMs).coerceAtLeast(0L)) / 1000.0
        val routePath = storedRoute(stored)
        if (routePath != null) {
            // The SAME arithmetic the module runs (RouteEngine), including the seed: the
            // module rebuilds each lap from MotionSeed.forRun(start, lap), so a marker that
            // used any other seed would show a position the target app was never given.
            // The lap length is fixed on the first build of a run, exactly as there.
            if (stored.startedAtMs != liveLapRunStartedAt) {
                liveLapRunStartedAt = stored.startedAtMs
                liveLapSeconds = 0.0
                liveLap = null
            }
            val lap = MotionSamples.lapAt(elapsed, if (stored.loopRoute) liveLapSeconds else 0.0)
            val key = liveKey(stored) + "|lap=" + lap.index
            val samples = liveLap?.takeIf { it.key == key }?.samples
                ?: buildLap(stored, routePath, key, lap.index)
            if (samples.isEmpty()) return null
            val base = MotionSamples.at(samples, lap.offsetSec) ?: return null
            return MotionSamples.afterLaps(
                base,
                lap.index,
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
    ): List<FixSample> {
        val samples = RouteSimulator(
            routePath,
            MotionConfig(
                baseSpeedMps = if (stored.speed > 0.0) stored.speed else 3.0,
                plan = SpeedPlan.parse(stored.plan),
            ),
            toggles = stored.realism,
            steadyAccuracyMeters = stored.accuracy,
        ).simulate(dtSec = 1.0, seed = MotionSeed.forRun(stored.startedAtMs, lapIndex))
        if (stored.loopRoute && liveLapSeconds <= 0.0) {
            liveLapSeconds = samples.lastOrNull()?.tSec ?: 0.0
        }
        liveLap = LiveLap(key, samples)
        return samples
    }

    private fun storedRoute(stored: PacerConfigClient.Stored): RoutePath? {
        if (stored.routeJson.isBlank()) return null
        return try {
            val points = RouteJson.decode(stored.routeJson)
            if (points.size >= 2) RoutePath(points) else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Everything that changes the samples, so one lap is rebuilt only when it must be. */
    private fun liveKey(stored: PacerConfigClient.Stored): String =
        stored.startedAtMs.toString() + "|" + stored.routeJson + "|" + stored.speed + "|" +
            stored.plan + "|" + stored.loopRoute + "|" + stored.realism + "|" + stored.accuracy

    private fun updateInfo() {
        info.text = String.format(
            Locale.US,
            "Tap to append a waypoint; drag a waypoint to move it; long press elsewhere to move a " +
                "running simulation here.  Waypoints: %d   Length: %.0f m   Map: %s",
            draft.size,
            draft.totalLengthMeters(),
            routeMap?.name ?: "none",
        )
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private companion object {
        val DEFAULT_CENTER = GeoPoint(39.9042, 116.4074)

        /** How often the live marker is moved; the simulation advances in seconds. */
        const val LIVE_POLL_MS = 1000L
    }
}
