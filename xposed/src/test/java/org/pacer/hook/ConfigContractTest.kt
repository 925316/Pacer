// The cross-process config contract, checked against the two SOURCE FILES instead
// of against classes: the writer lives in the app module and this reader in the
// module, so neither can see the other's code, and a key only one of them knows
// about fails silently on a device.
//
// That is not hypothetical. The Bluetooth switch was once written nowhere at all,
// so the whole BLE layer was unreachable while every log line looked correct. The
// same shape of bug is one typo away at any time and the compiler cannot see it.
// A relative path is used because Gradle runs tests with the module directory as
// the working directory; the existence assertions below make a wrong assumption
// fail loudly rather than pass vacuously.
package org.pacer.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigContractTest {

    @Test
    fun theAppWritesEveryKeyTheHookSideReads() {
        assertTrue("writer not found: ${CLIENT.absolutePath}", CLIENT.isFile)
        assertTrue("reader not found: ${SYNC.absolutePath}", SYNC.isFile)

        val written = keysIn(CLIENT, """put(?:Boolean|Long|Float|Int|String)\(\s*"([a-z_0-9]+)"""")
        val read = keysIn(SYNC, """(?:getBoolean|getLong|getFloat|getInt|getString)\(\s*"([a-z_0-9]+)"""") +
            keysIn(SYNC, """doubleOf\(\s*prefs,\s*"([a-z_0-9]+)"""")

        assertEquals("keys the hook side reads but the app never writes", emptySet<String>(), read - written)
        assertEquals("keys the app writes but the hook side never reads", emptySet<String>(), written - read)
    }

    private fun keysIn(file: File, pattern: String): Set<String> =
        Regex(pattern)
            .findAll(file.readText())
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
            .toSet()

    private companion object {
        val CLIENT = File("../app/src/main/java/org/pacer/app/PacerConfigClient.kt")
        val SYNC = File("src/main/java/org/pacer/hook/config/ConfigSync.kt")
    }
}
