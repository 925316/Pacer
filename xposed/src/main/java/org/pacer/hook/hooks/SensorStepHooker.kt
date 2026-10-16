// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Step-counter / step-detector / accelerometer simulation. Adapted for Pacer
// from the public GPL-3.0 LocationSpoofer sources (see THIRD_PARTY_NOTICES.md).
// Waveform shape and cadence band are Pacer's own choices (see core-motion
// SensorWaveGenerator / StrideLinker for the pure-math version).
package org.pacer.hook.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

object SensorStepHooker {

    data class CapturedListener(val listener: Any, val sensor: Sensor?, val handler: Handler?)

    private val captured = CopyOnWriteArrayList<CapturedListener>()
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val handleToType = ConcurrentHashMap<Int, Int>()
    private val rng = Random()

    private const val INITIAL_BOOT_STEPS = 2350.0
    @Volatile private var stepBase = INITIAL_BOOT_STEPS
    @Volatile private var lastSteps = INITIAL_BOOT_STEPS
    @Volatile private var lastInitTime = 0L

    private var mockStepCounter: Sensor? = null
    private var mockStepDetector: Sensor? = null
    private var mockAccelerometer: Sensor? = null

    fun install(classLoader: ClassLoader) {
        for (name in listOf("android.hardware.SensorManager", "android.hardware.SystemSensorManager")) {
            val manager = HookUtils.findClassIfExists(name, classLoader) ?: continue
            hookGetDefaultSensor(manager, classLoader)
            hookGetSensorList(manager, classLoader)
            hookRegister(manager, classLoader)
            hookUnregister(manager)
        }
        hookEventQueue(classLoader)
        PacerLog.i("sensor step hooks installed")
    }

    private fun hookGetDefaultSensor(manager: Class<*>, classLoader: ClassLoader) {
        HookUtils.hookAllMethods(manager, "getDefaultSensor") { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            val type = chain.args.firstOrNull() as? Int
            if (result == null && (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR)) {
                return@hookAllMethods mockSensor(type, classLoader)
            }
            result
        }
    }

    private fun hookGetSensorList(manager: Class<*>, classLoader: ClassLoader) {
        HookUtils.hookAllMethods(manager, "getSensorList") { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            val type = chain.args.firstOrNull() as? Int
            if ((type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) &&
                result is List<*> && result.isEmpty()
            ) {
                val mock = mockSensor(type, classLoader)
                if (mock != null) return@hookAllMethods listOf(mock)
            }
            result
        }
    }

