// Step-counter / step-detector / accelerometer simulation. Waveform shape and
// cadence band come from core-motion's SensorWaveGenerator / StrideLinker, the
// pure-math version of the same curve.
package org.pacer.hook.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.hooks.env.CurrentFix
import org.pacer.hook.utils.CallScope
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import org.pacer.motion.SensorWaveGenerator
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object SensorStepHooker {

    data class CapturedListener(val listener: Any, val sensor: Sensor?, val handler: Handler?)

    private val captured = CopyOnWriteArrayList<CapturedListener>()
    private val hookedListenerClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val handleToType = ConcurrentHashMap<Int, Int>()

    private const val INITIAL_BOOT_STEPS = 2350.0
    @Volatile private var stepBase = INITIAL_BOOT_STEPS
    @Volatile private var lastSteps = INITIAL_BOOT_STEPS
    @Volatile private var lastInitTime = 0L

    /**
     * The last REAL step-counter reading this process saw, before the module
     * started overwriting it.
     *
     * TYPE_STEP_COUNTER is a count since boot, not a count since the run: an app
     * that stores it at the start of a run and subtracts it at the end computes a
     * DELTA, so a series that restarts at a synthetic boot count is a negative
     * delta and reads as "no steps at all" - a 5 km run with zero steps is a
     * contradiction the coordinates cannot explain away. Continuing from the
     * device's own count keeps the series monotonic either way, which is what an
     * app reading the raw value (rather than a delta) needs as well.
     *
     * -1 means none was seen yet, and the module falls back to its synthetic boot
     * count; a device whose sensor never reports before a run starts that way.
     */
    @Volatile private var realStepBase = -1f

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
                        // The hardware may never speak (on-change sensors), so the
                        // callback is served by this module as well. Accelerometer
                        // events are continuous, so only the two step sensors need it.
                        if (target.type == Sensor.TYPE_STEP_COUNTER || target.type == Sensor.TYPE_STEP_DETECTOR) {
                            startDelivery(CapturedListener(l, target, handler))
                        }
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    private fun hookUnregister(manager: Class<*>) {
        for (m in arrayOf("unregisterListener", "unregisterListenerImpl")) {
            HookUtils.hookAllMethods(manager, m) { chain ->
                var listener: Any? = null
                var sensor: Sensor? = null
                for (arg in chain.args) {
                    if (arg == null) continue
                    if (arg is SensorEventListener || hasTypeByName(arg.javaClass, "android.hardware.SensorEventListener")) {
                        listener = arg
                    } else if (arg is Sensor) {
                        sensor = arg
                    }
                }
                val l = listener
                if (l != null) {
                    // A registration is (listener, sensor): unregistering one sensor must
                    // leave the listener's other registrations - and their heartbeats -
                    // alone. An overload without a sensor removes everything.
                    val type = sensor?.type
                    stopDelivery(l, type)
                    if (type == null) {
                        captured.removeAll { it.listener === l }
                    } else {
                        captured.removeAll { it.listener === l && it.sensor?.type == type }
                    }
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    // --- synthetic delivery --------------------------------------------------
    //
    // TYPE_STEP_COUNTER is an ON-CHANGE sensor: a phone lying on a desk produces no
    // events at all. Rewriting what the hardware sends therefore leaves the app with
    // zero steps for the whole run while the coordinates cover kilometres - the exact
    // contradiction this module exists to avoid, and one a target app can check by
    // dividing its step count by the distance.
    //
    // So the module delivers its own events to the listeners it captured. The
    // structure is the one the BLE layer uses (a heartbeat per registered callback),
    // with one rule added for this case: the hardware path is left alone while it is
    // ALIVE. A target accumulates steps from the difference between consecutive
    // readings, so two sources feeding one listener would make the same movement
    // count twice; taking over only after the real source goes quiet keeps the series
    // monotonic and single-sourced.
    //
    // "Alive" is judged from TWO places, both genuinely hardware: the sensor event
    // queue funnel and the listener callback. Whichever sees a real event last wins,
    // so hooking only one of them still works. This module's own deliveries call the
    // listener directly, never the queue, and are marked with CallScope.

    /**
     * Identity-keyed: one listener can register for both step sensors, and the two
     * have to be served separately. Reference equality on purpose - a listener that
     * overrides equals() is still a different registration.
     */
    private class DeliveryKey(val listener: Any, val type: Int) {
        override fun equals(other: Any?): Boolean =
            other is DeliveryKey && other.listener === listener && other.type == type

        override fun hashCode(): Int = System.identityHashCode(listener) * 31 + type
    }

    /** One heartbeat per (listener, sensor type), cancelled when it unregisters. */
    private val deliveries = ConcurrentHashMap<DeliveryKey, Runnable>()

    /** When the hardware last produced an event, per sensor type, elapsed-realtime ms. */
    private val lastRealEventByType = ConcurrentHashMap<Int, Long>()

    /** How long the real source must be silent before this layer takes over. */
    private const val QUIET_MS = 2500L

    /** A registered listener with no run in progress still has to be kept alive. */
    private const val IDLE_DELAY_MS = 10000L

    private fun startDelivery(target: CapturedListener) {
        val sensor = target.sensor ?: return
        val key = DeliveryKey(target.listener, sensor.type)
        if (deliveries.containsKey(key)) return
        val handler = target.handler ?: Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    deliver(target)
                } catch (_: Throwable) {
                }
                // The heartbeat outlives a run: the listener stays registered across
                // start/stop, so it must keep ticking to serve the NEXT run. It drops to
                // an idle tick while no simulation is active rather than waking the
                // target process every second forever.
                if (deliveries[key] === this) {
                    handler.postDelayed(this, nextDelayMs(target.sensor?.type))
                }
            }
        }
        deliveries[key] = tick
        handler.postDelayed(tick, nextDelayMs(sensor.type))
    }

    /**
     * A step COUNTER carries a cumulative total, so once a second is enough. A step
     * DETECTOR carries one event per step, so it has to fire at the cadence the run
     * is producing - one event per second would report a third of the steps the
     * distance implies.
     */
    private fun nextDelayMs(type: Int?): Long {
        val cfg = PacerRuntimeConfig.snapshot()
        val speed = if (cfg.active && cfg.enableStepSimulation) CurrentFix.speedAt(cfg) else 0.0
        if (speed <= 0.05) return IDLE_DELAY_MS
        if (type != Sensor.TYPE_STEP_DETECTOR) return 1000L
        val cadence = org.pacer.motion.StrideLinker.cadenceForSpeed(speed).coerceAtLeast(60.0)
        return (60000.0 / cadence).toLong().coerceIn(150L, 1000L)
    }

    /** Constructor of the platform's own [SensorEvent], or null once it proved unusable. */
    @Volatile private var eventCtor: java.lang.reflect.Constructor<*>? = null

    /** Cleared for good the first time an event cannot be built. */
    @Volatile private var deliveryPossible = true

    /**
     * The step count a paused route keeps reporting, or null when this is not one.
     *
     * A paused route reports zero speed, so it would fall into the "no simulation"
     * branch below: the device's own counter would then be passed through to the target
     * app and also captured as [realStepBase]. Both are wrong. The app would see the user's
     * real walking while the simulated subject stands on the route, and the captured
     * base would shift the whole later series. The run still owns the count while it is
     * paused, so the frozen count is what gets reported.
     */
    private fun pausedRouteSteps(cfg: PacerRuntimeConfig.Snapshot): Long? {
        if (!cfg.active || !cfg.enableStepSimulation || !cfg.routePaused) return null
        if (cfg.routeJson.isBlank()) return null
        return currentSteps(cfg.startTimestamp, 0.0)
    }

    private fun deliver(target: CapturedListener) {
        val cfg = PacerRuntimeConfig.snapshot()
        val speed = if (cfg.active && cfg.enableStepSimulation) CurrentFix.speedAt(cfg) else 0.0
        if (speed <= 0.05) return
        val sensor = target.sensor ?: return
        // The hardware is still talking: it owns the listener until it goes quiet.
        // Read per TYPE, because the accelerometer is a stream while the step counter
        // is not - one global timestamp would let a live accelerometer starve the
        // step delivery forever.
        val lastReal = lastRealEventByType[sensor.type] ?: 0L
        if (lastReal != 0L && SystemClock.elapsedRealtime() - lastReal < QUIET_MS) return
        val event = buildEvent(sensor) ?: return
        when (sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> event.values[0] = currentSteps(cfg.startTimestamp, speed).toFloat()
            Sensor.TYPE_STEP_DETECTOR -> event.values[0] = 1.0f
            else -> return
        }
        event.timestamp = SystemClock.elapsedRealtimeNanos()
        val listener = target.listener as? SensorEventListener ?: return
        // Marked as this module's own call so the interceptor above does not mistake
        // it for a platform event and stop the watchdog it depends on.
        CallScope.enter { listener.onSensorChanged(event) }
    }

    /**
     * Builds an event to hand to the app.
     *
     * Two strategies, in order, because `SensorEvent` has no public constructor:
     *
     * 1. The platform's own package-private `SensorEvent(int)` through reflection. A
     *    FRESH object, which is what the platform itself delivers and therefore the
     *    least surprising thing a target can receive. It is a hidden API, so this can
     *    be refused on some releases; the result is validated by reading it back
     *    before use.
     * 2. A real event the platform delivered earlier, mutated in place. Local
     *    variables of the callback (`values[0]`) are all this target reads, and the
     *    step sensors carry a one-element array, so re-pointing the object at the
     *    right sensor and writing its element is safe here. Recorded as a compromise:
     *    a target that stores the event OBJECT and compares it later would see the
     *    same instance mutate, which a fresh object avoids.
     *
     * If neither works the delivery path disables itself for the session and the
     * module falls back to exactly the behaviour it had before this class gained a
     * delivery path - rewriting hardware events. Delivering a malformed event into a
     * target process would be a crash inside the app rather than a failed hook, which
     * is the one outcome not worth the risk.
     */
    private fun buildEvent(sensor: Sensor): SensorEvent? {
        if (!deliveryPossible) return null
        try {
            val ctor = eventCtor ?: SensorEvent::class.java
                .getDeclaredConstructor(Int::class.javaPrimitiveType!!)
                .also {
                    it.isAccessible = true
                    eventCtor = it
                }
            val event = ctor.newInstance(1) as SensorEvent
            event.sensor = sensor
            event.timestamp = SystemClock.elapsedRealtimeNanos()
            // Read the object back before trusting it: a construction that silently
            // produced a wrong-sized array would throw in the app's callback.
            if (event.values.size == 1) return event
            PacerLog.w("sensor-step", IllegalStateException("SensorEvent arrays are the wrong size"))
        } catch (t: Throwable) {
            PacerLog.w("sensor-step-ctor", t)
        }
        val template = templateEvent
        if (template != null && template.values.size == 1) {
            if (usingTemplate.compareAndSet(false, true)) {
                PacerLog.i("sensor-step: SensorEvent cannot be constructed; reusing a platform event")
            }
            template.sensor = sensor
            template.timestamp = SystemClock.elapsedRealtimeNanos()
            return template
        }
        deliveryPossible = false
        return null
    }

    /** A real event the platform delivered, used only when construction is refused. */
    @Volatile private var templateEvent: SensorEvent? = null

    /** Logged once, so a device report says which strategy is in use. */
    private val usingTemplate = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stopDelivery(listener: Any, type: Int? = null) {
        val removed = deliveries.keys.filter { it.listener === listener && (type == null || it.type == type) }
        for (key in removed) {
            val tick = deliveries.remove(key) ?: continue
            // The handler has to come from the captured registration, which is removed
            // after this call - so look in [captured] by listener only, and fall back
            // to the main looper if the app never gave one.
            val handler = captured.firstOrNull { it.listener === listener }?.handler
                ?: Handler(Looper.getMainLooper())
            handler.removeCallbacks(tick)
        }
    }

    private fun hookEventQueue(classLoader: ClassLoader) {
        val queue = HookUtils.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: HookUtils.findClassIfExists("android.hardware.SystemSensorManager\$BaseEventQueue", classLoader)
            ?: return
        HookUtils.hookAllMethods(queue, "dispatchSensorEvent") { chain ->
            val cfg = PacerRuntimeConfig.snapshot()
            // Ask the same source the coordinates come from: with an interval plan the
            // current speed is not the flat configured one, and steps accumulating at a
            // rate the track does not show is the disagreement this module exists to
            // avoid.
            val speed = if (cfg.active && cfg.enableStepSimulation) CurrentFix.speedAt(cfg) else 0.0
            val handle = chain.args.firstOrNull() as? Int
            val values = chain.args.getOrNull(1) as? FloatArray
            // The hardware's own funnel, and the more reliable of the two: this module's
            // deliveries call the listener directly and never pass through here, so there
            // is nothing self-feeding about it. It also covers the case where the
            // LISTENER class could not be hooked at all - without it, that case would
            // look permanently "silent" and the module would feed a live hardware stream.
            if (!CallScope.active()) {
                handle?.let { handleToType[it] }?.let { type ->
                    lastRealEventByType[type] = SystemClock.elapsedRealtime()
                }
            }
            if (speed > 0.05) {
                if (handle != null && values != null) {
                    when (handleToType[handle]) {
                        Sensor.TYPE_STEP_COUNTER ->
                            values[0] = currentSteps(cfg.startTimestamp, speed).toFloat()
                        Sensor.TYPE_STEP_DETECTOR ->
                            values[0] = 1.0f
                        Sensor.TYPE_ACCELEROMETER ->
                            if (values.size >= 3) syntheticAccel(values, speed)
                    }
                }
            } else if (handle != null && values != null && values.isNotEmpty() &&
                handleToType[handle] == Sensor.TYPE_STEP_COUNTER
            ) {
                // A paused route still owns the counter: report the frozen count rather
                // than the device's, which would include the user's real walking and
                // make the series drop when the route resumes.
                val frozen = pausedRouteSteps(cfg)
                if (frozen != null) {
                    values[0] = frozen.toFloat()
                } else {
                    // Not simulating: this is the device's own count, and it is the
                    // base a later run has to continue from. See [realStepBase].
                    realStepBase = values[0]
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookListenerClass(clazz: Class<*>) {
        if (hookedListenerClasses.putIfAbsent(clazz, true) == null) {
            HookUtils.hookAllMethods(clazz, "onSensorChanged") { chain ->
                val event: SensorEvent? = chain.args.firstOrNull() as? SensorEvent
                val sensor = event?.sensor
                // A platform delivery rather than this module's own. [CallScope] is how
                // the difference is told; the silence watchdog must only ever see the
                // hardware, otherwise this module's own delivery would keep resetting it.
                if (!CallScope.active() && event != null && sensor != null) {
                    lastRealEventByType[sensor.type] = SystemClock.elapsedRealtime()
                    if (templateEvent == null && event.values.isNotEmpty()) templateEvent = event
                }
                val cfg = PacerRuntimeConfig.snapshot()
                val speed = if (cfg.active && cfg.enableStepSimulation) CurrentFix.speedAt(cfg) else 0.0
                if (event != null && sensor != null) {
                    if (speed > 0.05) {
                        when (sensor.type) {
                            Sensor.TYPE_STEP_COUNTER -> if (event.values.isNotEmpty()) {
                                event.values[0] = currentSteps(cfg.startTimestamp, speed).toFloat()
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                            }
                            Sensor.TYPE_STEP_DETECTOR -> if (event.values.isNotEmpty()) {
                                event.values[0] = 1.0f
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                            }
                            Sensor.TYPE_ACCELEROMETER -> if (event.values.size >= 3) {
                                syntheticAccel(event.values, speed)
                                event.timestamp = SystemClock.elapsedRealtimeNanos()
                            }
                        }
                    } else if (sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                        // Same rule as the queue hook: a paused route keeps its own
                        // frozen count instead of exposing the device's.
                        val frozen = pausedRouteSteps(cfg)
                        if (frozen != null) {
                            event.values[0] = frozen.toFloat()
                        } else {
                            // The device's own count, seen before the run started: the
                            // series a run reports has to continue from it. See [realStepBase].
                            realStepBase = event.values[0]
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
        RouteEngine.sampleAt(now)?.let { return routeSteps(startTimestampMs, it.cumulativeSteps) }
        val start = startOf(startTimestampMs, now)
        resetBaseIfNewRun(start)
        if (speedMps <= 0.05) return lastSteps.toLong()
        val elapsedSec = ((now - start).coerceAtLeast(0L)) / 1000.0
        val cadence = autoCadenceSpm(speedMps)
        lastSteps = stepBase + cadence / 60.0 * elapsedSec
        return lastSteps.toLong()
    }

    /**
     * The step counter value for a route run: the run's own count, shifted onto the
     * device's series.
     *
     * The run's count starts at zero, but the sensor it is reported through counts since
     * boot. An app that samples the counter when the run starts and subtracts it at the
     * end therefore got a NEGATIVE delta as soon as the simulated count passed the real
     * one, and a run with zero steps next to several kilometres of movement is the
     * contradiction this whole class exists to avoid. The shift is applied here rather
     * than inside the engine because the engine describes the run while the offset is a
     * property of the device it is played on.
     */
    private fun routeSteps(startTimestampMs: Long, runSteps: Long): Long {
        val start = startOf(startTimestampMs, System.currentTimeMillis())
        resetBaseIfNewRun(start)
        val value = stepBase + runSteps
        if (value > lastSteps) lastSteps = value
        return value.toLong()
    }

    /** The run's start, or now for a config that never carried one. */
    private fun startOf(startTimestampMs: Long, now: Long): Long =
        if (startTimestampMs > 0L) startTimestampMs else now

    /**
     * Puts the counter series back on its base when a new run starts.
     *
     * The base is the device's own count when one was ever seen ([realStepBase]); the
     * synthetic boot count only covers a device whose counter has never reported. Zero is
     * a real reading - a phone that just booted and has not moved reports exactly that -
     * so the test is against the "never seen" sentinel, not against positivity.
     */
    private fun resetBaseIfNewRun(start: Long) {
        if (lastInitTime == start) return
        lastInitTime = start
        stepBase = if (realStepBase >= 0f) realStepBase.toDouble() else lastSteps
        lastSteps = stepBase
    }

    /** Cadence used for the accelerometer phase: route-aware when available. */
    private fun cadenceFor(speedMps: Double, now: Long): Double =
        RouteEngine.sampleAt(now)?.cadenceSpm?.takeIf { it > 0.0 } ?: autoCadenceSpm(speedMps)

    /** Speed -> cadence, delegated to the shared physical model. */
    fun autoCadenceSpm(speedMs: Double): Double =
        org.pacer.motion.StrideLinker.cadenceForSpeed(speedMs)

    /**
     * Accelerometer overlay: Z bounce plus Y sway at cadence, with small jitter.
     *
     * The waveform itself lives in the engine ([SensorWaveGenerator.Live]) rather than
     * here, so the samples an app receives are the ones the unit tests describe. A second
     * implementation of the same formula would mean the tested waveform is not the
     * shipped one.
     */
    fun syntheticAccel(
        values: FloatArray,
        speedMps: Double,
        now: Long = System.currentTimeMillis(),
    ) {
        val (ax, ay, az) = wave.at(now, cadenceFor(speedMps, now))
        values[0] = ax.toFloat()
        values[1] = ay.toFloat()
        values[2] = az.toFloat()
    }

    /**
     * One live waveform per process. The seed is deliberately not derived from the
     * config: a reproducible waveform would be a fingerprint of the module, which is
     * the same reason the coordinates carry noise.
     */
    private val wave = SensorWaveGenerator.Live()

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
