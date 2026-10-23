package org.pacer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.pacer.motion.JoystickMath
import org.pacer.motion.MotionOwner
import kotlin.math.roundToInt

/** Knob radius as a fraction of the ring radius. At 0.39 a 144 dp ring gives a 56 dp knob,
 * comfortably past the 48 dp minimum target, while leaving a ring wide enough to grab. */
private const val KNOB_FRACTION = 0.39f

/**
 * Diameter of the outer ring, and the transparent padding around it.
 *
 * Internal rather than private so the geometry test pins the numbers that actually ship;
 * the window is the two added together, which is why the whole overlay measures 160 dp.
 */
internal const val JOYSTICK_RING_DP = 144f
internal const val JOYSTICK_INSET_DP = 8f

/**
 * Space from the screen edges when the overlay first appears.
 *
 * Dragging still lets the user put it anywhere; this only decides where it first lands, and
 * a margin keeps the ring off the system gesture strip at the screen edge.
 */
private const val JOYSTICK_MARGIN_DP = 24

/**
 * Height reserved for the status bar when the overlay first appears.
 *
 * The service cannot read real insets - see [screenFrame] - so the starting position keeps a
 * generous allowance clear of both bars. Only the first position is affected; dragging lets
 * the user put the ring anywhere.
 */
private const val STATUS_BAR_ALLOWANCE_DP = 32

/** Height reserved for the navigation bar or gesture area, below the ring. */
private const val NAV_BAR_ALLOWANCE_DP = 48

/** Below the top inset but above the middle: reachable and out of the way. */
private const val START_VERTICAL_FRACTION = 0.18f

/**
 * The overlay's three pixel measurements, all derived from dp and the display density.
 *
 * One function for the window size, the ring and the knob is the point: the drawing, the
 * hit test and the layout params all read this, so the disc the user sees and the disc that
 * responds cannot be derived differently.
 */
internal data class JoystickGeometry(
    /** Side of the square overlay window, in px. */
    val windowSizePx: Int,
    /** Centre to the outer ring, in px. */
    val baseRadiusPx: Float,
    /** Radius of the draggable knob, in px. */
    val knobRadiusPx: Float,
)

/**
 * Geometry for an overlay window of [windowSizePx] square, with [insetDp] of transparent
 * padding on each side.
 *
 * The inset is excluded from [JoystickGeometry.baseRadiusPx], so the ring is exactly the
 * diameter that fits inside the padding; and because the radius is derived from the window
 * that was really laid out, the circle can never reach past the view's bounds.
 */
internal fun joystickGeometry(
    windowSizePx: Int,
    insetDp: Float,
    density: Float,
): JoystickGeometry {
    val baseRadius = (windowSizePx / 2f - insetDp * density).coerceAtLeast(1f)
    return JoystickGeometry(
        windowSizePx = windowSizePx,
        baseRadiusPx = baseRadius,
        knobRadiusPx = baseRadius * KNOB_FRACTION,
    )
}

/**
 * The window side needed to hold a [ringDiameterDp] ring plus [insetDp] of padding.
 *
 * Rounding happens here, once, and [joystickGeometry] is then given the rounded result: the
 * two cannot disagree by a pixel about how large the ring is.
 */
internal fun joystickWindowPx(ringDiameterDp: Float, insetDp: Float, density: Float): Int =
    ((ringDiameterDp + 2f * insetDp) * density).roundToInt().coerceAtLeast(4)

/** Window position for a [sizePx] overlay on a screen of the given size, in px. */
internal data class JoystickPlacement(val x: Int, val y: Int)

/**
 * Where the overlay starts: left edge, below the status bar, well clear of the navigation
 * bar, and always fully on screen.
 *
 * Screen coordinates, because a TYPE_APPLICATION_OVERLAY window is positioned against the
 * whole display rather than an app's content area. The insets are passed in so this stays a
 * pure function: a test can pin that a short screen still yields an on-screen position.
 */
internal fun joystickPlacement(
    screenWidthPx: Int,
    screenHeightPx: Int,
    sizePx: Int,
    marginPx: Int,
    topInsetPx: Int,
    bottomInsetPx: Int,
    verticalFraction: Float,
): JoystickPlacement {
    // Each bound is clamped at 0 by itself: a screen narrower or shorter than the overlay
    // plus its margins is not a reason to return a negative coordinate, which the window
    // manager would interpret as an offset from the far edge.
    val maxX = (screenWidthPx - sizePx - marginPx).coerceAtLeast(0)
    val lowY = topInsetPx + marginPx
    val highY = (screenHeightPx - bottomInsetPx - sizePx - marginPx).coerceAtLeast(0)
    val y = if (highY <= lowY) {
        highY
    } else {
        lowY + ((highY - lowY) * verticalFraction).toInt()
    }
    return JoystickPlacement(x = marginPx.coerceAtMost(maxX), y = y.coerceIn(0, highY))
}

