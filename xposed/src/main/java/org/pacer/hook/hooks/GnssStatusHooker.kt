// GNSS satellite-status spoofing. Instead of allocating a whole synthetic
// GnssStatus (hidden constructors differ per OEM), we sanitise the incoming
// status object in place: the receiver's own satellite ids, constellations,
// elevations and azimuths are kept, and only the signal quality is lifted. See
// spoof() for the invariant that must hold.
package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

object GnssStatusHooker {

    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()

    /**
     * Fields that getSvid(i) / getCn0DbHz(i) / usedInFix(i) /
     * getConstellationType(i) index into. The reported satellite count must stay
     * within the shortest of them. Both id spellings are listed because the array
     * was renamed from mSvids to mSvIds across framework versions.
     */
    private val INDEXED_ARRAY_FIELDS = arrayOf(
        "mSvids",
        "mSvIds",
        "mConstellationTypes",
        "mCn0DbHz",
        "mSvElevations",
        "mSvAzimuths",
        "mSvUsedInFix",
    )

    fun install(classLoader: ClassLoader) {
        val lm = HookUtils.findClassIfExists("android.location.LocationManager", classLoader) ?: return

        // Legacy GPS status listener registration: just capture and pass through.
        HookUtils.hookAllMethods(lm, "addGpsStatusListener") { chain ->
            val listener = chain.args.firstOrNull()
            if (listener != null) hookCallbackClass(listener.javaClass)
            chain.proceed(chain.args.toTypedArray())
        }

        // Modern GnssStatus.Callback registration: capture the callback class
        // so we can patch onSatelliteStatusChanged below.
        HookUtils.hookAllMethods(lm, "registerGnssStatusCallback") { chain ->
            for (arg in chain.args) {
                if (arg != null && hasTypeByName(arg.javaClass, "android.location.GnssStatus\$Callback")) {
                    hookCallbackClass(arg.javaClass)
                    break
                }
            }
            chain.proceed(chain.args.toTypedArray())
        }
        PacerLog.i("gnss status hooks installed")
    }

    private fun hookCallbackClass(clazz: Class<*>) {
        if (hookedCallbacks.putIfAbsent(clazz, true) == null) {
            for (m in arrayOf("onSatelliteStatusChanged", "onGpsStatusChanged")) {
                HookUtils.hookAllMethods(clazz, m) { chain ->
                    if (PacerRuntimeConfig.snapshot().active) {
                        chain.args.firstOrNull()?.let { spoof(it) }
                    }
                    chain.proceed(chain.args.toTypedArray())
                }
            }
        }
    }

    /**
     * Sanitises a GnssStatus in place and returns true when it was modified.
     *
     * INVARIANT, must never be broken:
     * `getSatelliteCount() <= every backing array's length`.
     * getSatelliteCount() returns the count FIELD while getSvid(i) /
     * getCn0DbHz(i) / usedInFix(i) index the ARRAYS, so reporting more
     * satellites than the arrays hold makes the host app throw
     * ArrayIndexOutOfBoundsException the moment it iterates them. Reporting
     * fewer is harmless.
     *
     * An in-place patch that reports a count the backing arrays do not have is the
     * classic inconsistent-container fault (it surfaces as
     * Collections$UnmodifiableList.set from within the patch), so the count is
     * clamped to the receiver's real capacity instead of being invented. That also
     * means a weak fix cannot be talked up into a strong one here; raising the
     * satellite count requires a synthetic GnssStatus, which the OEM-specific hidden
     * constructors make fragile.
     *
     * Only the signal quality and the counts are rewritten. Satellite ids,
     * constellation types, elevations and azimuths are left exactly as received:
     * they already agree with each other, and replacing them can only introduce
     * contradictions.
     *
     * The counts come from [LastSky], which the location injector publishes, so the
     * status and the Location extras describe the same sky. Inventing a second
     * number here would let an app read two answers to one question.
     */
    fun spoof(status: Any): Boolean {
        var patched = false

        // Report the published counts, clamped to what this status can actually carry:
        // its array lengths and the count the receiver itself reports. Reporting more
        // than the real sky holds would mean advertising satellites whose entries are
        // zeroes, and iterating past the arrays would crash the host app.
        val capacity = capacity(status)
        if (capacity > 0) {
            val reported = realCount(status)
            val limit = if (reported > 0) minOf(reported, capacity) else capacity
            LastSky.noteCapacity(limit)
            val sky = LastSky.current()
            val count = minOf(sky.inView, limit)
            if (setIntQuietly(status, "mSvCount", count)) patched = true
            if (setIntQuietly(status, "mSvidCount", count)) patched = true

            // A fix good enough to be reported must use enough satellites for a 3D
            // solution, and it must use exactly the number the extras claim. The used
            // set is rotated rather than always the first N: a real receiver drops
            // individual satellites, it does not sort its sky into a prefix.
            val used = minOf(sky.usedInFix, count)
            val rotation = if (count <= 0) 0 else (SystemClock.elapsedRealtime() / 60000L % count).toInt()
            patchBoolArray(status, "mSvUsedInFix") { index, _ ->
                if (used <= 0) false else Math.floorMod(index - rotation, count) < used
            }
        }

        // Lift weak C/N0 into a healthy band, never degrade a good value. The lift
        // depends on the index, so a satellite reports the same signal quality on
        // every callback instead of flickering between random values.
        patchFloatArray(status, "mCn0DbHz") { index, existing ->
            maxOf(existing, 30f + (index % 4) * 3f) // 30..39 dB-Hz
        }

        return patched
    }

    /** The count the receiver itself puts in the status, or 0 when unreadable. */
    private fun realCount(status: Any): Int {
        for (name in arrayOf("mSvCount", "mSvidCount")) {
            val value = try {
                val f = status.javaClass.getDeclaredField(name)
                f.isAccessible = true
                f.getInt(status)
            } catch (_: Throwable) {
                -1
            }
            if (value > 0) return value
        }
        return 0
    }

    /** Smallest length among the arrays that are indexed by satellite. */
    private fun capacity(status: Any): Int {
        var smallest = Int.MAX_VALUE
        for (name in INDEXED_ARRAY_FIELDS) {
            val length = arrayLength(status, name) ?: continue
            if (length < smallest) smallest = length
        }
        return if (smallest == Int.MAX_VALUE) 0 else smallest
    }

    private fun arrayLength(obj: Any, name: String): Int? =
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val value = f.get(obj) ?: return null
            if (!value.javaClass.isArray) null else java.lang.reflect.Array.getLength(value)
        } catch (_: Throwable) {
            null
        }

    private fun setIntQuietly(obj: Any, name: String, value: Int): Boolean =
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.setInt(obj, value)
            true
        } catch (_: Throwable) {
            false
        }

    private fun patchFloatArray(obj: Any, name: String, transform: (Int, Float) -> Float) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val arr = f.get(obj) as? FloatArray ?: return
            for (i in arr.indices) arr[i] = transform(i, arr[i])
        } catch (_: Throwable) {
        }
    }

    private fun patchBoolArray(obj: Any, name: String, transform: (Int, Int) -> Boolean) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val arr = f.get(obj) as? BooleanArray ?: return
            for (i in arr.indices) arr[i] = transform(i, arr.size)
        } catch (_: Throwable) {
        }
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
