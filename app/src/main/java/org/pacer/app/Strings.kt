package org.pacer.app

/**
 * The two languages the interface is written in.
 *
 * English is the default, not the system language: the app's own output - route names, log
 * lines, the self-check report - is English, and the choice is stored rather than re-derived
 * on every start.
 */
enum class Language(val tag: String, val label: String) {
    ENGLISH("en", "English"),
    CHINESE("zh", "中文"),
    ;

    companion object {
        val DEFAULT = ENGLISH

        /** The language for a stored tag, or [DEFAULT] when the tag is unknown. */
        fun of(tag: String?): Language = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}

/**
 * The interface strings, one object per language.
 *
 * Not res/values-zh: the UI is built in Kotlin with inline labels, and splitting one screen
 * across XML, literals and a lookup is how half a page ends up untranslated. A field per
 * string makes a missing translation a compile error.
 *
 * Diagnostic text (self-check report, module log) stays English in both.
 */
interface Strings {
    val appTagline: String
    val tabRun: String
    val tabRoute: String
    val tabSettings: String
    val speed: String
    val walk: String
    val run: String
    val ride: String
    val custom: String
    val customSpeed: String
    val customSpeedHint: String
    val route: String
    val routeHint: String
    val routeSummary: String
    val routeSummaryIncomplete: String
    val openMap: String
    val loadSample: String
    val startRecording: String
    val stopRecording: String
    val savedRoutes: String
    val saveRoute: String
    val simulation: String

    /** What the reported-position numbers are, and when a change takes effect. */
    val simulationHint: String
    val unitMeters: String
    val unitDegrees: String
    val unitMps: String
    val resetToDefault: String
    val accuracy: String
    val accuracyHint: String
    val altitude: String
    val altitudeHint: String
    val bearing: String
    val bearingHint: String
    val intervalPlanSection: String
    val loopRoute: String
    val loopRouteHint: String
    val stepSimulation: String
    val stepSimulationHint: String
    val bluetooth: String
    val bluetoothHint: String
    val coordinateSystem: String
    val coordinateSystemHint: String
    val saveOverrides: String
    val signalRealism: String
    val signalRealismHint: String
    val bearingHold: String
    val bearingHoldHint: String
    val altitudeDrift: String
    val altitudeDriftHint: String
    val accuracyWarmup: String
    val accuracyWarmupHint: String
    val satelliteJitter: String
    val satelliteJitterHint: String
    val signalDropout: String
    val signalDropoutHint: String

    /** The straight-line run the joystick drives, named for how it is controlled. */
    val startJoystickMode: String
    val startRoute: String
    val pauseRoute: String
    val resumeRoute: String
    val stopSimulation: String
    val showJoystick: String

    /** Short forms for the pinned control bar, where the full label wraps to three lines. */
    val startRouteShort: String
    val stopShort: String
    val joystickShort: String
    val preview: String
    val audit: String
    val selfCheck: String
    val runSelfCheck: String
    val selfCheckHint: String

    /** The one failure a user is most likely to meet: no module behind the config channel. */
    val configNotReady: String

    /** Why the app asks for location and radio permissions at startup, and the two answers. */
    val permissionTitle: String
    val permissionBody: String
    val permissionGrant: String
    val permissionLater: String

    /** The interval plan's folded header announces what tapping it will do. */
    val planExpand: String
    val planCollapse: String

    /** The per-kilometre pace curve, which is a second shape the same editor can express. */
    val paceSection: String
    val paceHint: String
    val paceKilometre: String
    val paceAddKilometre: String
    val paceRemove: String
    val paceEnableCurve: String
    val paceRemoveCurve: String
    val paceTotals: String
    val paceInvalid: String
    val paceSummary: String

    /** The floating strip's dismiss button, which only a failure shows. */
    val dismissNotice: String

    /** Coordinate-override outcomes, kept here rather than built as English sentences in code. */
    val overridesCleared: String
    val overridesUnreadable: String
    val overridesSaved: String
    val overridesSavedPartial: String

    /** Saved-route outcomes. */
    val routeReplaced: String
    val routeSavedAs: String
    val routeLoadedAs: String
    val nameRequiredWithLimit: String

    /** What a successful start says: the run that is now happening, in one line. */
    val startedJoystickMode: String
    val startedJoystickModeWithPlan: String
    val startedRoute: String
    val startedRouteWithPlan: String
    val planPassSummary: String
    val planTotalSummary: String
    val planTotalForever: String
    val mapRouteSummary: String

    /** What the joystick window says about who owns the run it would steer. */
    val joystickLockedRoute: String
    val joystickLockedRoutePaused: String
    val joystickLockedPlan: String
    val joystickReady: String

