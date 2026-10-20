package org.pacer.hook.hooks.system.vendor

import java.io.File

enum class SystemProcess {
    SYSTEM_SERVER,
    PHONE,
    BLUETOOTH,
    /** The SettingsProvider process: com.android.providers.settings. */
    SETTINGS,
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

    /** The same cmdline, kept so the per-app coordinate override can name the package. */
    @Volatile private var cachedName: String? = null

    fun current(): SystemProcess {
        cached?.let { return it }
        val name = readCmdline()
        cachedName = name
        val process = classify(name)
        cached = process
        return process
    }

    /**
     * The process name (`com.example.app` or `com.example.app:remote`), or null.
     *
     * The same value [current] classifies, cached together with it so the two can never
     * describe different reads.
     */
    fun processName(): String? {
        current()
        return cachedName
    }

    fun classify(cmdline: String?): SystemProcess {
        val name = cmdline?.trim().orEmpty()
        return when {
            name.isEmpty() -> SystemProcess.UNKNOWN
            name == "system_server" || name == "system" -> SystemProcess.SYSTEM_SERVER
            name.startsWith("com.android.phone") -> SystemProcess.PHONE
            name.startsWith("com.android.bluetooth") -> SystemProcess.BLUETOOTH
            // SettingsProvider serves Settings.Secure over the provider protocol and
            // lives here, not in system_server. Installing its hook in system_server
            // silently resolved nothing, which is why the scope entry did nothing.
            name.startsWith("com.android.providers.settings") -> SystemProcess.SETTINGS
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
