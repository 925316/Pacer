package org.pacer.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.pacer.motion.GeoPoint
import org.pacer.motion.MotionConfig
import org.pacer.motion.RoutePath
import org.pacer.motion.RouteSimulator
import kotlin.concurrent.thread

/**
 * Minimal demo shell: runs the motion engine over a sample route and prints
 * run statistics. Real map/route UI comes in later milestones.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
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

        button = Button(this)
        button.text = "Run sample simulation"
        button.setOnClickListener { runSimulation() }
        root.addView(button)

        val startBtn = Button(this)
        startBtn.text = "Start remote simulation (campus route)"
        startBtn.setOnClickListener {
            PacerConfigClient.start(
                lat = 39.9042,
                lon = 116.4074,
                speed = 3.0,
                bearing = 45f,
            )
            output.text = "Config pushed (if service bound). Hooks read it live."
        }
        root.addView(startBtn)

        val stopBtn = Button(this)
        stopBtn.text = "Stop remote simulation"
        stopBtn.setOnClickListener {
            PacerConfigClient.stop()
            output.text = "Config: active=false."
        }
        root.addView(stopBtn)

        output = TextView(this)
        output.text = "Tap the button to simulate a 1km run."
        output.textSize = 14f
        output.setPadding(0, 32, 0, 0)
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })

        // Bind the config channel early so start/stop work immediately.
        PacerConfigClient.init()
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
}