    private fun hookRegister(manager: Class<*>, classLoader: ClassLoader) {
        for (m in arrayOf("registerListener", "registerListenerImpl")) {
            HookUtils.hookAllMethods(manager, m) { chain ->
                var listener: Any? = null
                var sensor: Sensor? = null
                var handler: Handler? = null
                for (arg in chain.args) {
                    if (arg == null) continue
                    if (arg is SensorEventListener || hasTypeByName(arg.javaClass, "android.hardware.SensorEventListener")) {
                        listener = arg
                    } else if (arg is Sensor) {
                        sensor = arg
                    } else if (arg is Handler) {
                        handler = arg
                    }
                }
                val l = listener
                if (l != null) {
                    val target = sensor ?: mockSensor(Sensor.TYPE_STEP_COUNTER, classLoader)
                    if (target != null && (target.type == Sensor.TYPE_STEP_COUNTER ||
                            target.type == Sensor.TYPE_STEP_DETECTOR ||
                            target.type == Sensor.TYPE_ACCELEROMETER)
                    ) {
                        if (!captured.any { it.listener === l && it.sensor?.type == target.type }) {
                            captured.add(CapturedListener(l, target, handler))
                        }
                        sensorHandle(target)?.let { handleToType[it] = target.type }
                        hookListenerClass(l.javaClass)
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    private fun hookUnregister(manager: Class<*>) {
        for (m in arrayOf("unregisterListener", "unregisterListenerImpl")) {
            HookUtils.hookAllMethods(manager, m) { chain ->
                val listener = chain.args.firstOrNull {
                    it != null && (it is SensorEventListener || hasTypeByName(it.javaClass, "android.hardware.SensorEventListener"))
                }
                if (listener != null) captured.removeAll { it.listener === listener }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    private fun hookEventQueue(classLoader: ClassLoader) {
        val queue = HookUtils.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: HookUtils.findClassIfExists("android.hardware.SystemSensorManager\$BaseEventQueue", classLoader)
            ?: return
        HookUtils.hookAllMethods(queue, "dispatchSensorEvent") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            if (cfg.active && cfg.enableStepSimulation && cfg.speedMps > 0.05) {
                val handle = chain.args.firstOrNull() as? Int
                val values = chain.args.getOrNull(1) as? FloatArray
                if (handle != null && values != null) {
                    when (handleToType[handle]) {
                        Sensor.TYPE_STEP_COUNTER ->
                            values[0] = currentSteps(cfg.startTimestamp, cfg.speedMps).toFloat()
                        Sensor.TYPE_STEP_DETECTOR ->
                            values[0] = 1.0f
                        Sensor.TYPE_ACCELEROMETER ->
                            if (values.size >= 3) syntheticAccel(values, cfg.startTimestamp, cfg.speedMps)
                    }
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookListenerClass(clazz: Class<*>) {
        if (hookedListenerClasses.putIfAbsent(clazz, true) == null) {
            HookUtils.hookAllMethods(clazz, "onSensorChanged") { chain ->
                val event = chain.args.firstOrNull() as? SensorEvent
                val cfg = PacerRuntimeConfig.snapshot()
                if (event?.sensor != null && cfg.active && cfg.enableStepSimulation && cfg.speedMps > 0.05) {
                    when (event.sensor.type) {
                        Sensor.TYPE_STEP_COUNTER -> if (event.values.isNotEmpty()) {
                            event.values[0] = currentSteps(cfg.startTimestamp, cfg.speedMps).toFloat()
                            event.timestamp = SystemClock.elapsedRealtimeNanos()
                        }
                        Sensor.TYPE_STEP_DETECTOR -> if (event.values.isNotEmpty()) {
                            event.values[0] = 1.0f
                            event.timestamp = SystemClock.elapsedRealtimeNanos()
                        }
                        Sensor.TYPE_ACCELEROMETER -> if (event.values.size >= 3) {
                            syntheticAccel(event.values, cfg.startTimestamp, cfg.speedMps)
                            event.timestamp = SystemClock.elapsedRealtimeNanos()
                        }
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    /** Cumulative steps since [startTimestampMs] at the configured speed. */
    fun currentSteps(startTimestampMs: Long, speedMps: Double, now: Long = System.currentTimeMillis()): Long {
        // Route mode: steps come straight from the humanized run so they stay
        // consistent with the injected coordinates.
        RouteEngine.sampleAt(now)?.let { return it.cumulativeSteps }
        val start = if (startTimestampMs > 0L) startTimestampMs else now
        if (lastInitTime != start) {
            lastInitTime = start
            stepBase = lastSteps
        }
        if (speedMps <= 0.05) return lastSteps.toLong()
        val elapsedSec = ((now - start).coerceAtLeast(0L)) / 1000.0
        val cadence = autoCadenceSpm(speedMps)
        lastSteps = stepBase + cadence / 60.0 * elapsedSec
        return lastSteps.toLong()
    }

    /** Cadence used for the accelerometer phase: route-aware when available. */
    private fun cadenceFor(speedMps: Double, now: Long): Double =
        RouteEngine.sampleAt(now)?.cadenceSpm?.takeIf { it > 0.0 } ?: autoCadenceSpm(speedMps)

    /** Speed -> cadence, delegated to the shared physical model. */
    fun autoCadenceSpm(speedMs: Double): Double =
        org.pacer.motion.StrideLinker.cadenceForSpeed(speedMs)

    /** Accelerometer overlay: Z bounce + Y sway at cadence, small jitter. */
    fun syntheticAccel(values: FloatArray, startTimestampMs: Long, speedMps: Double, now: Long = System.currentTimeMillis()) {
        val cadence = cadenceFor(speedMps, now)
        // Accumulate phase so a changing cadence does not jump the waveform.
        if (lastPhaseMillis != 0L) {
            val dt = (now - lastPhaseMillis).coerceAtLeast(0L) / 1000.0
            phaseAccum += 2 * PI * (cadence / 60.0) * dt
        }
        lastPhaseMillis = now
        values[0] = jitter().toFloat()
        values[1] = (1.5 * sin(phaseAccum) + jitter()).toFloat()
        values[2] = (3.5 * sin(phaseAccum) + jitter()).toFloat()
    }

    @Volatile private var phaseAccum = 0.0
    @Volatile private var lastPhaseMillis = 0L

    private fun jitter(): Double = rng.nextDouble() * 0.3 - 0.15

    private fun mockSensor(type: Int, classLoader: ClassLoader): Sensor? {
        when (type) {
            Sensor.TYPE_STEP_COUNTER -> mockStepCounter?.let { return it }
            Sensor.TYPE_STEP_DETECTOR -> mockStepDetector?.let { return it }
            Sensor.TYPE_ACCELEROMETER -> mockAccelerometer?.let { return it }
        }
        return try {
            val sensorClass = Class.forName("android.hardware.Sensor", false, classLoader)
            val ctor: Constructor<*> = sensorClass.getDeclaredConstructor()
            ctor.isAccessible = true
            val sensor = ctor.newInstance() as Sensor
            setSensorField(sensor, "mType", type)
            setSensorField(
                sensor, "mName",
                when (type) {
                    Sensor.TYPE_STEP_COUNTER -> "Step Counter Sensor"
                    Sensor.TYPE_STEP_DETECTOR -> "Step Detector Sensor"
                    else -> "Accelerometer Sensor"
                },
            )
            setSensorField(sensor, "mVendor", "Android")
            setSensorField(sensor, "mVersion", 1)
            setSensorField(sensor, "mResolution", 1.0f)
            setSensorField(sensor, "mPower", 0.05f)
            when (type) {
                Sensor.TYPE_STEP_COUNTER -> mockStepCounter = sensor
                Sensor.TYPE_STEP_DETECTOR -> mockStepDetector = sensor
                else -> mockAccelerometer = sensor
            }
            sensor
        } catch (_: Throwable) {
            null
        }
    }

    private fun setSensorField(sensor: Sensor, name: String, value: Any) {
        try {
            val f: Field = Sensor::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(sensor, value)
        } catch (_: Throwable) {
        }
    }

    private fun sensorHandle(sensor: Sensor): Int? =
        try {
            val f = Sensor::class.java.getDeclaredField("mHandle")
            f.isAccessible = true
            f.getInt(sensor)
        } catch (_: Throwable) {
            null
        }

    private fun hasTypeByName(clazz: Class<*>, name: String): Boolean {
        var c: Class<*>? = clazz
        while (c != null) {
            if (c.name == name || c.interfaces.any { hasTypeByName(it, name) }) return true
            c = c.superclass
        }
        return false
    }
}
