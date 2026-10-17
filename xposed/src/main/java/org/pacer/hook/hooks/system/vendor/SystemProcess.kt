package org.pacer.hook.hooks.system.vendor

import java.io.File

enum class SystemProcess {
    SYSTEM_SERVER,
    PHONE,
    BLUETOOTH,
    UNKNOWN,
}

object ProcessIdentity {

    /**
     * The process name, read from /proc/self/cmdline.
     *
     * Deliberately not taken from the package manager: system_server is not an
     * installed package, so a package-based answer would report it as the
     * framework's own package and the system layer could never be entered.
     */
    @Volatile private var cached: SystemProcess? = null

    fun current(): SystemProcess {
        cached?.let { return it }
        val process = classify(readCmdline())
        cached = process
        return process
    }

    fun classify(cmdline: String?): SystemProcess {
        val name = cmdline?.trim().orEmpty()
        return when {
            name.isEmpty() -> SystemProcess.UNKNOWN
            name == "system_server" || name == "system" -> SystemProcess.SYSTEM_SERVER
            name.startsWith("com.android.phone") -> SystemProcess.PHONE
            name.startsWith("com.android.bluetooth") -> SystemProcess.BLUETOOTH
            else -> SystemProcess.UNKNOWN
        }
    }

    private fun readCmdline(): String? =
        try {
            val bytes = File("/proc/self/cmdline").readBytes()
            // The file is NUL separated and NUL terminated.
            val end = bytes.indexOfFirst { it == 0.toByte() }
            val name = if (end >= 0) bytes.copyOfRange(0, end) else bytes
            name.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
}
