package org.pacer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.pacer.motion.GeoPoint
import org.pacer.motion.PixelPoint
import org.pacer.motion.TileGrid
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A map view that draws raster tiles itself.
 *
 * WHY NOT A MAP SDK. The three sources Pacer needs - AMap, Baidu and Google - do not agree
 * on what a tile index means, and Baidu's is not even a Web Mercator grid. osmdroid and the
 * vendor SDKs each hardcode one grid, so serving all three through one of them needs either
 * an offset hack or three map widgets with three interaction models. A view that takes a
 * [TileGrid] as a parameter draws all three correctly with one set of gestures, and pulls in
 * no proprietary dependency at all.
 *
 * WHAT IT IS NOT. Not a map library: one zoom step per pinch, no rotation, no tile
 * prediction, and a small in-memory cache. It exists to place waypoints.
 *
 * THE GRID OWNS THE MATHS. This class converts screen pixels to grid pixels and back, and
 * nothing else. Which datum a tile is drawn in, and whether its y index counts up or down,
 * are questions the [TileGrid] answers.
 */
internal class TileMapView(
    context: Context,
    private val source: TileSource,
    initialCenter: GeoPoint = DEFAULT_CENTER,
    initialZoom: Int = DEFAULT_ZOOM,
) : View(context) {

    /** One raster source: where its tiles live, and on which grid they are cut. */
    data class TileSource(
        val label: String,
        val grid: TileGrid,
        /** The URL of one tile; null means this source cannot serve that tile. */
        val url: (x: Int, y: Int, zoom: Int) -> String?,
    )

    /** Where the view is looking: the grid pixel at the CENTRE of the view. */
    private var centerPixel: PixelPoint

    var zoom: Int = initialZoom
        private set

    private val tiles = TileCache(MAX_CACHED_TILES)

    /** Repaints when a tile arrives, coalesced onto the UI thread. */
    private val invalidatePending = AtomicBoolean(false)

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ROUTE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * A wider, darker copy drawn underneath the route.
     *
     * A shadow layer would be one line, but it only renders on text under hardware
     * acceleration, so a route drawn with a shadow is a route with no shadow. Two strokes
     * cost one more draw call and actually work.
     */
    private val routeUnderlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val waypointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WAYPOINT_COLOR
        style = Paint.Style.FILL
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SELECTED_COLOR
        style = Paint.Style.FILL
    }

    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIVE_COLOR
        style = Paint.Style.FILL
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EMPTY_COLOR
        style = Paint.Style.FILL
    }

    private var geometry: List<GeoPoint> = emptyList()
    private var waypoints: List<GeoPoint> = emptyList()
    private var livePoint: GeoPoint? = null
    private var selectedWaypoint = -1

    /** A tap on the map away from every waypoint. */
    var onTap: ((GeoPoint) -> Unit)? = null

    /** A tap on a waypoint, with its index. */
    var onWaypointTap: ((Int) -> Unit)? = null

    /** A waypoint was dragged to a new place, while the drag is still in progress. */
    var onWaypointMoving: ((Int, GeoPoint) -> Unit)? = null

    /** A waypoint drag finished; this is the point that must be persisted. */
    var onWaypointDropped: ((Int, GeoPoint) -> Unit)? = null

    /** A finger held still on the map, away from every waypoint. */
    var onLongPress: ((GeoPoint) -> Unit)? = null

    /** Which source this is, for the page to say out loud. */
    val label: String get() = source.label

    init {
        centerPixel = source.grid.pixel(initialCenter, zoom)
    }

    fun setRoute(geometry: List<GeoPoint>, waypoints: List<GeoPoint>) {
        this.geometry = geometry
        this.waypoints = waypoints
        if (selectedWaypoint >= waypoints.size) selectedWaypoint = -1
        invalidate()
    }

    fun setSelectedWaypoint(index: Int?) {
        selectedWaypoint = index ?: -1
        invalidate()
    }

    fun setLive(point: GeoPoint?) {
        livePoint = point
        invalidate()
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND_COLOR)
        if (width == 0 || height == 0) return
        drawTiles(canvas)
        drawGeometry(canvas)
        drawWaypoints(canvas)
        drawLive(canvas)
    }

    private fun drawTiles(canvas: Canvas) {
        val topLeft = screenToPixel(0f, 0f)
        val firstX = floor(topLeft.x / TILE_SIZE).toInt()
        val firstY = floor(topLeft.y / TILE_SIZE).toInt()
        // The screen position of tile (firstX, firstY)'s corner: the view's left edge is
        // part-way into that tile.
        val originX = -(topLeft.x - firstX * TILE_SIZE.toDouble())
        val originY = -(topLeft.y - firstY * TILE_SIZE.toDouble())
        val cols = width / TILE_SIZE + 2
        val rows = height / TILE_SIZE + 2
        val destination = Rect()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = firstX + col
                val y = firstY + row
                val left = (originX + col * TILE_SIZE).roundToInt()
                val top = (originY + row * TILE_SIZE).roundToInt()
                destination.set(left, top, left + TILE_SIZE, top + TILE_SIZE)
                val bitmap = tiles.bitmap(x, y, zoom, source.url)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, destination, tilePaint)
                } else {
                    canvas.drawRect(destination, emptyPaint)
                    tiles.request(x, y, zoom, source.url, ::onTileArrived)
                }
            }
        }
    }

    private fun onTileArrived() {
        if (invalidatePending.compareAndSet(false, true)) {
            post {
                invalidatePending.set(false)
                invalidate()
            }
        }
    }

    private fun drawGeometry(canvas: Canvas) {
        if (geometry.size < 2) return
        val path = Path()
        geometry.forEachIndexed { index, point ->
            val p = pixelToScreen(source.grid.pixel(point, zoom))
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        canvas.drawPath(path, routeUnderlayPaint)
        canvas.drawPath(path, routePaint)
    }

    private fun drawWaypoints(canvas: Canvas) {
        for ((index, point) in waypoints.withIndex()) {
            val p = pixelToScreen(source.grid.pixel(point, zoom))
            val selected = index == selectedWaypoint
            val radius = if (selected) SELECTED_RADIUS else WAYPOINT_RADIUS
            canvas.drawCircle(p.x, p.y, radius, if (selected) selectedPaint else waypointPaint)
            canvas.drawCircle(p.x, p.y, radius, haloPaint)
        }
    }

    private fun drawLive(canvas: Canvas) {
        val point = livePoint ?: return
        val p = pixelToScreen(source.grid.pixel(point, zoom))
        canvas.drawCircle(p.x, p.y, LIVE_RADIUS, livePaint)
        canvas.drawCircle(p.x, p.y, LIVE_RADIUS, haloPaint)
    }

    // ---------------------------------------------------------------------
    // Camera
    // ---------------------------------------------------------------------

    /** Where the view is looking, as a WGS-84 coordinate; for a basemap switch. */
    fun centerPoint(): GeoPoint = source.grid.point(centerPixel, zoom)

    /** Centres the map on [point], leaving the zoom alone. */
    fun centerOn(point: GeoPoint) {
        centerPixel = source.grid.pixel(point, zoom)
        invalidate()
    }

    /**
     * Zooms so that every one of [points] is on screen, then centres on their middle.
     *
     * Chosen by search rather than by a bounding-box formula: the three grids do not share a
     * metres-per-pixel function, and the search is over at most seventeen zooms.
     */
    fun fitRoute(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        if (width == 0 || height == 0) {
            // No size yet, so no fit can be computed. The page calls this again from a
            // layout listener once the view has one.
            centerOn(points.first())
            return
        }
        var best = source.grid.minZoom
        for (z in source.grid.minZoom..source.grid.maxZoom) {
            val box = bounds(points, z)
            val fits = box.width <= width - FIT_PADDING * 2 && box.height <= height - FIT_PADDING * 2
            if (!fits) break
            best = z
        }
        zoom = best
        val box = bounds(points, zoom)
        centerPixel = PixelPoint(box.left + box.width / 2, box.top + box.height / 2)
        invalidate()
    }

    private class Box(val left: Double, val top: Double, val width: Double, val height: Double)

    private fun bounds(points: List<GeoPoint>, zoom: Int): Box {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (point in points) {
            val px = source.grid.pixel(point, zoom)
            minX = min(minX, px.x)
            maxX = max(maxX, px.x)
            minY = min(minY, px.y)
            maxY = max(maxY, px.y)
        }
        return Box(minX, minY, maxX - minX, maxY - minY)
    }

    /** Where a grid pixel lands on screen, given the current camera. */
    private fun pixelToScreen(p: PixelPoint): PointF {
        val left = centerPixel.x - width / 2.0
        val top = centerPixel.y - height / 2.0
        return PointF((p.x - left).toFloat(), (p.y - top).toFloat())
    }

    private fun screenToPixel(x: Float, y: Float): PixelPoint {
        val left = centerPixel.x - width / 2.0
        val top = centerPixel.y - height / 2.0
        return PixelPoint(left + x, top + y)
    }

    private fun screenToGeo(x: Float, y: Float): GeoPoint =
        source.grid.point(screenToPixel(x, y), zoom)

    // ---------------------------------------------------------------------
    // Gestures
    // ---------------------------------------------------------------------

    private enum class Mode { IDLE, PAN, WAYPOINT, PINCH }

    private var mode = Mode.IDLE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var draggedWaypoint = -1
    private var movedFar = false
    private var longPressFired = false
    private var pinchStartSpan = 0f
    private var pinchStartZoom = 0

    /**
     * Fires while the finger is still down, so the map must remember that it already acted:
     * otherwise the lift that follows would be read as a tap and append a waypoint at the
     * same place the simulation was just moved to.
     */
    private val longPress = Runnable {
        if (mode == Mode.PAN && !movedFar) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke(screenToGeo(downX, downY))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The page may sit in a scrollable parent; a map that hands its drags to it
                // would pan the screen instead of the map.
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                movedFar = false
                longPressFired = false
                draggedWaypoint = waypointNear(event.x, event.y)
                mode = if (draggedWaypoint >= 0) Mode.WAYPOINT else Mode.PAN
                if (mode == Mode.PAN) postDelayed(longPress, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                if (event.pointerCount >= 2) {
                    pinchStartSpan = span(event)
                    pinchStartZoom = zoom
                    mode = Mode.PINCH
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.PINCH && event.pointerCount >= 2) {
                    val scale = span(event) / pinchStartSpan
                    if (scale > 0f && !scale.isNaN()) {
                        // Whole zoom steps only: a fractional zoom would need the tile
                        // source to serve fractional levels, which none of the three does.
                        val steps = (ln(scale.toDouble()) / LN_2).toInt()
                        val target = (pinchStartZoom + steps)
                            .coerceIn(source.grid.minZoom, source.grid.maxZoom)
                        if (target != zoom) {
                            zoom = target
                            invalidate()
                        }
                    }
                    return true
                }
                if (mode == Mode.WAYPOINT && draggedWaypoint >= 0) {
                    val point = screenToGeo(event.x, event.y)
                    // Move the handle locally first: waiting for the page would make it lag
                    // a frame behind the finger at every step.
                    if (draggedWaypoint < waypoints.size) {
                        waypoints = waypoints.toMutableList().also { it[draggedWaypoint] = point }
                    }
                    onWaypointMoving?.invoke(draggedWaypoint, point)
                    invalidate()
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (hypot(event.x - downX, event.y - downY) > TOUCH_SLOP) {
                    movedFar = true
                    removeCallbacks(longPress)
                }
                centerPixel = PixelPoint(centerPixel.x - dx, centerPixel.y - dy)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Lifting one finger of a pinch must not read as a tap.
                if (mode == Mode.PINCH) movedFar = true
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val wasWaypoint = mode == Mode.WAYPOINT
                val index = draggedWaypoint
                mode = Mode.IDLE
                draggedWaypoint = -1
                if (wasWaypoint && index >= 0) {
                    // The page has been following the drag; this hands it the final point
                    // and is what makes the change persist.
                    onWaypointDropped?.invoke(index, screenToGeo(event.x, event.y))
                    return true
                }
                if (movedFar || longPressFired) return true
                val hit = waypointNear(event.x, event.y)
                if (hit >= 0) onWaypointTap?.invoke(hit) else onTap?.invoke(screenToGeo(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                mode = Mode.IDLE
                draggedWaypoint = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        // A long press that is still pending would fire against a view the page has thrown
        // away, for example when the basemap is switched under the finger.
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    /** The index of a waypoint under the finger, or -1. */
    private fun waypointNear(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = WAYPOINT_GRAB_PIXELS
        for ((index, point) in waypoints.withIndex()) {
            val p = pixelToScreen(source.grid.pixel(point, zoom))
            val d = hypot(p.x - x, p.y - y)
            if (d <= bestDistance) {
                bestDistance = d
                best = index
            }
        }
        return best
    }

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private companion object {
        const val TILE_SIZE = TileGrid.TILE_SIZE

        /** Moscow: the default view, deliberately nowhere near the user. */
        val DEFAULT_CENTER = GeoPoint(55.7558, 37.6173)
        const val DEFAULT_ZOOM = 13

        const val MAX_CACHED_TILES = 400
        const val FIT_PADDING = 48
        const val TOUCH_SLOP = 12f
        const val LONG_PRESS_MS = 500L
        const val WAYPOINT_RADIUS = 9f
        const val SELECTED_RADIUS = 13f
        const val LIVE_RADIUS = 8f
        const val WAYPOINT_GRAB_PIXELS = 34f
        val LN_2 = ln(2.0)

        const val ROUTE_COLOR = 0xFF1E88E5.toInt()
        const val WAYPOINT_COLOR = 0xFF37474F.toInt()
        const val SELECTED_COLOR = 0xFFD81B60.toInt()
        const val LIVE_COLOR = 0xFFE53935.toInt()
        const val EMPTY_COLOR = 0xFFE8E8E8.toInt()
        const val BACKGROUND_COLOR = 0xFFF5F5F5.toInt()
    }
}

/**
 * Decoded tiles by URL, in memory only, capped at [capacity].
 *
 * Not persisted: a disk cache would be a record of every place the user has looked at, and
 * a tile cache buys nothing for the minute or two a route takes to build.
 */
private class TileCache(private val capacity: Int) {

    private val entries = object : LinkedHashMap<String, Bitmap>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > capacity
    }

    /** URLs currently being fetched, so a tile is never requested twice at once. */
    private val inFlight = HashSet<String>()

    /**
     * When each failed URL was last attempted.
     *
     * Without this, a tile the service refuses is asked for again on the very next frame:
     * a draw runs sixty times a second, so one bad tile becomes sixty requests a second
     * until the user pans away. A failure is remembered for a while and then forgotten, so
     * a temporary outage still recovers on its own.
     */
    private val failedAt = HashMap<String, Long>()

    private val workers = Executors.newFixedThreadPool(FETCH_THREADS)

    /** The tile if it is already decoded and in memory. */
    fun bitmap(
        x: Int,
        y: Int,
        zoom: Int,
        url: (Int, Int, Int) -> String?,
    ): Bitmap? {
        val key = url(x, y, zoom) ?: return null
        return synchronized(entries) { entries[key] }
    }

    /** Starts a fetch unless the tile is cached, already on its way, or recently failed. */
    fun request(
        x: Int,
        y: Int,
        zoom: Int,
        url: (Int, Int, Int) -> String?,
        onLoaded: () -> Unit,
    ) {
        val key = url(x, y, zoom) ?: return
        val now = System.currentTimeMillis()
        synchronized(failedAt) {
            val at = failedAt[key]
            if (at != null) {
                if (now - at < RETRY_AFTER_MS) return
                failedAt.remove(key)
            }
        }
        synchronized(inFlight) {
            if (!inFlight.add(key)) return
        }
        workers.execute {
            try {
                val bytes = Http.getBytes(key)
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap != null) {
                    synchronized(entries) { entries[key] = bitmap }
                    onLoaded()
                } else {
                    // A missing tile is not an error worth a stack trace: a zoom level the
                    // source does not serve, or a transient failure.
                    Log.d(TAG, "tile unavailable: $key")
                    synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tile failed: $key", t)
                synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    private companion object {
        const val FETCH_THREADS = 4
        const val TAG = "Pacer"

        /** How long a tile that failed is left alone before it is tried again. */
        const val RETRY_AFTER_MS = 15_000L
    }
}
