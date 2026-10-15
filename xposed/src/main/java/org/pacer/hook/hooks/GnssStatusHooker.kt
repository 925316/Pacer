// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// GNSS satellite-status spoofing. Adapted for Pacer from the public GPL-3.0
// LocationSpoofer GnssStatusHooker (see THIRD_PARTY_NOTICES.md). Instead of
// allocating a whole synthetic GnssStatus (hidden constructors differ per
// OEM), we patch the incoming status object's internal svCount / C/N0 arrays
// in place so a moving position is backed by plausible satellites.
package org.pacer.hook.hooks

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

object GnssStatusHooker {

    private val hookedCallbacks = ConcurrentHashMap<Class<*>, Boolean>()
    private val rng = Random()

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
     * Patch a GnssStatus instance in place: bump svCount into the 12..14 band
     * and fill C/N0, elevation, azimuth and used-in-fix arrays so the numbers
     * look like a healthy outdoor fix. Returns true if anything was patched.
     */
    fun spoof(status: Any): Boolean {
        val count = 12 + rng.nextInt(3) // 12..14
        var patched = false

        // int mSvCount (older: int mSvidCount)
        if (setIntQuietly(status, "mSvCount", count)) patched = true
        if (setIntQuietly(status, "mSvidCount", count)) patched = true
        if (!patched) return false

        // Fill float arrays sized >= count when present.
        fillFloatArray(status, "mCn0DbHz") { 25f + rng.nextFloat() * 20f } // 25..45 dB-Hz
        fillFloatArray(status, "mSvElevations") { 10f + rng.nextFloat() * 70f }
        fillFloatArray(status, "mSvAzimuths") { rng.nextFloat() * 360f }
        fillBoolArray(status, "mSvUsedInFix") { rng.nextInt(6) < 4 }
        fillIntArray(status, "mSvids") { 1 + rng.nextInt(64) }
        fillIntArray(status, "mSvIds") { 1 + rng.nextInt(64) }
        return true
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

    private fun fillFloatArray(obj: Any, name: String, gen: () -> Float) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val arr = f.get(obj) as? FloatArray ?: return
            for (i in arr.indices) arr[i] = gen()
        } catch (_: Throwable) {
        }
    }

    private fun fillIntArray(obj: Any, name: String, gen: () -> Int) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val arr = f.get(obj) as? IntArray ?: return
            for (i in arr.indices) arr[i] = gen()
        } catch (_: Throwable) {
        }
    }

    private fun fillBoolArray(obj: Any, name: String, gen: () -> Boolean) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val arr = f.get(obj) as? BooleanArray ?: return
            for (i in arr.indices) arr[i] = gen()
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
