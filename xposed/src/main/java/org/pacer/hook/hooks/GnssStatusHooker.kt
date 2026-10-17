package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

object GnssStatusHooker {

    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()
    private val rng = Random()

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
     * Only the signal quality is rewritten. Satellite ids, constellation types,
     * elevations and azimuths are left exactly as received: they already agree
     * with each other, and replacing them can only introduce contradictions.
     */
    fun spoof(status: Any): Boolean {
        var patched = false

        val capacity = capacity(status)
        if (capacity > 0) {
            // 8..14 is a healthy outdoor fix, but never more than can be listed.
            val count = minOf(8 + rng.nextInt(7), capacity)
            if (setIntQuietly(status, "mSvCount", count)) patched = true
            if (setIntQuietly(status, "mSvidCount", count)) patched = true
        }

        // Lift weak C/N0 into a healthy band, never degrade a good value.
        patchFloatArray(status, "mCn0DbHz") { existing ->
            maxOf(existing, 22f + rng.nextFloat() * 20f) // 22..42 dB-Hz
        }

        // A fix good enough to be reported must use enough satellites for a 3D
        // solution, otherwise the status contradicts the fix it accompanies.
        patchBoolArray(status, "mSvUsedInFix") { index, total ->
            index < minOf(total, maxOf(4, total * 3 / 4))
        }

        return patched
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

    private fun patchFloatArray(obj: Any, name: String, transform: (Float) -> Float) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val arr = f.get(obj) as? FloatArray ?: return
            for (i in arr.indices) arr[i] = transform(arr[i])
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