    /** Why recording cannot start yet: it follows a hand-steered run and nothing else. */
    val recordNeedsJoystickRoute: String
    val recordNeedsJoystickPlan: String
    val recordNeedsJoystickIdle: String
    val recordedSummary: String

    /** A thrown exception, and the two lines the self-check writes when it is done. */
    val unexpectedFailure: String
    val selfCheckCrashed: String
    val selfCheckDone: String
    val statusIdle: String
    val statusRunning: String
    val statusPaused: String
    val language: String
    val continueLabel: String
    val dontShowAgain: String
    val environment: String
    val checkEnvironment: String
    val checkingEnvironment: String
    val rootGranted: String
    val rootDenied: String
    val rootUnknown: String
    val moduleBound: String
    val moduleUnbound: String
    val routeNeedsTwoPoints: String
    val routeName: String
    val noSavedRoutes: String
    val routeGone: String
    val waypointCountSuffix: String
    val renamed: String
    val deleted: String
    val placeWaypointFirst: String
    val recording: String
    val nothingRecorded: String
    val needTwoWaypoints: String
    val grantOverlay: String
    val auditing: String
    val simulating: String
    val statusConfigIdle: String
    val undo: String
    val clear: String
    val remove: String
    val fit: String
    val centre: String
    val start: String
    val stop: String
    val speedMpsLabel: String
    val loopShort: String
    val nothingToCentre: String
    val routeCannotMove: String
    val movedTo: String
    val longPressIdle: String
    val routePausedWhereItStands: String
    val routeResumedFromPoint: String
    val mapGestureHint: String
    val waypointSelected: String
    val centredOnSubject: String
    val centredOnLast: String
    val simulatingWithPlan: String
    val simulatingAtSpeed: String
    val repeatingSuffix: String
    val noTilesForLayer: String
    val simControlChannel: String
    val simControlText: String
    val lockRouteOwns: String
    val lockRoutePaused: String
    val lockPlanOwns: String
    val lockNothingToSteer: String
    val clearRouteTitle: String
    val clearRouteMessage: String
    val cancel: String
    val replaceRunTitle: String
    val replaceRunMessage: String
    val replaceRunConfirm: String
    val planNoSteps: String
    val planAddStep: String
    val planRemove: String
    val planSpeed: String
    val planDuration: String
    val planRepeats: String
    val planForever: String
    val planPresets: String
    val planInvalid: String
    val planNone: String
    val planSummary: String
    val planInvalidShort: String

