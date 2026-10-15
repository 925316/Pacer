package org.pacer.hook

/**
 * Reserved hook entry placeholder.
 *
 * No hook framework is wired yet on purpose: the real LSPosed entry point
 * will be designed from scratch in milestone M4 (anti-detection suite),
 * not copied from any existing module.
 */
object PacerHookPlaceholder {
    const val TARGET_PACKAGE: String = "org.pacer.app"

    fun describe(): String = "Pacer hook placeholder — no hooks registered."
}
