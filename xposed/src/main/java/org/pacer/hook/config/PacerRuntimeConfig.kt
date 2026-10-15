package org.pacer.hook.config

/**
 * In-process runtime configuration consulted by the hooks.
 *
 * The remote-config channel (app -> framework service -> hook) is a later
 * milestone; until then this holder keeps the hook logic testable and lets the
 * module compile with safe defaults (everything off).
 */
object PacerRuntimeConfig {

    @Volatile var active: Boolean = false

    /** Current simulated speed in m/s, 0 means stationary. */
    @Volatile var speedMps: Double = 0.0

    @Volatile var enableStepSimulation: Boolean = true

    @Volatile var mockBluetooth: Boolean = false

    /** Wall-clock millis when the current simulation started. */
    @Volatile var startTimestamp: Long = 0L

    fun snapshot(): Snapshot = Snapshot(
        active = active,
        speedMps = speedMps,
        enableStepSimulation = enableStepSimulation,
        mockBluetooth = mockBluetooth,
        startTimestamp = startTimestamp,
    )

    data class Snapshot(
        val active: Boolean,
        val speedMps: Double,
        val enableStepSimulation: Boolean,
        val mockBluetooth: Boolean,
        val startTimestamp: Long,
    )
}
