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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        params = WindowManager.LayoutParams(
            SIZE,
            SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 120
            y = 400
        }

        val view = JoystickView(this)
        joystick = view
        wm.addView(view, params)
    }

    override fun onDestroy() {
        joystick?.let { windowManager?.removeView(it) }
        joystick = null
        super.onDestroy()
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
                            PacerConfigClient.stop()
                            // With the run gone there is nothing left to steer, and an
                            // overlay that outlives it can only be removed by stopping
                            // the whole app. The tap is the close control; deferred so
                            // the view is not removed mid-gesture.
                            post { stopSelf() }
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
            val knobRadius = (width / 2f - 8f) * KNOB_FRACTION
            val dx = x - (width / 2f + knobX)
            val dy = y - (height / 2f + knobY)
            return dx * dx + dy * dy <= knobRadius * knobRadius
        }

        private fun moveOverlay(dx: Float, dy: Float) {
            params.x += dx.toInt()
            params.y += dy.toInt()
            windowManager?.updateViewLayout(this, params)
        }

        private fun steer(dx: Float, dy: Float) {
            val maxOffset = width / 2f - 16f
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
            val radius = width / 2f - 8f
            canvas.drawCircle(cx, cy, radius, basePaint)
            canvas.drawCircle(cx + knobX, cy + knobY, radius * KNOB_FRACTION, knobPaint)
        }
    }

    private companion object {
        const val SIZE = 220
        const val SPEED_INPUT = 6.0

        /** Knob size as a fraction of the base radius, shared by drawing and hit testing. */
        const val KNOB_FRACTION = 0.32f

        const val CHANNEL_ID = "pacer-joystick"
        const val NOTIFICATION_ID = 1
        const val TAG = "Pacer"
    }
}