    /** Button labels for the presets, keyed by [PlanDraft.Preset.id]. */
    val planPresetNames: Map<String, String>
    val planTotals: String
    val planTotalsForever: String
}

/** The English strings; also the fallback for a language that has not been written yet. */
object EnglishStrings : Strings {
    override val appTagline = "Simulate movement for testing."
    override val tabRun = "Simulate"
    override val tabRoute = "Route"
    override val tabSettings = "Settings"
    override val speed = "Steady speed"
    override val walk = "Walk 1.4"
    override val run = "Run 3.0"
    override val ride = "Ride 6.0"
    override val custom = "Custom"
    override val route = "Route"
    override val routeHint =
        "Waypoints are placed on the map: pan to a place, tap to append one, drag a " +
            "handle to move it. This list is what the map has drawn."
    override val routeSummary = "Waypoints: %d   Length: %.0f m%s"
    override val routeSummaryIncomplete = "   (need two distinct points to run a route)"
    override val openMap = "Open the map and controls"
    override val loadSample = "Load sample route (Urumqi)"
    override val startRecording = "Start recording from the joystick"
    override val stopRecording = "Stop recording"
    override val savedRoutes = "Saved routes"
    override val saveRoute = "Save current route as..."
    override val simulation = "What other apps see"
    override val simulationHint =
        "These three numbers are written into the simulated fix, so a target app reads them " +
            "as if the phone had measured them. A change takes effect when the next " +
            "simulation starts, not while one is running."
    override val unitMeters = "m"
    override val unitDegrees = "deg"
    override val unitMps = "m/s"
    override val resetToDefault = "Reset"
    override val customSpeed = "Custom speed"
    override val customSpeedHint =
        "Used only while Custom is selected. Below 0.3 m/s the subject counts as standing " +
            "still and only jitters in place."
    override val accuracy = "Horizontal accuracy"
    override val accuracyHint =
        "Goes into the fix's accuracy field: how far off the position may be. Apps draw a " +
            "circle of this radius and decide whether to trust the point, so a smaller number " +
            "claims a better fix. It does not change where the subject walks."
    override val altitude = "Starting altitude"
    override val altitudeHint =
        "The elevation the fix reports, as if the ground were that high. 0 is sea level. " +
            "It does not affect the track on the map; switch on the drift below to make it wander."
    override val bearing = "Heading joystick mode starts on"
    override val bearingHint =
        "0 is north and the number grows clockwise: 90 east, 180 south, 270 west. This is the " +
            "heading the run starts on; the knob changes it afterwards, and a route sets its own."
    override val intervalPlanSection = "Interval plan"
    override val loopRoute = "Repeat the route (laps)"
    override val loopRouteHint =
        "A route run starts over at the first waypoint instead of stopping at the end. " +
            "Distance and steps keep growing across laps."
    override val stepSimulation = "Step and sensor simulation"
    override val stepSimulationHint =
        "The step counter, the step detector and the accelerometer follow the route."
    override val bluetooth = "Simulate the Bluetooth surroundings"
    override val bluetoothHint =
        "Without this, Bluetooth scans still report the real surroundings while WiFi and " +
            "cells are simulated."
    override val coordinateSystem = "Per-app coordinate system"
    override val coordinateSystemHint =
        "Format: package=datum, several entries separated by semicolons. The datum is one of " +
            "AUTO, WGS84, GCJ02 or BD09. A fix carries no datum of its own, so an app drawing " +
            "it on a Chinese base map assumes GCJ-02 while one drawing it on a Google base map " +
            "assumes WGS-84, and the two differ by about half a kilometre; AUTO follows the API " +
            "being hooked. Saved immediately, unlike the numbers above."
    override val saveOverrides = "Save coordinate overrides"
    override val signalRealism = "Signal realism"
    override val signalRealismHint =
        "Each of these makes the track look less machine-made. Switch one off to keep that " +
            "part of the signal steady."
    override val bearingHold = "Hold the heading while standing still"
    override val bearingHoldHint =
        "A stationary receiver keeps reporting its last course instead of snapping to north."
    override val altitudeDrift = "Drift the altitude"
    override val altitudeDriftHint = "A slow random walk of a few centimetres per second."
    override val accuracyWarmup = "Warm up the accuracy"
    override val accuracyWarmupHint =
        "30 m converging to the accuracy above over the first half minute, as a receiver does " +
            "when it has just acquired the sky."
    override val satelliteJitter = "Vary the satellite count"
    override val satelliteJitterHint =
        "A plausible 7 to 14 instead of one fixed number; off, it always reports 11."
    override val signalDropout = "Drop the satellite count now and then"
    override val signalDropoutHint =
        "For a few seconds the fix reports 3 to 10 satellites instead of the usual count. " +
            "The position keeps being delivered: this thins the sky, not the fix."
    override val startJoystickMode = "Joystick mode from the first waypoint"
    override val startRoute = "Start humanized route simulation"
    override val pauseRoute = "Pause route"
    override val resumeRoute = "Resume route"
    override val stopSimulation = "Stop simulation"
    override val showJoystick = "Show floating joystick"
    override val startRouteShort = "Run"
    override val stopShort = "Stop"
    override val joystickShort = "Joystick"
    override val preview = "Preview engine locally (no hooks)"
    override val audit = "Audit this track"
    override val selfCheck = "Self-check"
    override val runSelfCheck = "Run self-check"
    override val selfCheckHint =
        "Reads this app's own location, WiFi, cell, Bluetooth and step counter back through " +
            "the public APIs and compares them with what the engine says they must be. This " +
            "app is deliberately outside the module's scope, so what it sees is the " +
            "system-wide result."
    override val configNotReady =
        "The simulation was not started: the module's config channel is not bound yet. " +
            "Try again in a moment; if it keeps happening, check that Pacer is enabled and " +
            "that this app (or the target app) is in its scope."
    override val permissionTitle = "Permissions Pacer needs"
    override val permissionBody =
        "Pacer reads the simulated location and radio state back through the same public " +
            "APIs a target app uses, so it cannot show or verify anything without these. " +
            "Location is also what puts the marker on the map. Nothing is sent anywhere."
    override val permissionGrant = "Grant"
    override val permissionLater = "Not now"
    override val planExpand = "Open the interval plan"
    override val planCollapse = "Close the interval plan"
    override val paceSection = "Pace per kilometre"
    override val paceHint =
        "The pace each kilometre is run at. The overall time is the sum, so this is also how " +
            "an overall target is met: type the paces that add up to it."
    override val paceKilometre = "km %d"
    override val paceAddKilometre = "+ Add a kilometre"
    override val paceRemove = "Remove this kilometre"
    override val paceEnableCurve = "Use a per-kilometre pace"
    override val paceRemoveCurve = "Stop using per-kilometre pace"
    override val paceTotals = "%.0f s over %.1f km, mean %s"
    override val paceInvalid = "Every kilometre needs a pace between 2:00 and 60:00."
    override val paceSummary = "%d km, mean %s"
    override val dismissNotice = "OK"
    override val overridesCleared = "Coordinate overrides cleared; AUTO applies."
    override val overridesUnreadable = "Nothing in that text was a valid entry, so nothing was saved."
    override val overridesSaved = "Saved %d coordinate override(s)."
    override val overridesSavedPartial =
        "Saved %d override(s); %d entr(y/ies) were unreadable and are shown dropped."
    override val routeReplaced = "Replaced \"%s\"."
    override val routeSavedAs = "Saved \"%s\" (%d waypoints, %.0f m)."
    override val routeLoadedAs = "\"%s\" (%d %s)"
    override val nameRequiredWithLimit = "A route name is required, %d characters at most."
    override val startedJoystickMode = "Joystick mode started at %.5f, %.5f, heading %.0f deg, %.2f m/s."
    override val startedJoystickModeWithPlan =
        "Joystick mode started at %.5f, %.5f, heading %.0f deg; the speed comes from the plan.%s"
    override val startedRoute = "Following %d waypoints (%.0f m) at %.2f m/s."
    override val startedRouteWithPlan =
        "Following %d waypoints (%.0f m); the plan sets the speed on top of %.2f m/s.%s"
    override val planPassSummary = "%.0f s / %.0f m per pass"
    override val planTotalSummary = ", %.0f m in total"
    override val planTotalForever = ", repeating for the whole route"
    override val mapRouteSummary = "Waypoints: %d   Length: %s   Map: %s"
    override val joystickLockedRoute =
        "Joystick shown. A route run supplies its own speed and heading, so the knob is locked " +
            "until the route is stopped. Drag the ring to move it, tap to stop."
    override val joystickLockedRoutePaused =
        "Joystick shown. The route is paused, so the subject stands still; the knob unlocks " +
            "only if you stop the route. Drag the ring to move it, tap to stop."
    override val joystickLockedPlan =
        "Joystick shown. The interval plan supplies the speed, so the knob is locked until the " +
            "plan run is stopped. Drag the ring to move it, tap to stop."
    override val joystickReady =
        "Joystick shown. Drag the knob to steer (releasing keeps the last heading), drag the " +
            "ring to move it, tap to stop."
    override val recordNeedsJoystickRoute =
        "Recording follows the joystick, and a route run owns its own motion. Stop the route " +
            "and start joystick mode to record one."
    override val recordNeedsJoystickPlan =
        "Recording follows the joystick, but the interval plan owns the speed. Clear the plan " +
            "and start joystick mode to record."
    override val recordNeedsJoystickIdle =
        "Start joystick mode first, then record it."
    override val recordedSummary =
        "Recorded %d waypoints over %.0f m in %d s. It is in the editor now."
    override val unexpectedFailure = "Something went wrong: %s: %s"
    override val selfCheckCrashed = "The self-check itself crashed: %s: %s"
    override val selfCheckDone = "Self-check: %s (report copied to the clipboard)"
    override val statusIdle = "Idle: no simulation is running."
    override val statusRunning = "Running"
    override val statusPaused = "Paused"
    override val language = "Language"
    override val continueLabel = "Continue"
    override val dontShowAgain = "Don't show this again"
    override val environment = "Environment"
    override val checkEnvironment = "Check this device"
    override val checkingEnvironment = "Checking..."
    override val rootGranted = "Root: granted"
    override val rootDenied = "Root: not granted"
    override val rootUnknown = "Root: unknown"
    override val moduleBound = "Module: connected"
    override val moduleUnbound = "Module: not connected"
    override val routeNeedsTwoPoints =
        "A named route needs two distinct waypoints; this draft is not one yet."
    override val routeName = "Route name"
    override val noSavedRoutes = "No saved routes yet."
    override val routeGone = "That route is no longer in the store."
    override val waypointCountSuffix = "waypoints"
    override val renamed = "Renamed."
    override val deleted = "Deleted."
    override val placeWaypointFirst =
        "Place a waypoint on the map first; a run needs a start."
    override val recording =
        "Recording. Steer with the floating joystick; the route is saved when you stop."
    override val nothingRecorded = "Nothing was recorded: the subject did not move far enough."
    override val needTwoWaypoints =
        "Add at least two waypoints that are not the same place before starting a route."
    override val grantOverlay = "Grant overlay permission, then tap again."
    override val auditing = "Auditing the track..."
    override val simulating = "Simulating..."
    override val statusConfigIdle = "Idle: simulation stopped."
    override val undo = "Undo"
    override val clear = "Clear"
    override val remove = "Remove"
    override val fit = "Fit"
    override val centre = "Centre"
    override val start = "Start"
    override val stop = "Stop"
    override val speedMpsLabel = "Speed m/s"
    override val loopShort = "Loop"
    override val nothingToCentre = "Nothing to centre on yet."
    override val routeCannotMove =
        "This run follows a route, so it cannot be moved with a long press. Edit its " +
            "waypoints instead, or stop it and switch to joystick mode."
    override val movedTo = "Simulation moved to %.5f, %.5f."
    override val longPressIdle =
        "Long press moves a running joystick-mode simulation here; none is running."
    override val routePausedWhereItStands =
        "Route paused where it stands; the subject reports no speed until it resumes."
    override val routeResumedFromPoint = "Route resumed from the point it stopped at."
    override val mapGestureHint =
        "Drag to pan, pinch to zoom, tap to append a waypoint, drag a handle to move it."
    override val waypointSelected =
        "Waypoint %d of %d selected. Drag it to move, Remove deletes it, tap again to deselect."
    override val centredOnSubject = "Centred on the subject at %.5f, %.5f."
    override val centredOnLast = "Centred on the last waypoint."
    override val simulatingWithPlan =
        "Simulating %d waypoints with the interval plan as the speed curve%s."
    override val simulatingAtSpeed = "Simulating %d waypoints at %.1f m/s%s."
    override val repeatingSuffix = ", repeating"
    override val noTilesForLayer = "%s has no %s tiles."
    override val simControlChannel = "Simulation control"
    override val simControlText = "Speed and heading controls are on screen."
    override val lockRouteOwns = "Route run: speed and heading come from the route."
    override val lockRoutePaused = "Route paused: the subject stands still until the route resumes."
    override val lockPlanOwns = "Interval plan running: speed comes from the plan, not the knob."
    override val lockNothingToSteer = "No run to steer."
    override val clearRouteTitle = "Clear every waypoint?"
    override val clearRouteMessage = "This removes all %d waypoints from the draft."
    override val cancel = "Cancel"
    override val replaceRunTitle = "A simulation is already running"
    override val replaceRunMessage =
        "Starting again replaces it: the run restarts from the beginning and the progress " +
            "so far is lost."
    override val replaceRunConfirm = "Replace"
    override val planNoSteps = "No steps yet. Add one below."
    override val planAddStep = "+ Add a step"
    override val planRemove = "Remove"
    override val planSpeed = "Speed"
    override val planDuration = "Duration"
    override val planRepeats = "Repeat"
    override val planForever = "forever"
    override val planPresets = "Start from a preset"
    override val planInvalid = "This plan does not run: every step needs a speed above zero."
    override val planNone = "steady speed"
    override val planSummary = "%d steps x%s"
    override val planInvalidShort = "not runnable"
    override val planPresetNames = mapOf(
        "human" to "Human run",
        "official" to "Official",
        "reps" to "400m reps",
        "fartlek" to "Fartlek",
        "negative" to "Negative",
    )
    override val planTotals = "One pass %.0f s / %.0f m, total %.0f min / %.2f km"
    override val planTotalsForever = "One pass %.0f s / %.0f m, repeating for the whole route"
}

/** The Chinese strings. */
object ChineseStrings : Strings {
    override val appTagline = "用于测试的运动模拟。"
    override val tabRun = "模拟"
    override val tabRoute = "路线"
    override val tabSettings = "设置"
    override val speed = "常规速度"
    override val walk = "步行 1.4"
    override val run = "跑步 3.0"
    override val ride = "骑行 6.0"
    override val custom = "自定义"
    override val customSpeed = "自定义速度"
    override val customSpeedHint =
        "只在选中「自定义」时使用。低于 0.3 m/s 会被当作原地静止，只在原地微动。"
    override val route = "路线"
    override val routeHint =
        "路径点在地图上放置：拖动到目标位置，点击追加一个点，拖动圆点可以移动它。这里只是列出地图上已画好的点。"
    override val routeSummary = "路径点：%d   长度：%.0f 米%s"
    override val routeSummaryIncomplete = "   （需要两个不同的点才能跑路线）"
    override val openMap = "打开地图与控制器"
    override val loadSample = "载入示例路线（乌鲁木齐）"
    override val startRecording = "用摇杆开始录制"
    override val stopRecording = "停止录制"
    override val savedRoutes = "已保存路线"
    override val saveRoute = "将当前路线另存为..."
    override val simulation = "其它应用看到的定位数值"
    override val simulationHint =
        "这三项会写进模拟出来的定位结果，目标应用会把它当成手机自己测到的。改完不会立刻" +
            "生效，下一次开始模拟时才用新值。"
    override val unitMeters = "米"
    override val unitDegrees = "度"
    override val unitMps = "米/秒"
    override val resetToDefault = "恢复默认"
    override val accuracy = "水平精度"
    override val accuracyHint =
        "写进定位结果的精度字段，含义是「这个点大约差多少米」。应用会按这个半径画误差圈、" +
            "判断能不能信这个点，所以填得越小表示定位越准。它不改变人往哪儿走。"
    override val altitude = "起始海拔"
    override val altitudeHint =
        "定位结果里报告的海拔高度，相当于说脚下地面有这么高。0 表示海平面。它不影响地图上" +
            "的轨迹；想让海拔自己浮动，打开下面的「海拔缓慢漂移」。"
    override val bearing = "摇杆模式的起始方向"
    override val bearingHint =
        "0 是正北，数字顺时针增加：90 正东、180 正南、270 正西。这是运行开始时的方向；" +
            "之后由摇杆改变，跑路线时方向由路线自己决定。"
    override val intervalPlanSection = "变速计划"
    override val loopRoute = "循环跑这条路线"
    override val loopRouteHint = "跑完最后一站后回到第一个路径点继续，而不是停下。距离和步数会继续累加。"
    override val stepSimulation = "步数与传感器模拟"
    override val stepSimulationHint = "计步器、步行检测和加速度计会跟随路线变化。"
    override val bluetooth = "模拟蓝牙环境"
    override val bluetoothHint = "关闭时，WiFi 和基站仍然被模拟，但蓝牙扫描会报告真实周边。"
    override val coordinateSystem = "按应用指定坐标系"
    override val coordinateSystemHint =
        "格式：包名=坐标系，多项用分号隔开。坐标系可填 AUTO、WGS84、GCJ02 或 BD09。" +
            "定位数据本身不带基准，画在中国底图上的应用会当成 GCJ-02、画在谷歌底图上的会" +
            "当成 WGS-84，两者相差约五百米；AUTO 表示跟随被 hook 的接口。" +
            "点保存后立刻生效（上面那三项则要等下次开始模拟）。"
    override val saveOverrides = "保存坐标系覆盖"
    override val signalRealism = "信号真实度"
    override val signalRealismHint = "每项都能让轨迹少一点机器味。关掉某项就让那部分信号保持稳定。"
    override val bearingHold = "静止时保持朝向"
    override val bearingHoldHint = "静止的接收机继续报告上一次的航向，而不是跳到正北。"
    override val altitudeDrift = "海拔缓慢漂移"
    override val altitudeDriftHint = "每秒几厘米的随机游走。"
    override val accuracyWarmup = "精度预热"
    override val accuracyWarmupHint =
        "前三十秒从 30 米收敛到上面填的精度，模拟接收机刚锁定卫星时的状态。"
    override val satelliteJitter = "卫星数量浮动"
    override val satelliteJitterHint = "在 7 到 14 之间浮动，而不是固定一个数字；关掉后固定报告 11 颗。"
    override val signalDropout = "偶尔压低的卫星数"
    override val signalDropoutHint =
        "有几秒钟只报告 3 到 10 颗卫星，而不是平时的数量。定位本身照常下发：这一项只是让" +
            "天上的卫星看起来变少，不是真的中断定位。"
    override val startJoystickMode = "摇杆模式（从第一个路径点出发）"
    override val startRoute = "开始拟人化路线模拟"
    override val pauseRoute = "暂停路线"
    override val resumeRoute = "继续路线"
    override val stopSimulation = "停止模拟"
    override val showJoystick = "显示悬浮摇杆"
    override val startRouteShort = "运行"
    override val stopShort = "停止"
    override val joystickShort = "摇杆"
    override val preview = "本地预览引擎（不经过 hook）"
    override val audit = "审查这条轨迹"
    override val selfCheck = "自检"
    override val runSelfCheck = "运行自检"
    override val selfCheckHint =
        "通过公开接口读回本应用自己看到的定位、WiFi、基站、蓝牙和计步器，并与引擎声明的值比对。" +
            "本应用刻意不在模块作用域内，所以它看到的是系统层面的结果。"
    override val configNotReady =
        "模拟没能开始：模块的配置通道还没连上。稍等再试；如果一直这样，请确认 Pacer 已在 " +
            "LSPosed 里启用，并且这个应用（或目标应用）在它的作用域里。"
    override val permissionTitle = "Pacer 需要的权限"
    override val permissionBody =
        "Pacer 要通过与目标应用相同的公开接口读回模拟出来的定位和无线状态，没有这些权限就没法" +
            "显示或核对任何东西；地图上的位置标记也需要定位权限。所有数据都不会上传到任何地方。"
    override val permissionGrant = "授予"
    override val permissionLater = "以后再说"
    override val planExpand = "展开变速计划"
    override val planCollapse = "收起变速计划"
    override val paceSection = "按公里配速"
    override val paceHint =
        "每一公里各跑多快。总时长就是这些配速之和，所以「整体目标」也是靠它达成的：" +
            "把配速填成加起来等于目标的那个组合。"
    override val paceKilometre = "第 %d 公里"
    override val paceAddKilometre = "＋ 增加一公里"
    override val paceRemove = "删除这一公里"
    override val paceEnableCurve = "改用按公里配速"
    override val paceRemoveCurve = "不再使用按公里配速"
    override val paceTotals = "共 %.0f 秒 / %.1f 公里，平均 %s"
    override val paceInvalid = "每一公里都要有配速，范围 2:00 到 60:00。"
    override val paceSummary = "%d 公里，平均 %s"
    override val dismissNotice = "知道了"
    override val overridesCleared = "坐标系覆盖已清空，改为 AUTO。"
    override val overridesUnreadable = "那段文字里没有一条有效记录，什么也没保存。"
    override val overridesSaved = "已保存 %d 条坐标系覆盖。"
    override val overridesSavedPartial = "已保存 %d 条；另有 %d 条读不懂，显示为已丢弃。"
    override val routeReplaced = "已覆盖「%s」。"
    override val routeSavedAs = "已保存「%s」（%d 个路径点，%.0f 米）。"
    override val routeLoadedAs = "「%s」（%d %s）"
    override val nameRequiredWithLimit = "需要填写路线名，最多 %d 个字符。"
    override val startedJoystickMode = "摇杆模式已在 %.5f, %.5f 启动，方向 %.0f 度，%.2f m/s。"
    override val startedJoystickModeWithPlan =
        "摇杆模式已在 %.5f, %.5f 启动，方向 %.0f 度；速度由变速计划提供。%s"
    override val startedRoute = "正在沿 %d 个路径点（%.0f 米）以 %.2f m/s 前进。"
    override val startedRouteWithPlan =
        "正在沿 %d 个路径点（%.0f 米）前进；速度在 %.2f m/s 的基础上由计划调整。%s"
    override val planPassSummary = "每趟 %.0f 秒 / %.0f 米"
    override val planTotalSummary = "，合计 %.0f 米"
    override val planTotalForever = "，沿整条路线循环"
    override val mapRouteSummary = "路径点：%d   长度：%s   底图：%s"
    override val joystickLockedRoute =
        "摇杆已显示。当前是路线模式，速度与方向都由路线决定，停止路线前摇杆是锁定的。" +
            "拖动外圈可以移动它，点一下停止。"
    override val joystickLockedRoutePaused =
        "摇杆已显示。路线处于暂停，人不移动；除非停止路线，否则摇杆仍锁定。" +
            "拖动外圈可以移动它，点一下停止。"
    override val joystickLockedPlan =
        "摇杆已显示。速度由变速计划决定，停止计划运行前摇杆是锁定的。" +
            "拖动外圈可以移动它，点一下停止。"
    override val joystickReady =
        "摇杆已显示。拖动中间的圆点控制方向（松手保持最后的方向），拖动外圈移动它，点一下停止。"
    override val recordNeedsJoystickRoute =
        "录制要跟着摇杆走，而路线模式的运动由路线决定。请停止路线，改用摇杆模式再录制。"
    override val recordNeedsJoystickPlan =
        "录制要跟着摇杆走，但速度由变速计划决定。请清空计划，改用摇杆模式再录制。"
    override val recordNeedsJoystickIdle =
        "请先启动摇杆模式，然后再录制。"
    override val recordedSummary =
        "已录制 %d 个路径点，共 %.0f 米，用时 %d 秒。已经放进编辑器里了。"
    override val unexpectedFailure = "出了点问题：%s：%s"
    override val selfCheckCrashed = "自检本身出错了：%s：%s"
    override val selfCheckDone = "自检：%s（报告已复制到剪贴板）"
    override val statusIdle = "空闲：没有正在运行的模拟。"
    override val statusRunning = "运行中"
    override val statusPaused = "已暂停"
    override val language = "语言"
    override val continueLabel = "继续"
    override val dontShowAgain = "不再显示此页"
    override val environment = "环境"
    override val checkEnvironment = "检测这台设备"
    override val checkingEnvironment = "检测中..."
    override val rootGranted = "Root：已授权"
    override val rootDenied = "Root：未授权"
    override val rootUnknown = "Root：未知"
    override val moduleBound = "模块：已连接"
    override val moduleUnbound = "模块：未连接"
    override val routeNeedsTwoPoints = "命名路线需要两个不同的路径点，当前草稿还不满足。"
    override val routeName = "路线名称"
    override val noSavedRoutes = "还没有已保存的路线。"
    override val routeGone = "该路线已不在存储中。"
    override val waypointCountSuffix = "个路径点"
    override val renamed = "已重命名。"
    override val deleted = "已删除。"
    override val placeWaypointFirst = "请先在地图上放置一个路径点，运行需要一个起点。"
    override val recording = "录制中。用悬浮摇杆操控方向，停止时保存路线。"
    override val nothingRecorded = "没有录制到内容：移动距离不够。"
    override val needTwoWaypoints = "开始路线前请至少添加两个不同的路径点。"
    override val grantOverlay = "请先授予悬浮窗权限，然后重试。"
    override val auditing = "正在审查轨迹..."
    override val simulating = "模拟中..."
    override val statusConfigIdle = "空闲：模拟已停止。"
    override val undo = "撤销"
    override val clear = "清空"
    override val remove = "删除"
    override val fit = "全览"
    override val centre = "居中"
    override val start = "开始"
    override val stop = "停止"
    override val speedMpsLabel = "速度 m/s"
    override val loopShort = "循环"
    override val nothingToCentre = "还没有可以居中的目标。"
    override val routeCannotMove =
        "当前是路线模式，长按无法移动。请改动路径点，或停止后改用摇杆模式。"
    override val movedTo = "模拟位置已移动到 %.5f, %.5f。"
    override val longPressIdle = "长按可以把正在跑的摇杆模式模拟移到这里，但当前没有运行中的模拟。"
    override val routePausedWhereItStands = "路线已在原地暂停，继续之前不再上报速度。"
    override val routeResumedFromPoint = "路线已从暂停处继续。"
    override val mapGestureHint = "拖动平移，双指缩放，点击追加路径点，拖动圆点移动它。"
    override val waypointSelected = "已选中第 %d / %d 个路径点。可拖动它，点“删除”移除，再点一次取消选中。"
    override val centredOnSubject = "已居中到模拟位置 %.5f, %.5f。"
    override val centredOnLast = "已居中到最后一个路径点。"
    override val simulatingWithPlan = "正在按变速计划模拟 %d 个路径点%s。"
    override val simulatingAtSpeed = "正以 %.1f m/s 模拟 %d 个路径点%s。"
    override val repeatingSuffix = "，循环"
    override val noTilesForLayer = "%s 没有 %s 图层。"
    override val simControlChannel = "模拟控制"
    override val simControlText = "屏幕上有速度与方向控制。"
    override val lockRouteOwns = "路线模式：速度和方向由路线决定。"
    override val lockRoutePaused = "路线已暂停：继续之前主体保持不动。"
    override val lockPlanOwns = "变速计划运行中：速度由计划决定，不受摇杆控制。"
    override val lockNothingToSteer = "没有可操控的运行。"
    override val clearRouteTitle = "清空所有路径点？"
    override val clearRouteMessage = "这会从草稿中移除全部 %d 个路径点。"
    override val cancel = "取消"
    override val replaceRunTitle = "已有模拟在运行"
    override val replaceRunMessage = "再次开始会替换当前模拟：从头开始，之前的进度会丢失。"
    override val replaceRunConfirm = "替换"
    override val planNoSteps = "还没有步骤，在下面添加一个。"
    override val planAddStep = "＋ 添加一步"
    override val planRemove = "删除"
    override val planSpeed = "速度"
    override val planDuration = "时长"
    override val planRepeats = "重复"
    override val planForever = "无限"
    override val planPresets = "从预设开始"
    override val planInvalid = "这个计划跑不了：每一步的速度都必须大于零。"
    override val planNone = "匀速"
    override val planSummary = "%d 步 x%s"
    override val planInvalidShort = "跑不了"
    override val planPresetNames = mapOf(
        "human" to "拟人跑法",
        "official" to "官方示例",
        "reps" to "400 米间歇",
        "fartlek" to "法特莱克",
        "negative" to "负分割",
    )
    override val planTotals = "单轮 %.0f 秒 / %.0f 米，合计 %.0f 分钟 / %.2f 公里"
    override val planTotalsForever = "单轮 %.0f 秒 / %.0f 米，之后按整条路线无限重复"
}

/** The strings for [language]. */
fun stringsFor(language: Language): Strings = when (language) {
    Language.ENGLISH -> EnglishStrings
    Language.CHINESE -> ChineseStrings
}