/**
 * Floating joystick overlay.
 *
 * Three gestures, and each one has to be told apart by where the touch lands and
 * whether it travels, because an overlay has no menu: touching the knob steers,
 * touching the ring moves the overlay itself, and a touch that never travels is a
 * tap, which stops the simulation. Releasing after steering keeps the last heading
 * and speed, so a long run does not need a finger held on the screen.
 */
class JoystickOverlayService : Service() {

    /** The service has no activity, so it resolves the labels itself. */
    private val strings: Strings
        get() = stringsFor(AppSettings(this).language)

    private var windowManager: WindowManager? = null
    private var joystick: JoystickView? = null
    private lateinit var params: WindowManager.LayoutParams

    /** Owns the deferred stop, so onDestroy can drop it instead of racing a removed view. */
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // dp, not px: the same number of pixels is a thumb-sized target on a low-density
        // phone and an untouchable dot on a 3x screen.
        val density = resources.displayMetrics.density
        val geometry = joystickGeometry(
            joystickWindowPx(JOYSTICK_RING_DP, JOYSTICK_INSET_DP, density),
            JOYSTICK_INSET_DP,
            density,
        )
        val marginPx = dp(JOYSTICK_MARGIN_DP)
        val screen = screenFrame()
        val at = joystickPlacement(
            screenWidthPx = screen.widthPx,
            screenHeightPx = screen.heightPx,
            sizePx = geometry.windowSizePx,
            marginPx = marginPx,
            topInsetPx = screen.topInsetPx,
            bottomInsetPx = screen.bottomInsetPx,
            verticalFraction = START_VERTICAL_FRACTION,
        )

        params = WindowManager.LayoutParams(
            geometry.windowSizePx,
            geometry.windowSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = at.x
            y = at.y
        }

