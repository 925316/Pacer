package org.pacer.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.pacer.motion.JoystickMath

/**
 * Draggable floating joystick. Dragging sets live speed and bearing through the
 * config channel; tapping the center stops the simulation.
 */
class JoystickOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var joystick: JoystickView? = null
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        private var movingWindow = false

        init {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        movingWindow = false
                        dragDx = event.rawX
                        dragDy = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - dragDx
                        val dy = event.rawY - dragDy
                        dragDx = event.rawX
                        dragDy = event.rawY
                        if (movingWindow) {
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            windowManager?.updateViewLayout(this, params)
                        } else {
                            steer(dx, dy)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!movingWindow) {
                            knobX = 0f
                            knobY = 0f
                            invalidate()
                            PacerConfigClient.stop()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        /** Long-press-free heuristic: dragging from the rim moves the window. */
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
            canvas.drawCircle(cx + knobX, cy + knobY, radius * 0.32f, knobPaint)
        }

        /** Update the knob from a normalized joystick vector and push config. */
        fun push(x: Double, y: Double) {
            val velocity = JoystickMath.compute(x, y, SPEED_INPUT)
            knobX = (velocity.vx / SPEED_INPUT * (width / 2f - 16f)).toFloat()
            knobY = (velocity.vy / SPEED_INPUT * (width / 2f - 16f)).toFloat()
            invalidate()
            if (velocity.magnitude > 0.0) {
                PacerConfigClient.updateMotion(velocity.magnitude * SPEED_INPUT, velocity.angleDeg.toFloat())
            }
        }
    }

    private companion object {
        const val SIZE = 220
        const val SPEED_INPUT = 6.0
    }
}
