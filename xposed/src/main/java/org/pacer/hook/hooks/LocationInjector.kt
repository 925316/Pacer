package org.pacer.hook.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object LocationInjector {

    /** Always expose a fabricated position while a simulation is active. */
    fun install(classLoader: ClassLoader) {
        val lmClass = HookUtils.findClassIfExists("android.location.LocationManager", classLoader)
        if (lmClass != null) {
            HookUtils.hookAllMethods(lmClass, "getLastKnownLocation") { chain ->
                fakeIfActive(chain.args.firstOrNull() as? String)
                    ?: chain.proceed(chain.args.toTypedArray())
            }
            if (Build.VERSION.SDK_INT >= 30) {
                HookUtils.hookAllMethods(lmClass, "getCurrentLocation") { chain ->
                    fakeIfActive(chain.args.firstOrNull() as? String)
                        ?: chain.proceed(chain.args.toTypedArray())
                }
            }
        }
        scrubLocationFlags(classLoader)
        PacerLog.i("location injection hooks installed")
    }

    private fun fakeIfActive(provider: String?): Location? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || cfg.latitude == 0.0 || cfg.longitude == 0.0) return null
        return buildLocation(provider ?: LocationManager.GPS_PROVIDER, cfg, nowNanos = SystemClock.elapsedRealtimeNanos())
    }

    /** Build a Location object and force every mock flag to false. */
    fun buildLocation(
        provider: String,
        cfg: PacerRuntimeConfig.Snapshot,
        nowNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Location {
        val location = Location(provider)
        location.latitude = cfg.latitude
        location.longitude = cfg.longitude
        location.accuracy = cfg.accuracyMeters
        location.altitude = cfg.altitudeMeters
        location.bearing = cfg.bearingDeg
        location.speed = cfg.speedMps.toFloat()
        location.time = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 17) location.elapsedRealtimeNanos = nowNanos
        // Satellites in extras so consumers that read them see a plausible count.
        val bundle = Bundle()
        bundle.putInt("satellites", 9)
        bundle.putInt("satellitesvalue", 9)
        try {
            location.extras = bundle
        } catch (_: Throwable) {
        }
        scrub(location)
        return location
    }

    /** Clear the AOSP mock flags on a Location instance via reflection. */
    fun scrub(location: Location) {
        HookUtils.setBooleanField(location, "mIsFromMockProvider", false)
        HookUtils.setBooleanField(location, "mMock", false)
        HookUtils.setBooleanField(location, "mIsMock", false)
    }

    private fun scrubLocationFlags(classLoader: ClassLoader) {
        val locClass = HookUtils.findClassIfExists("android.location.Location", classLoader) ?: return
        HookUtils.hookAllMethods(locClass, "isFromMockProvider") { chain ->
            if (PacerRuntimeConfig.snapshot().active) return@hookAllMethods false
            chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookAllMethods(locClass, "isMock") { chain ->
            if (PacerRuntimeConfig.snapshot().active) return@hookAllMethods false
            chain.proceed(chain.args.toTypedArray())
        }
    }
}
