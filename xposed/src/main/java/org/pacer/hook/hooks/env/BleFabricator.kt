// Builds synthetic BLE advertisements from the deterministic beacon model, so the
// Bluetooth view agrees with the simulated fix instead of being suppressed.
//
// What makes this safe, given that none of it can be checked from CI:
//   - The DEVICE is built with the PUBLIC BluetoothAdapter.getRemoteDevice(),
//     which validates the address and needs no hidden constructor. The hidden
//     BluetoothDevice(String) constructor is only a fallback.
//   - The advertisement bytes are built by hand and then handed to the platform's
//     own ScanRecord.parseFromBytes, so the object the app receives is one the
//     platform produced. A record that fails to parse is DROPPED rather than
//     delivered: getScanRecord() returning null is a crash in most scan callers.
//   - Only the four-argument ScanResult(BluetoothDevice, ScanRecord, int, long)
//     constructor is accepted. The longer signatures gained extra parameters with
//     unknown relative order, and a wrong guess there would silently fill real
//     fields with garbage, which readback cannot distinguish from a valid build.
//   - Everything built is validated by reading it back through the public API,
//     and if nothing can be built the fabricator disables itself and reports so.
//     The worst case is the behaviour Pacer had before: suppression.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog
import java.io.ByteArrayOutputStream
import java.lang.reflect.Constructor

object BleFabricator {

    private const val SCAN_RESULT_CLASS = "android.bluetooth.le.ScanResult"
    private const val SCAN_RECORD_CLASS = "android.bluetooth.le.ScanRecord"
    private const val BLUETOOTH_DEVICE_CLASS = "android.bluetooth.BluetoothDevice"

    /** Advertising structures cap a legacy payload at 31 bytes per field. */
    private const val MAX_NAME_BYTES = 20

    /** Set once construction has proven impossible on this build. */
    @Volatile private var unavailable = false

    @Volatile private var reported = false

    /**
     * ScanResult objects for the beacons visible at the current fix, or null when
     * the caller must suppress instead. Never throws.
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
            val resultClass = HookUtils.findClassIfExists(SCAN_RESULT_CLASS, classLoader) ?: return null
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
                buildScanResult(resultClass, beacon, classLoader, timestampNanos)?.let { out.add(it) }
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
     * The platform re-parses these bytes, so the layout has to be exactly what
     * the AD grammar expects; a malformed one yields a null ScanRecord, which is
     * why the caller drops the beacon in that case.
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
        resultClass: Class<*>,
        beacon: FakeRadioEnvironment.Beacon,
        classLoader: ClassLoader,
        timestampNanos: Long,
    ): Any? {
        val device = deviceFor(beacon.address, classLoader) ?: return null
        val record = recordFor(beacon, classLoader) ?: return null
        val rssi = beacon.rssi

        val ctor = fourArgumentConstructor(resultClass, device.javaClass, record.javaClass) ?: return null
        val candidate = instantiate(ctor, arrayOf<Any?>(device, record, rssi, timestampNanos)) ?: return null
        return if (matches(candidate, rssi, timestampNanos)) candidate else null
    }

    /**
     * The public factory first: it validates the address and creates the object
     * without touching a hidden constructor.
     */
    private fun deviceFor(address: String, classLoader: ClassLoader): Any? {
        publicRemoteDevice(address)?.let { return it }
        return hiddenDevice(address, classLoader)
    }

    private fun publicRemoteDevice(address: String): Any? =
        try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        } catch (_: Throwable) {
            null
        }

    private fun hiddenDevice(address: String, classLoader: ClassLoader): Any? {
        val clazz = HookUtils.findClassIfExists(BLUETOOTH_DEVICE_CLASS, classLoader) ?: return null
        for (ctor in clazz.declaredConstructors) {
            if (ctor.parameterCount != 1 || ctor.parameterTypes[0] != String::class.java) continue
            val candidate = instantiate(ctor, arrayOf<Any?>(address)) ?: continue
            if (addressOf(candidate)?.equals(address, ignoreCase = true) == true) return candidate
        }
        return null
    }

    private fun recordFor(beacon: FakeRadioEnvironment.Beacon, classLoader: ClassLoader): Any? {
        val recordClass = HookUtils.findClassIfExists(SCAN_RECORD_CLASS, classLoader) ?: return null
        val parse = try {
            HookUtils.findMethodExact(recordClass, "parseFromBytes", ByteArray::class.java)
        } catch (_: Throwable) {
            null
        } ?: return null
        val bytes = advertiseBytes(beacon.name, txPowerFor(beacon))
        return try {
            parse.invoke(null, bytes)
        } catch (_: Throwable) {
            null
        }
    }

    /** Transmit power at one metre, varied a little per device so all beacons do not agree. */
    private fun txPowerFor(beacon: FakeRadioEnvironment.Beacon): Int =
        -59 - Math.floorMod(beacon.address.hashCode(), 5)

    private fun fourArgumentConstructor(
        resultClass: Class<*>,
        deviceClass: Class<*>,
        recordClass: Class<*>,
    ): Constructor<*>? =
        resultClass.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterCount == 4 &&
                ctor.parameterTypes[0].isAssignableFrom(deviceClass) &&
                ctor.parameterTypes[1].isAssignableFrom(recordClass) &&
                ctor.parameterTypes[2] == Integer.TYPE &&
                ctor.parameterTypes[3] == java.lang.Long.TYPE
        }

    private fun instantiate(ctor: Constructor<*>, args: Array<Any?>): Any? =
        try {
            ctor.isAccessible = true
            ctor.newInstance(*args)
        } catch (_: Throwable) {
            null
        }

    private fun matches(result: Any, rssi: Int, timestampNanos: Long): Boolean {
        if (HookUtils.intAccessor(result, "getRssi") != rssi) return false
        if (readLong(result, "getTimestampNanos") != timestampNanos) return false
        if (read(result, "getScanRecord") == null) return false
        return read(result, "getDevice") != null
    }

    private fun addressOf(device: Any): String? = read(device, "getAddress") as? String

    private fun read(target: Any, getter: String): Any? =
        try {
            HookUtils.noArgAccessor(target.javaClass, getter)?.invoke(target)
        } catch (_: Throwable) {
            null
        }

    private fun readLong(target: Any, getter: String): Long? = read(target, getter) as? Long
}
