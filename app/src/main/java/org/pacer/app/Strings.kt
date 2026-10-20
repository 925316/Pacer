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
    val route: String
    val routeHint: String
    val routeSummary: String
    val routeSummaryIncomplete: String
    val openMap: String
    val loadSample: String
    val clearRoute: String
    val startRecording: String
    val stopRecording: String
    val savedRoutes: String
    val saveRoute: String
    val simulation: String
    val accuracy: String
    val altitude: String
    val bearing: String
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
    val startStraight: String
    val startRoute: String
    val pauseRoute: String
    val resumeRoute: String
    val stopSimulation: String
    val showJoystick: String

    /** Short forms for the pinned control bar, where the full label wraps to three lines. */
    val startRouteShort: String
    val startStraightShort: String
    val stopShort: String
    val joystickShort: String
    val preview: String
    val audit: String
    val selfCheck: String
    val runSelfCheck: String
    val selfCheckHint: String
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
    val onlyRunningRouteCanPause: String
    val routeNeedsTwoPoints: String
    val routeName: String
    val noSavedRoutes: String
    val routeGone: String
    val waypointCountSuffix: String
    val renamed: String
    val nameRequired: String
    val deleted: String
    val placeWaypointFirst: String
    val badPlan: String
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
    val planTotals: String
    val planTotalsForever: String
}

/** The English strings; also the fallback for a language that has not been written yet. */
object EnglishStrings : Strings {
    override val appTagline = "Simulate movement for testing."
    override val tabRun = "Run"
    override val tabRoute = "Route"
    override val tabSettings = "Settings"
    override val speed = "Speed"
    override val walk = "Walk 1.4"
    override val run = "Run 3.0"
    override val ride = "Ride 6.0"
    override val custom = "Custom"
    override val customSpeed = "Custom speed m/s"
    override val route = "Route"
    override val routeHint =
        "Waypoints are placed on the map: pan to a place, tap to append one, drag a " +
            "handle to move it. This list is what the map has drawn."
    override val routeSummary = "Waypoints: %d   Length: %.0f m%s"
    override val routeSummaryIncomplete = "   (need two distinct points to run a route)"
    override val openMap = "Open the map and controls"
    override val loadSample = "Load sample route (Urumqi)"
    override val clearRoute = "Clear route"
    override val startRecording = "Start recording from the joystick"
    override val stopRecording = "Stop recording"
    override val savedRoutes = "Saved routes"
    override val saveRoute = "Save current route as..."
    override val simulation = "Simulation"
    override val accuracy = "Steady accuracy (m)"
    override val altitude = "Start altitude (m)"
    override val bearing = "Straight-line bearing (deg, 0 = north)"
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
        "A fix carries no datum, so an app that draws it on a Chinese base map assumes " +
            "GCJ-02 while one that draws it on a Google base map assumes WGS-84; the two " +
            "differ by about half a kilometre. AUTO follows the API being hooked. This table " +
            "overrides that per package; semicolons separate entries."
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
    override val accuracyWarmupHint = "30 m converging to 5 m over the first half minute."
    override val satelliteJitter = "Vary the satellite count"
    override val satelliteJitterHint = "A plausible 7 to 14 instead of one fixed number."
    override val signalDropout = "Lose the signal occasionally"
    override val signalDropoutHint =
        "A few seconds of dropout now and then, which is the one a strict receiver may reject."
    override val startStraight = "Start straight line from the first waypoint"
    override val startRoute = "Start humanized route simulation"
    override val pauseRoute = "Pause route"
    override val resumeRoute = "Resume route"
    override val stopSimulation = "Stop simulation"
    override val showJoystick = "Show floating joystick"
    override val startRouteShort = "Route"
    override val startStraightShort = "Straight"
    override val stopShort = "Stop"
    override val joystickShort = "Joystick"
    override val preview = "Preview engine locally (no hooks)"
    override val audit = "Audit this track (server-side heuristics)"
    override val selfCheck = "Self-check"
    override val runSelfCheck = "Run self-check"
    override val selfCheckHint =
        "Reads this app's own location, WiFi, cell, Bluetooth and step counter back through " +
            "the public APIs and compares them with what the engine says they must be. This " +
            "app is deliberately outside the module's scope, so what it sees is the " +
            "system-wide result."
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
    override val onlyRunningRouteCanPause = "Only a running route can be paused."
    override val routeNeedsTwoPoints =
        "A named route needs two distinct waypoints; this draft is not one yet."
    override val routeName = "Route name"
    override val noSavedRoutes = "No saved routes yet."
    override val routeGone = "That route is no longer in the store."
    override val waypointCountSuffix = "waypoints"
    override val renamed = "Renamed."
    override val nameRequired = "A name is required, under the length limit."
    override val deleted = "Deleted."
    override val placeWaypointFirst =
        "Place a waypoint on the map first; a straight line needs a start."
    override val badPlan =
        "The interval plan could not be read. Use e.g. 3.0@180;1.4@60#5."
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
            "waypoints instead, or stop it and start a straight-line run."
    override val movedTo = "Simulation moved to %.5f, %.5f."
    override val longPressIdle =
        "Long press moves a running straight-line simulation here; none is running."
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
    override val planTotals = "One pass %.0f s / %.0f m, total %.0f min / %.2f km"
    override val planTotalsForever = "One pass %.0f s / %.0f m, repeating for the whole route"
}

