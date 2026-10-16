package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import kotlin.concurrent.thread

/**
 * Demo shell: preview the motion engine locally and drive the hooks through the
 * config channel. Real map picker comes later; coordinates can be typed in.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var latInput: EditText
    private lateinit var lonInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 48, 48, 48)

        val title = TextView(this)
        title.text = "Pacer"
        title.textSize = 24f
        title.gravity = Gravity.CENTER
        root.addView(title)

        latInput = numberField("Latitude (WGS84)", "39.9042")
        lonInput = numberField("Longitude (WGS84)", "116.4074")
        speedInput = numberField("Speed m/s (walk 1.4 / run 3.0 / ride 6.0)", "3.0")
        root.addView(latInput)
        root.addView(lonInput)
        root.addView(speedInput)

        val startBtn = Button(this)
        startBtn.text = "Start simulation at these coords"
        startBtn.setOnClickListener {
            val lat = latInput.text.toString().toDoubleOrNull() ?: 39.9042
            val lon = lonInput.text.toString().toDoubleOrNull() ?: 116.4074
            val speed = speedInput.text.toString().toDoubleOrNull() ?: 3.0
            PacerConfigClient.start(lat, lon, speed, bearing = 45f)
            output.text = "Pushed: lat=$lat lon=$lon speed=$speed (hooks apply live)"
        }
        root.addView(startBtn)

        val routeBtn = Button(this)
        routeBtn.text = "Start humanized route simulation"
        routeBtn.setOnClickListener {
            val speed = speedInput.text.toString().toDoubleOrNull() ?: 3.0
            PacerConfigClient.start(
                lat = 39.9042,
                lon = 116.4074,
                speed = speed,
                bearing = 45f,
                routeJson = SAMPLE_ROUTE,
            )
            output.text = "Route pushed: engine drives humanized speed along it."
        }
        root.addView(routeBtn)

        val stopBtn = Button(this)
        stopBtn.text = "Stop simulation"
        stopBtn.setOnClickListener {
            PacerConfigClient.stop()
            output.text = "Config: active=false."
        }
        root.addView(stopBtn)

        button = Button(this)
        button.text = "Preview engine locally (no hooks)"
        button.setOnClickListener { runSimulation() }
        root.addView(button)

        output = TextView(this)
        output.text = "Tip: run inside LSPosed scope for the target app."
        output.textSize = 14f
        output.setPadding(0, 32, 0, 0)
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })

        PacerConfigClient.init()
    }

    private fun numberField(hint: String, initial: String): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(initial)
        }

    private fun runSimulation() {
        button.isEnabled = false
        output.text = "Simulating..."
        thread {
            val route = RoutePath(
                listOf(
                    GeoPoint(39.900, 116.400),
                    GeoPoint(39.905, 116.404),
                    GeoPoint(39.908, 116.410),
                ),
            )
            val config = MotionConfig.run()
            val fixes = RouteSimulator(route, config).simulate(dtSec = 1.0)
            val last = fixes.last()
            val avgPaceSecPerKm = if (last.distanceMeters > 0) {
                last.tSec / last.distanceMeters * 1000
            } else 0.0
            val text = buildString {
                appendLine("Route length: ${"%.0f".format(route.totalLengthMeters)} m")
                appendLine("Simulated time: ${"%.0f".format(last.tSec)} s")
                appendLine("Steps: ${last.cumulativeSteps}")
                appendLine("Avg pace: ${"%.1f".format(avgPaceSecPerKm / 60)} min/km")
                appendLine("Final phase: ${last.phase}")
                appendLine("Samples: ${fixes.size}")
            }
            runOnUiThread {
                output.text = text
                button.isEnabled = true
            }
        }
    }

    private companion object {
        /** Sample campus polygon; [lat,lon] pairs. */
        const val SAMPLE_ROUTE = "[" +
            "[39.9042,116.4074]," +
            "[39.9052,116.4090]," +
            "[39.9081,116.4104]," +
            "[39.9095,116.4071]," +
            "[39.9080,116.4040]," +
            "[39.9050,116.4035]]"
    }
}
