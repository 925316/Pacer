// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Builds synthetic BLE advertisements from the deterministic beacon model, so the
// Bluetooth view agrees with the simulated fix instead of being suppressed.
//
// What makes this safe, given how little of it a device-free environment can
// check:
//   - Everything that CAN be typed is typed. The four-argument
//     ScanResult(BluetoothDevice, ScanRecord, int, long) constructor and the
//     public getRemoteDevice() factory are both public API in the compile SDK, so
//     the compiler checks them and CI fails if a future SDK moves them. The
//     ten-argument constructor is deliberately not used: its parameters turned out
//     to be ordered (device, seven ints, record, long), so the ints cannot be
//     filled by position without knowing which is which, and a wrong guess would
//     silently write garbage that readback cannot distinguish from a valid build.
//   - The record is the one part with no usable constructor, so its bytes are
//     built by hand and handed to the platform's own parser. A record that fails
//     to parse is DROPPED rather than delivered: getScanRecord() returning null is
//     a crash in most scan callers.
//   - Every constructed result is read back through the public API before it can
//     reach the app, and if nothing can be built the fabricator disables itself
//     and reports so. The worst case is the behaviour Pacer had before:
//     suppression.
package org.pacer.hook.hooks.env

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.io.ByteArrayOutputStream

object BleFabricator {

    private const val SCAN_RECORD_CLASS = "android.bluetooth.le.ScanRecord"
    private const val BLUETOOTH_DEVICE_CLASS = "android.bluetooth.BluetoothDevice"

    /** A legacy advertising field carries at most 31 bytes; stay well inside it. */
    private const val MAX_NAME_BYTES = 20

    /** Set once construction has proven impossible on this build. */
    @Volatile private var unavailable = false

    @Volatile private var reported = false

    /**
     * Scan results for the beacons visible at the current fix, or null when the
     * caller must suppress instead. Never throws.
     *
     * @param nowMs elapsed realtime in milliseconds; converted to the nanosecond
     *   timestamp real scan results carry.
     */
    fun scanResults(
        cfg: PacerRuntimeConfig.Snapshot,
        classLoader: ClassLoader,
        nowMs: Long,
    ): List<Any>? {
        if (unavailable) return null
        return try {
            val beacons = FakeRadioEnvironment.beacons(
                cfg.latitude,
                cfg.longitude,
                EnvGeo.sessionSeed(cfg.startTimestamp),
                nowMs,
            )
            if (beacons.isEmpty()) return null

            val timestampNanos = nowMs * 1_000_000L
            val out = ArrayList<Any>(beacons.size)
            for (beacon in beacons) {
                buildScanResult(beacon, classLoader, timestampNanos)?.let { out.add(it) }
            }

            if (out.isEmpty()) {
                unavailable = true
                PacerLog.i("ble fabrication unsupported on this build; suppressing beacons instead")
                null
            } else {
                if (!reported) {
                    reported = true
                    PacerLog.i("ble fabrication active (${out.size} beacon(s))")
                }
                out
            }
        } catch (t: Throwable) {
            unavailable = true
            PacerLog.w("ble-fabrication", t)
            null
        }
    }

    /**
     * A minimal, valid advertisement: flags, complete local name, transmit power.
     *
     * The platform re-parses these bytes, so the layout has to match the AD
     * grammar exactly; a malformed one yields a null ScanRecord, which is why the
     * caller drops the beacon in that case.
     */
    fun advertiseBytes(name: String, txPowerDbm: Int): ByteArray {
        val out = ByteArrayOutputStream(32)
        // Flags: LE General Discoverable Mode, BR/EDR not supported.
        out.write(0x02)
        out.write(0x01)
        out.write(0x06)

        val nameBytes = name.toByteArray(Charsets.UTF_8).copyOf(MAX_NAME_BYTES)
        val trimmed = nameBytes.takeWhile { it != 0.toByte() }.toByteArray()
        if (trimmed.isNotEmpty()) {
            out.write(trimmed.size + 1)
            out.write(0x09)
            out.write(trimmed)
        }

        out.write(0x02)
        out.write(0x0A)
        out.write(txPowerDbm and 0xFF)
        return out.toByteArray()
    }

    private fun buildScanResult(
        beacon: FakeRadioEnvironment.Beacon,
        classLoader: ClassLoader,
        timestampNanos: Long,
    ): ScanResult? {
        val device = deviceFor(beacon.address, classLoader) ?: return null
        val record = recordFor(beacon, classLoader) ?: return null

        val result = try {
            ScanResult(device, record, beacon.rssi, timestampNanos)
        } catch (_: Throwable) {
            null
        } ?: return null

        // Read it back through the public API before the app can ever see it.
        if (result.rssi != beacon.rssi) return null
        if (result.timestampNanos != timestampNanos) return null
        if (result.scanRecord == null) return null
        if (result.device == null) return null
        return result
    }

    /**
     * The public factory first: it validates the address and creates the object
     * without touching a hidden constructor or the Bluetooth service.
     */
    private fun deviceFor(address: String, classLoader: ClassLoader): BluetoothDevice? =
        publicRemoteDevice(address) ?: hiddenDevice(address, classLoader)

    private fun publicRemoteDevice(address: String): BluetoothDevice? =
        try {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        } catch (_: Throwable) {
            null
        }

    private fun hiddenDevice(address: String, classLoader: ClassLoader): BluetoothDevice? {
        val clazz = HookUtils.findClassIfExists(BLUETOOTH_DEVICE_CLASS, classLoader) ?: return null
        for (ctor in clazz.declaredConstructors) {
            if (ctor.parameterCount != 1 || ctor.parameterTypes[0] != String::class.java) continue
            val candidate = try {
                ctor.isAccessible = true
                ctor.newInstance(address) as? BluetoothDevice
            } catch (_: Throwable) {
                null
            } ?: continue
            if (candidate.address.equals(address, ignoreCase = true)) return candidate
        }
        return null
    }

    /**
     * ScanRecord has no usable constructor, so the bytes go through the platform
     * parser: whatever comes back is an object the platform itself built.
     */
    private fun recordFor(beacon: FakeRadioEnvironment.Beacon, classLoader: ClassLoader): ScanRecord? {
        val recordClass = HookUtils.findClassIfExists(SCAN_RECORD_CLASS, classLoader) ?: return null
        val parse = try {
            HookUtils.findMethodExact(recordClass, "parseFromBytes", ByteArray::class.java)
        } catch (_: Throwable) {
            null
        } ?: return null
        val bytes = advertiseBytes(beacon.name, txPowerFor(beacon))
        return try {
            parse.invoke(null, bytes) as? ScanRecord
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Transmit power at one metre, varied a little per device so every beacon does
     * not report the same value.
     */
    private fun txPowerFor(beacon: FakeRadioEnvironment.Beacon): Int =
        -59 - Math.floorMod(beacon.address.hashCode(), 5)
}