/** The Chinese strings. */
object ChineseStrings : Strings {
    override val appTagline = "用于测试的运动模拟。"
    override val tabRun = "运行"
    override val tabRoute = "路线"
    override val tabSettings = "设置"
    override val speed = "速度"
    override val walk = "步行 1.4"
    override val run = "跑步 3.0"
    override val ride = "骑行 6.0"
    override val custom = "自定义"
    override val customSpeed = "自定义速度 m/s"
    override val route = "路线"
    override val routeHint =
        "路径点在地图上放置：拖动到目标位置，点击追加一个点，拖动圆点可以移动它。这里只是列出地图上已画好的点。"
    override val routeSummary = "路径点：%d   长度：%.0f 米%s"
    override val routeSummaryIncomplete = "   （需要两个不同的点才能跑路线）"
    override val openMap = "打开地图与控制器"
    override val loadSample = "载入示例路线（乌鲁木齐）"
    override val clearRoute = "清空路线"
    override val startRecording = "用摇杆开始录制"
    override val stopRecording = "停止录制"
    override val savedRoutes = "已保存路线"
    override val saveRoute = "将当前路线另存为..."
    override val simulation = "模拟"
    override val accuracy = "稳定精度（米）"
    override val altitude = "起始海拔（米）"
    override val bearing = "直线方位角（度，0 = 正北）"
    override val intervalPlanSection = "变速计划"
    override val loopRoute = "循环跑这条路线"
    override val loopRouteHint = "跑完最后一站后回到第一个路径点继续，而不是停下。距离和步数会继续累加。"
    override val stepSimulation = "步数与传感器模拟"
    override val stepSimulationHint = "计步器、步行检测和加速度计会跟随路线变化。"
    override val bluetooth = "模拟蓝牙环境"
    override val bluetoothHint = "关闭时，WiFi 和基站仍然被模拟，但蓝牙扫描会报告真实周边。"
    override val coordinateSystem = "按应用指定坐标系"
    override val coordinateSystemHint =
        "定位结果本身不带基准，画在中国底图上的应用会当成 GCJ-02，画在谷歌底图上的会当成 WGS-84，" +
            "两者相差约五百米。AUTO 表示跟随被 hook 的接口。此表按包名覆盖默认值，用分号分隔多项。"
    override val saveOverrides = "保存坐标系覆盖"
    override val signalRealism = "信号真实度"
    override val signalRealismHint = "每项都能让轨迹少一点机器味。关掉某项就让那部分信号保持稳定。"
    override val bearingHold = "静止时保持朝向"
    override val bearingHoldHint = "静止的接收机继续报告上一次的航向，而不是跳到正北。"
    override val altitudeDrift = "海拔缓慢漂移"
    override val altitudeDriftHint = "每秒几厘米的随机游走。"
    override val accuracyWarmup = "精度预热"
    override val accuracyWarmupHint = "前三十秒从 30 米收敛到 5 米，模拟冷启动定位。"
    override val satelliteJitter = "卫星数量浮动"
    override val satelliteJitterHint = "在 7 到 14 之间浮动，而不是固定一个数字。"
    override val signalDropout = "偶尔丢失信号"
    override val signalDropoutHint = "偶尔掉几秒，严格的接收机可能会因此拒绝。"
    override val startStraight = "从第一个路径点开始直线运动"
    override val startRoute = "开始拟人化路线模拟"
    override val pauseRoute = "暂停路线"
    override val resumeRoute = "继续路线"
    override val stopSimulation = "停止模拟"
    override val showJoystick = "显示悬浮摇杆"
    override val startRouteShort = "路线"
    override val startStraightShort = "直线"
    override val stopShort = "停止"
    override val joystickShort = "摇杆"
    override val preview = "本地预览引擎（不经过 hook）"
    override val audit = "审查这条轨迹（服务端启发式）"
    override val selfCheck = "自检"
    override val runSelfCheck = "运行自检"
    override val selfCheckHint =
        "通过公开接口读回本应用自己看到的定位、WiFi、基站、蓝牙和计步器，并与引擎声明的值比对。" +
            "本应用刻意不在模块作用域内，所以它看到的是系统层面的结果。"
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
    override val onlyRunningRouteCanPause = "只有正在运行的路线才能暂停。"
    override val routeNeedsTwoPoints = "命名路线需要两个不同的路径点，当前草稿还不满足。"
    override val routeName = "路线名称"
    override val noSavedRoutes = "还没有已保存的路线。"
    override val routeGone = "该路线已不在存储中。"
    override val waypointCountSuffix = "个路径点"
    override val renamed = "已重命名。"
    override val nameRequired = "必须填写名称，且不超过长度上限。"
    override val deleted = "已删除。"
    override val placeWaypointFirst = "请先在地图上放置一个路径点，直线运动需要一个起点。"
    override val badPlan = "变速计划无法解析。示例：3.0@180;1.4@60#5。"
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
        "当前是路线模式，长按无法移动。请改动路径点，或停止后改跑直线。"
    override val movedTo = "模拟位置已移动到 %.5f, %.5f。"
    override val longPressIdle = "长按可以把正在跑的直线模拟移到这里，但当前没有运行中的模拟。"
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
    override val planTotals = "单轮 %.0f 秒 / %.0f 米，合计 %.0f 分钟 / %.2f 公里"
    override val planTotalsForever = "单轮 %.0f 秒 / %.0f 米，之后按整条路线无限重复"
}

/** The strings for [language]. */
fun stringsFor(language: Language): Strings = when (language) {
    Language.ENGLISH -> EnglishStrings
    Language.CHINESE -> ChineseStrings
}
