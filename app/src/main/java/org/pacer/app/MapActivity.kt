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
import org.pacer.motion.GeoMath
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
import kotlin.concurrent.thread

/**
 * The operation page: the route on a map, the simulation controls, and a marker for where
 * the simulated subject currently is.
 *
 * TWO LAYERS OF ROUTE, on purpose. The draft holds the few waypoints the user placed and
 * drags; the stored road path connects them along the streets. Editing stays a matter of
 * a handful of handles, while what is simulated follows roads instead of cutting across
 * blocks. [RouteStore.geometry] decides which one a run uses, and both screens ask it, so
 * the map, the overview and the injected track cannot describe different routes.
 *
 * Tapping appends a waypoint, dragging one moves it, and a long press elsewhere moves a
 * running straight-line simulation. The draft is saved on every change so the other screen
 * sees it.
 *
 * The map itself comes from [RouteMaps], so this page knows nothing about osmdroid or AMap
 * beyond the interface: which map a build gets depends on whether it carries a key.
 *
 * The live marker is DERIVED, not read from the module: it is computed with the same
 * core-motion code and the same run seed the hook side uses, from the same stored inputs,
 * so a marker that disagreed with what the target app is told would be a bug in one shared
 * formula rather than drift between two copies.
 */
class MapActivity : Activity() {

    private lateinit var store: RouteStore
    private lateinit var draft: RouteDraft
    private lateinit var info: TextView
    private lateinit var speedField: EditText
    private lateinit var loopCheck: CheckBox

    private lateinit var placeField: EditText
    private lateinit var addPlaceButton: Button

    /** The place the last search found; null until one does. */
    private var foundPlace: Place? = null

    /** Incremented per search, so a slower earlier answer cannot replace a newer one. */
    private var searchToken = 0

    /** Travel mode for road routing; the enum names are the accepted values. */
    private lateinit var profileField: EditText
    private lateinit var roadButton: Button

    /** Set while a routing request is in flight. */
    private var routing = false

    /** Drives the live marker, and only while this page is visible. */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Set between onResume and onPause. The tick clears its own re-post from this rather
     * than relying on removeCallbacks alone: a tick already running when the page is
     * paused would otherwise schedule the next one afterwards.
     */
    private var polling = false

    private val liveTick = object : Runnable {
        override fun run() {
            try {
                updateLivePosition()
            } catch (_: Throwable) {
            } finally {
                if (polling) handler.postDelayed(this, LIVE_POLL_MS)
            }
        }
    }

    private var routeMap: RouteMap? = null

    /** One lap of the stored run, rebuilt only when the stored run changes. */
    private class LiveLap(val key: String, val samples: List<FixSample>)

    private var liveLap: LiveLap? = null

    /** The run [liveLapSeconds] belongs to, and one lap's length for it. */
    private var liveLapRunStartedAt: Long = 0L
    private var liveLapSeconds: Double = 0.0