        val view = JoystickView(this)
        joystick = view
        wm.addView(view, params)
    }

    /**
     * Removes everything this service put on screen.
     *
     * Both halves matter: a removed view with a live foreground notification leaves a
     * permanent entry for a control that no longer exists, and a removed notification with
     * a live view leaves an overlay the user cannot account for.
     */
    override fun onDestroy() {
        overlayHandler.removeCallbacksAndMessages(null)
        joystick?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                // Already detached by the window being torn down; nothing left to remove.
                Log.w(TAG, "overlay view was already gone", t)
            }
        }
        joystick = null
        // The service is gone, so the ongoing notification must go with it; leaving it would
        // advertise a control that no longer exists and cannot be dismissed by the user.
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private data class ScreenFrame(
        val widthPx: Int,
        val heightPx: Int,
        val topInsetPx: Int,
        val bottomInsetPx: Int,
    )

    /**
     * Display size and system-bar heights, in screen coordinates.
     *
     * An overlay window is positioned against the whole display, so the insets have to be
     * subtracted from that frame rather than from an app's content area.
     *
     * The size comes from the real display metrics because a Service is not a visual
     * context, which is what getCurrentWindowMetrics requires; the only metric a Service may
     * ask for is the maximum one, and that describes the largest area the display can ever
     * have - in portrait it is the landscape frame, with width and height swapped. The bar
     * heights are therefore fixed allowances too: this only decides where the ring first
     * lands, and joystickPlacement clamps whatever it is given into the frame.
     */
    @Suppress("DEPRECATION")
    private fun screenFrame(): ScreenFrame {
        val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return ScreenFrame(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            topInsetPx = dp(STATUS_BAR_ALLOWANCE_DP),
            bottomInsetPx = dp(NAV_BAR_ALLOWANCE_DP),
        )
    }

    /**
     * A foreground service, because of when this overlay is useful: the user is
     * steering a simulation that the TARGET app is reading, which means Pacer is in
     * the background. The system may kill a background service at any moment, so
     * without this the control would disappear exactly when it is needed.
     *
     * Android 14 and later require a declared foreground service type; "specialUse"
     * with the property in the manifest is the honest one for a control overlay.
     */
    private fun startAsForeground() {
        try {
            val channel = NotificationChannel(CHANNEL_ID, strings.simControlChannel, NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
            val open = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Pacer")
                .setContentText(strings.simControlText)
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // A refused notification must not take the overlay down with it: the view
            // is already added, and the service still runs while the activity lives.
            Log.w(TAG, "foreground notification refused", t)
        }
    }

    private inner class JoystickView(context: Context) : View(context) {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 30, 30, 30)
            style = Paint.Style.FILL
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 60, 160, 255)
            style = Paint.Style.FILL
        }
        private var knobX = 0f
        private var knobY = 0f
        private var dragDx = 0f
        private var dragDy = 0f

        /** True while this gesture steers rather than moving the overlay. */
        private var steering = false

        /**
         * The owner of the running simulation, sampled when the touch starts.
         *
         * A route or an interval plan derives speed and heading from its own clock;
         * writing either from here would move the subject along its track instead of
         * steering it, so the knob refuses to leave the centre and says why. Sampled
         * per gesture rather than per frame because a run can start or stop between
         * two touches.
         */
        private var lockedOwner = MotionOwner.NONE

        /** True once the touch has travelled: a gesture that never moves is a tap. */
        private var travelled = false

        /** True once this gesture has explained why the knob will not move. */
        private var lockNotified = false

        init {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragDx = event.rawX
                        dragDy = event.rawY
                        travelled = false
                        lockNotified = false
                        lockedOwner = PacerConfigClient.owner()
                        steering = isOnKnob(event.x, event.y)
                        invalidate()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - dragDx
                        val dy = event.rawY - dragDy
                        dragDx = event.rawX
                        dragDy = event.rawY
                        if (dx != 0f || dy != 0f) travelled = true
                        if (steering) {
                            // An automatic run derives speed and heading from its own
                            // clock; writing either would move the subject along its
                            // track instead of steering it. The ring still drags.
                            if (lockedOwner.acceptsHandSteering) {
                                steer(dx, dy)
                            } else {
                                // Explain it on the first movement rather than on touch
                                // down, so a tap that stops the run says nothing.
                                if (!lockNotified) {
                                    lockNotified = true
                                    Toast.makeText(
                                        this@JoystickOverlayService,
                                        lockMessage(lockedOwner),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } else {
                            moveOverlay(dx, dy)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // Only a tap stops the simulation, not every release: steering
                        // ends when the finger comes off the knob, and stopping on that
                        // release would end the run the moment the user let go.
                        if (!travelled && event.actionMasked == MotionEvent.ACTION_UP) {
                            knobX = 0f
                            knobY = 0f
                            // Closing the overlay on a refused stop would leave the target app
                            // moving with no way back: stop() writes nothing when the config
                            // channel is not bound, and the window is the only control left.
                            if (PacerConfigClient.stop()) {
                                // With the run gone there is nothing left to steer, and an
                                // overlay that outlives it can only be removed by stopping
                                // the whole app. The tap is the close control; deferred so
                                // the view is not removed mid-gesture.
                                overlayHandler.post { stopSelf() }
                            } else {
                                Toast.makeText(
                                    this@JoystickOverlayService,
                                    strings.configNotReady,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        lockedOwner = MotionOwner.NONE
                        invalidate()
                        true
                    }
                    else -> false
                }
            }
        }

        /** Why the knob refused, in terms of what the user actually started. */
        private fun lockMessage(owner: MotionOwner): String {
            val s = stringsFor(AppSettings(context).language)
            return when (owner) {
                MotionOwner.ROUTE -> s.lockRouteOwns
                MotionOwner.ROUTE_PAUSED -> s.lockRoutePaused
                MotionOwner.PLAN -> s.lockPlanOwns
                else -> s.lockNothingToSteer
            }
        }

        /** The knob is drawn at its current offset, so the hit test uses that offset too. */
        private fun isOnKnob(x: Float, y: Float): Boolean {
            val knobRadius = geometry().knobRadiusPx
            val dx = x - (width / 2f + knobX)
            val dy = y - (height / 2f + knobY)
            return dx * dx + dy * dy <= knobRadius * knobRadius
        }

        /** One source for drawing, hit testing and the steering range; derived from the
         * view's real width so a density change cannot desynchronise them. */
        private fun geometry(): JoystickGeometry {
            val density = resources.displayMetrics.density
            val windowPx = if (width > 0) {
                width
            } else {
                joystickWindowPx(JOYSTICK_RING_DP, JOYSTICK_INSET_DP, density)
            }
            return joystickGeometry(windowPx, JOYSTICK_INSET_DP, density)
        }

        private fun moveOverlay(dx: Float, dy: Float) {
            params.x += dx.toInt()
            params.y += dy.toInt()
            windowManager?.updateViewLayout(this, params)
        }

        private fun steer(dx: Float, dy: Float) {
            // How far the knob's CENTRE may travel, which is not the ring radius: allowing
            // it to reach the ring would draw half the knob outside the window and clip it.
            // Full speed is then reached exactly when the knob touches the ring.
            val geometry = geometry()
            val maxOffset = (geometry.baseRadiusPx - geometry.knobRadiusPx).coerceAtLeast(1f)
            knobX = (knobX + dx).coerceIn(-maxOffset, maxOffset)
            knobY = (knobY + dy).coerceIn(-maxOffset, maxOffset)
            invalidate()
            val nx = knobX / maxOffset
            val ny = -knobY / maxOffset
            val velocity = JoystickMath.compute(nx.toDouble(), ny.toDouble(), SPEED_INPUT)
            if (velocity.magnitude > 0.0) {
                PacerConfigClient.updateMotion(velocity.magnitude * SPEED_INPUT, velocity.angleDeg.toFloat())
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val geometry = geometry()
            canvas.drawCircle(cx, cy, geometry.baseRadiusPx, basePaint)
            canvas.drawCircle(cx + knobX, cy + knobY, geometry.knobRadiusPx, knobPaint)
        }
    }

    private companion object {
        const val SPEED_INPUT = 6.0

        const val CHANNEL_ID = "pacer-joystick"
        const val NOTIFICATION_ID = 1
        const val TAG = "Pacer"
    }
}
