// Hides the Settings.Secure "mock_location" switch. The framework getters are
// hooked for every process that loads the settings classes; the settings
// provider's call() entry is hooked as well for builds that expose GET_secure
// over the provider protocol. A missing class is skipped quietly.
package org.pacer.hook.hooks.system

import android.content.ContentResolver
import android.os.Bundle
import org.pacer.hook.config.PacerRuntimeConfig
import org.pacer.hook.utils.HookUtils
import org.pacer.hook.utils.PacerLog

object SystemSettingsProviderHooker {

    private const val KEY_MOCK_LOCATION = "mock_location"
    private const val GET_SECURE = "GET_secure"

    private val providerCandidates = listOf(
        "com.android.providers.settings.SettingsProvider",
    )

    fun install(classLoader: ClassLoader) {
        hookSettingsSecure(classLoader)
        for (name in providerCandidates) {
            val clazz = HookUtils.findClassIfExists(name, classLoader) ?: continue
            hookSettingsProvider(clazz)
        }
        PacerLog.i("system settings hooks installed")
    }

    private fun hookSettingsSecure(classLoader: ClassLoader) {
        val secure = HookUtils.findClassIfExists("android.provider.Settings\$Secure", classLoader) ?: return
        HookUtils.hookExact(secure, "getString", ContentResolver::class.java, String::class.java) { chain ->
            if (PacerRuntimeConfig.snapshot().active && chain.args.getOrNull(1) == KEY_MOCK_LOCATION) {
                return@hookExact "0"
            }
            chain.proceed(chain.args.toTypedArray())
        }
        HookUtils.hookExact(
            secure,
            "getStringForUser",
            ContentResolver::class.java,
            String::class.java,
            Int::class.javaPrimitiveType!!,
        ) { chain ->
            if (PacerRuntimeConfig.snapshot().active && chain.args.getOrNull(1) == KEY_MOCK_LOCATION) {
                return@hookExact "0"
            }
            chain.proceed(chain.args.toTypedArray())
        }
    }

    private fun hookSettingsProvider(clazz: Class<*>) {
        HookUtils.hookAllMethods(clazz, "call") { chain ->
            val result = chain.proceed(chain.args.toTypedArray())
            try {
                if (!PacerRuntimeConfig.snapshot().active) return@hookAllMethods result
                val method = chain.args.getOrNull(0) as? String
                val name = (chain.args.getOrNull(1) as? String) ?: extrasName(chain.args.getOrNull(2))
                if (method == GET_SECURE && name == KEY_MOCK_LOCATION && result is Bundle) {
                    result.putString("value", "0")
                }
            } catch (t: Throwable) {
                PacerLog.w("system-settings", t)
            }
            result
        }
    }

    /** Older GET_secure calls carry the setting name inside the extras bundle. */
    private fun extrasName(extras: Any?): String? {
        val bundle = extras as? Bundle ?: return null
        return bundle.getString("name") ?: bundle.getString("setting")
    }
}