    /**
     * The stored route as a [RoutePath].
     *
     * Cached because the marker asks for it once a second and a road route is a
     * thousand-point JSON array: re-parsing it on every tick is work nobody needs, and
     * the poll is the one thing here that runs continuously.
     */
    private var parsedRouteJson: String? = null
    private var parsedRoute: RoutePath? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        draft = store.load()
        PacerConfigClient.init()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 8) }
        root.addView(info)

        // Two rows: one screen width cannot hold four labelled buttons, and a button that
        // is off-screen is a feature that does not exist.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("Undo") {
            editDraft { if (draft.size > 0) draft.removeAt(draft.size - 1) }
        })
        bar.addView(button("Clear") {
            editDraft { draft.clear() }
        })
        bar.addView(button("Fit") { fitRoute() })
        bar.addView(button("Centre") { centreOnSubject() })
        root.addView(bar)

        // Following the roads is the difference between a route and a flight over
        // rooftops. The waypoints stay the thing the user edits; this replaces the line
        // between them with the path a person would actually walk.
        val roads = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        roadButton = button("Follow roads") { planRoadRoute() }
        roads.addView(roadButton)
        profileField = EditText(this).apply {
            hint = "foot / bike / car"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("foot")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        roads.addView(profileField)
        root.addView(roads)

        // Searching is the only way to place a route somewhere the user does not already
        // recognise on the tiles; the result is centred so it can be checked before it is
        // appended.
        val places = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        placeField = EditText(this).apply {
            hint = "Search a place"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        places.addView(placeField)
        places.addView(button("Find") { searchPlace() })
        addPlaceButton = button("Add place") { addFoundPlace() }
        addPlaceButton.isEnabled = false
        places.addView(addPlaceButton)
        root.addView(places)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Start this route") { startSimulation() })
        controls.addView(button("Stop") {
            PacerConfigClient.stop()
            info.text = "Simulation stopped."
            updateLivePosition()
        })
        root.addView(controls)

        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedRow.addView(TextView(this).apply { text = "Speed m/s "; textSize = 13f })
        speedField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        speedRow.addView(speedField)
        loopCheck = CheckBox(this).apply { text = "Loop" }
        speedRow.addView(loopCheck)
        root.addView(speedRow)

        val map = try {
            RouteMaps.create(this)
        } catch (_: Throwable) {
            null
        }
        routeMap = map
        if (map != null) {
            map.onCreate(savedInstanceState)
            map.onTap = { point ->
                editDraft { draft.add(point) }
            }
            map.onWaypointDragEnd = { index, point ->
                editDraft { draft.replaceAt(index, point) }
            }
            map.onLongPress = { point ->
                if (PacerConfigClient.canTeleport()) {
                    PacerConfigClient.teleport(point.lat, point.lon)
                    info.text = String.format(Locale.US, "Simulation moved to %.5f, %.5f.", point.lat, point.lon)
                } else {
                    info.text = "Long press moves a running straight-line simulation here; none is running."
                }
            }
            // A saved route is centred when it is drawn, at the end of onCreate: a fit
            // computed here would run against a view the layout has not measured yet.
            if (draft.size < 2) {
                map.centerOn(draft.points.lastOrNull() ?: DEFAULT_CENTER)
            }
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
        // A route that came back from the last session is fitted once the view has a size;
        // otherwise the user opens the page looking at one arbitrary waypoint.
        if (map != null && draft.size >= 2) {
            map.view.post { if (!isFinishing && !isDestroyed) map.fitRoute() }
        }
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
        // The marker is only worth computing while this page is in front of the user; a
        // tick left running behind another screen burns a full run's rebuild for nothing.
        polling = true
        handler.removeCallbacks(liveTick)
        handler.postDelayed(liveTick, LIVE_POLL_MS)
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(liveTick)
        routeMap?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(liveTick)
        // AMap's view owns a GL surface; osmdroid's detaches its tile provider. Neither is
        // released by the framework, and leaking one leaves a map running behind a dead
        // activity.
        routeMap?.onDestroy()
        super.onDestroy()
    }

    /**
     * Every edit to the draft goes through here.
     *
     * One place, because an edit has a consequence that is easy to forget at each call
     * site: the stored road path was computed from the OLD waypoints, so saving the draft
     * invalidates it. Doing the save and the redraw in the same step is also what keeps
     * the other screen from reading a draft the map has not caught up with.
     */
    private fun editDraft(change: () -> Unit) {
        // A drag that lands on the same coordinate, or an Undo on an empty draft, changes
        // nothing - and saving then would drop a perfectly good road path for no reason.
        val before = draft.toJson()
        val hadRoad = store.roadPath(draft) != null
        change()
        if (draft.toJson() == before) return
        store.save(draft)
        render(afterEdit = hadRoad)
    }

    /**
     * Redraws the route: the road path when there is one, plus the draggable handles.
     *
     * The road path can disappear between draws (any edit to the waypoints drops it), and a
     * map that quietly switches back to straight lines is the kind of change a user does
     * not notice until the track looks wrong. [afterEdit] therefore says so explicitly.
     */
    private fun render(afterEdit: Boolean = false) {
        // Decoded once per redraw: a road path is a thousand-point JSON array, and both
        // the info line and the map want the same answer.
        val road = store.roadPath(draft)
        val geometry = RouteStore.geometryOf(draft, road)
        updateInfo(road)
        // A snapshot, not [RouteDraft.points]: that is a live view over the editable list,
        // and the map keeps what it is handed for its own hit-testing.
        routeMap?.showRoute(geometry, draft.points.toList())
        updateLivePosition()
        if (afterEdit && road == null && draft.size >= 2) {
            info.text = "The waypoints changed, so the road route no longer describes them. " +
                "Press Follow roads to plan it again; until then the route is straight lines."
        }
    }

    private fun startSimulation() {
        if (!draft.isValid) {
            info.text = "Add at least two waypoints before starting."
            return
        }
        // The road path when one was planned, the plain polyline otherwise: the same
        // geometry the map is drawing, so what the user sees is what is simulated.
        val geometry = store.geometry(draft)
        val route = RoutePath(geometry)
        val speed = speedField.text.toString().toDoubleOrNull()?.coerceIn(0.1, 30.0) ?: 3.0
        // Everything the app does not own here comes from the stored configuration:
        // starting a route from this screen must not silently reset the signal-realism
        // switches or the interval plan the user chose on the other screen.
        val stored = PacerConfigClient.stored()
        val started = PacerConfigClient.start(
            lat = route.points.first().lat,
            lon = route.points.first().lon,
            speed = speed,
            bearing = 0f,
            accuracy = stored?.accuracy ?: 5f,
            altitudeMeters = stored?.altitude ?: 0.0,
            routeJson = RouteJson.encode(geometry),
            plan = stored?.plan ?: "",
            loopRoute = loopCheck.isChecked,
            mockBluetooth = stored?.mockBluetooth ?: true,
            stepSimulation = stored?.stepSimulation ?: true,
            realism = stored?.realism ?: RealismToggles(),
        )
        if (!started) {
            info.text = PacerConfigClient.NOT_READY_MESSAGE
            return
        }
        info.text = String.format(
            Locale.US,
            "Simulating %d points through %d waypoints at %.1f m/s%s.",
            geometry.size,
            draft.size,
            speed,
            if (loopCheck.isChecked) ", repeating" else "",
        )
        updateLivePosition()
    }

    // ---------------------------------------------------------------------
    // Map commands
    // ---------------------------------------------------------------------

    /**
     * Plans a path along the roads between the current waypoints and stores it.
     *
     * Nothing about the draft changes: the waypoints the user placed stay the waypoints
     * the editor shows and the handles they drag. Only the line drawn and simulated
     * becomes a street route. The request goes to a third-party service, so it is made
     * on a background thread, exactly once per button press, and any failure leaves the
     * previous geometry in place with the reason on screen.
     */
    private fun planRoadRoute() {
        if (draft.size < 2) {
            info.text = "Add at least two waypoints before planning a road route."
            return
        }
        if (routing) return
        val profile = RoadRoute.Profile.entries.firstOrNull {
            it.name.equals(profileField.text.toString().trim(), ignoreCase = true)
        }
        if (profile == null) {
            info.text = "Profile must be foot, bike or car."
            return
        }
        val waypoints = draft.points.toList()
        // Captured, not read again when the answer arrives: the user can drag a waypoint
        // while a request is in flight, and a path planned through the old waypoints must
        // not then be stored as if it described the new ones.
        val requestedJson = draft.toJson()
        routing = true
        roadButton.isEnabled = false
        info.text = "Planning a ${profile.label.lowercase()} route along the roads..."
        thread {
            val path = RoadRoute.plan(waypoints, profile)
            runOnUiThread {
                routing = false
                roadButton.isEnabled = true
                if (draft.toJson() != requestedJson) {
                    info.text = "The waypoints changed while the road route was being planned, " +
                        "so it was discarded. Press Follow roads again."
                    return@runOnUiThread
                }
                if (path == null) {
                    info.text = "No road route could be planned (offline, or the service refused). " +
                        "The route still follows your waypoints directly."
                    return@runOnUiThread
                }
                store.saveRoadPath(draft, path)
                // Drawn first: render() rewrites the info line, so a message written
                // before it would be replaced by the summary.
                render()
                routeMap?.fitRoute()
                info.text = String.format(
                    Locale.US,
                    "Road route planned: %d points, %.0f m through %d waypoints. It is what the " +
                        "simulation will run.",
                    path.size,
                    lengthOf(path),
                    waypoints.size,
                )
            }
        }
    }

    /** Fits the whole route in view, so a route built from a search is visible at once. */
    private fun fitRoute() {
        val map = routeMap
        if (map == null) {
            info.text = "No map backend is available."
            return
        }
        if (draft.size < 2) {
            info.text = "Add at least two waypoints to fit a route."
            return
        }
        map.fitRoute()
    }

    /**
     * Centres on the simulated subject, or on the last waypoint when nothing is running.
     *
     * Deliberately two different targets: "centre" on the operation page means "show me
     * where the run is", and with no run the only meaningful thing to show is where the
     * route ends.
     */
    private fun centreOnSubject() {
        val map = routeMap ?: return
        val stored = PacerConfigClient.stored()
        val subject = if (stored != null && stored.active) livePoint(stored) else null
        val target = subject ?: draft.points.lastOrNull()
        if (target == null) {
            info.text = "Nothing to centre on yet."
            return
        }
        map.centerOn(target)
        info.text = if (subject != null) {
            String.format(Locale.US, "Centred on the subject at %.5f, %.5f.", target.lat, target.lon)
        } else {
            "Centred on the last waypoint."
        }
    }

    // ---------------------------------------------------------------------
    // Place search
    // ---------------------------------------------------------------------

    private fun searchPlace() {
        val query = placeField.text.toString().trim()
        if (query.isEmpty()) {
            info.text = "Type a place name first."
            return
        }
        info.text = "Searching for \"$query\"..."
        // Nominatim is a blocking HTTP call; the main thread must never make it.
        val token = ++searchToken
        thread {
            val top = PlaceSearch.search(query).firstOrNull()
            runOnUiThread {
                // A second search started while this one was in flight, and its answer is
                // the one the user is waiting for: this one is stale.
                if (token != searchToken) return@runOnUiThread
                foundPlace = top
                addPlaceButton.isEnabled = top != null
                if (top == null) {
                    info.text = "No place matched \"$query\" (no network, or nothing found)."
                    return@runOnUiThread
                }
                info.text = "${top.displayName}\n${top.point.lat}, ${top.point.lon}"
                // Show the hit before appending it: a route is built from several of these
                // and appending the wrong one is not obvious once it is on the map.
                routeMap?.centerOn(top.point)
            }
        }
    }

    private fun addFoundPlace() {
        val place = foundPlace
        if (place == null) {
            info.text = "Search a place first."
            return
        }
        editDraft { draft.add(place.point) }
        info.text = String.format(
            Locale.US,
            "Added %s as waypoint %d.",
            place.displayName.substringBefore(','),
            draft.size,
        )
    }

    // ---------------------------------------------------------------------
    // Live position
    // ---------------------------------------------------------------------

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
        if (stored.routeJson == parsedRouteJson) return parsedRoute
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
     * The route is named by its length and hash rather than its text: a road route is a
     * thousand points, and this is built once a second by the live poll.
     */
    private fun liveKey(stored: PacerConfigClient.Stored): String =
        stored.startedAtMs.toString() + "|" + stored.routeJson.length + ":" +
            stored.routeJson.hashCode() + "|" + stored.speed + "|" +
            stored.plan + "|" + stored.loopRoute + "|" + stored.realism + "|" + stored.accuracy

    private fun updateInfo(road: List<GeoPoint>?) {
        // The length shown is the one a run would cover, not the straight line between
        // the handles: those differ by a lot on a road route, and showing the smaller
        // number would make the counter look wrong a minute into the run.
        val shape = if (road == null) {
            String.format(
                Locale.US,
                "straight between waypoints (%.0f m); press Follow roads to use the streets",
                draft.totalLengthMeters(),
            )
        } else {
            String.format(Locale.US, "along the roads (%.0f m)", lengthOf(road))
        }
        info.text = String.format(
            Locale.US,
            "Tap to append a waypoint; drag a waypoint to move it; long press elsewhere to move a " +
                "running simulation here.\nWaypoints: %d   Route: %s   Map: %s",
            draft.size,
            shape,
            routeMap?.name ?: "none",
        )
    }

    /** Total length of an arbitrary polyline, for the info line. */
    private fun lengthOf(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += GeoMath.haversine(points[i - 1], points[i])
        return total
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
