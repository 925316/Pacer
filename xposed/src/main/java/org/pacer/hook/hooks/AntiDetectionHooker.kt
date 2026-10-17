// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Anti-detection hooker. Adapted for Pacer from the public GPL-3.0
// LocationSpoofer sources (see THIRD_PARTY_NOTICES.md). Core idea: intercept
// the system's own mock-location signals (AppOps OP_MOCK_LOCATION,
// Settings.Secure "mock_location", permission self-checks, system features)
// and report a clean, non-mocked device to the scoped target app.
package org.pacer.hook.hooks

import android.content.ContentResolver
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object AntiDetectionHooker {

    private const val OP_MOCK_LOCATION = 58

    /**
     * Location / bluetooth operation names. Codes are NOT hardcoded: the numeric
     * op table shifts between Android releases, and answering MODE_ALLOWED for
     * the wrong number would silently mask an unrelated permission check. They
     * are resolved through the platform's own strOpToOp() at install time.
     */
    private val locationOpNames = setOf(
        "android:coarse_location", "android:fine_location", "android:gps",
        "android:monitor_location", "android:monitor_location_high_power",
        "android:bluetooth_scan", "android:bluetooth_connect", "android:bluetooth_advertise",
    )

    private val locationOpCodes: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val locationPermissions = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )
    private val bluetoothPermissions = setOf(
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
    )

    fun install(classLoader: ClassLoader) {
        resolveLocationOpCodes(classLoader)
        hookAppOps(classLoader)
        hookSettingsSecure(classLoader)
        hookPermissionChecks(classLoader)
        hookSystemFeatures(classLoader)
        PacerLog.i("anti-detection hooks installed (locationOpCodes=$locationOpCodes)")
    }

    /** Fills [locationOpCodes] via the platform op table; empty if unavailable. */
    private fun resolveLocationOpCodes(classLoader: ClassLoader) {
        val appOps = HookUtils.findClassIfExists("android.app.AppOpsManager", classLoader) ?: return
        val strOpToOp = try {
            HookUtils.findMethodExact(appOps, "strOpToOp", String::class.java)
        } catch (_: Throwable) {
            return
        }
        for (name in locationOpNames) {
            val code = try {
                strOpToOp.invoke(null, name) as? Int
            } catch (_: Throwable) {
                null
            }
            if (code != null) locationOpCodes.add(code)
        }
    }

    private fun hookAppOps(classLoader: ClassLoader) {
        val appOps = HookUtils.findClassIfExists("android.app.AppOpsManager", classLoader) ?: return
        for (m in arrayOf("checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow", "noteProxyOp", "noteProxyOpNoThrow")) {
            HookUtils.hookAllMethods(appOps, m) { chain ->
                val cfg = PacerRuntimeConfig.snapshot()
                if (!cfg.active) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                val op = chain.args.getOrNull(0)
                if (op == OP_MOCK_LOCATION || op == "android:mock_location" || op == "mock_location") {
                    return@hookAllMethods 1 // MODE_IGNORED
                }
                val isLocation = (op is String && locationOpNames.contains(op)) ||
                    (op is Int && locationOpCodes.contains(op))
                if (isLocation) {
                    return@hookAllMethods 0 // MODE_ALLOWED, consistent with the permission hooks
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }

    private fun hookSettingsSecure(classLoader: ClassLoader) {
        val secure = HookUtils.findClassIfExists("android.provider.Settings\$Secure", classLoader) ?: return
        HookUtils.hookExact(secure, "getInt", ContentResolver::class.java, String::class.java) { chain ->
            if (PacerRuntimeConfig.snapshot().active && chain.args.getOrNull(1) == "mock_location") {
                return@hookExact 0
            }
            chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookExact(
            secure, "getInt", ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!,
        ) { chain ->
            if (PacerRuntimeConfig.snapshot().active && chain.args.getOrNull(1) == "mock_location") {
                return@hookExact 0
            }
            chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookExact(secure, "getString", ContentResolver::class.java, String::class.java) { chain ->
            if (PacerRuntimeConfig.snapshot().active && chain.args.getOrNull(1) == "mock_location") {
                return@hookExact "0"
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookPermissionChecks(classLoader: ClassLoader) {
        val classes = listOfNotNull(
            HookUtils.findClassIfExists("android.content.Context", classLoader),
            HookUtils.findClassIfExists("android.content.ContextWrapper", classLoader),
            HookUtils.findClassIfExists("android.app.ContextImpl", classLoader),
        )
        for (clazz in classes) {
            for (m in arrayOf("checkPermission", "checkSelfPermission", "checkCallingOrSelfPermission", "checkCallingPermission")) {
                HookUtils.hookAllMethods(clazz, m) { chain ->
                    val cfg = PacerRuntimeConfig.snapshot()
                    if (cfg.active) {
                        val perm = chain.args.firstOrNull { it is String } as? String
                        val fake = perm != null && (
                            locationPermissions.contains(perm) ||
                                (bluetoothPermissions.contains(perm) && cfg.mockBluetooth)
                            )
                        if (fake) return@hookAllMethods 0 // PERMISSION_GRANTED
                    }
                    chain.proceed(chain.args.toTypedArray())
                }
            }
        }
    }

    private fun hookSystemFeatures(classLoader: ClassLoader) {
        val classes = listOfNotNull(
            HookUtils.findClassIfExists("android.app.ApplicationPackageManager", classLoader),
            HookUtils.findClassIfExists("android.content.pm.PackageManager", classLoader),
        )
        for (clazz in classes) {
            HookUtils.hookAllMethods(clazz, "hasSystemFeature") { chain ->
                val feat = chain.args.firstOrNull { it is String } as? String
                val bt = feat == "android.hardware.bluetooth_le" || feat == "android.hardware.bluetooth"
                val loc = feat == "android.hardware.location.gps" || feat == "android.hardware.location.network"
                val cfg = PacerRuntimeConfig.snapshot()
                if ((bt || loc) && cfg.active && (loc || cfg.mockBluetooth)) {
                    return@hookAllMethods true
                }
                chain.proceed(chain.args.toTypedArray())
            }
        }
    }
}
