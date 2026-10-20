package org.pacer.app

import android.content.Context

/**
 * The two choices that belong to this app rather than to a run: the interface language and
 * whether the startup screen should be skipped.
 *
 * Kept out of the Xposed preference group on purpose. That group is the channel the module
 * reads, and a UI preference written there would be pushed to every hooked process on every
 * config change for no reason at all.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("pacer_app", Context.MODE_PRIVATE)

    var language: Language
        get() = Language.of(prefs.getString(KEY_LANGUAGE, null))
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()
        }

    /** True once the user has asked not to see the startup screen again. */
    var skipStartup: Boolean
        get() = prefs.getBoolean(KEY_SKIP_STARTUP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SKIP_STARTUP, value).apply()
        }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_SKIP_STARTUP = "skip_startup"
    }
}
