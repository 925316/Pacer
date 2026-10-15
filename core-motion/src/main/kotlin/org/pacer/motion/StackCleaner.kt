// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

/**
 * Filters framework-injected frames out of a stack trace so that callers
 * inspecting [Throwable.getStackTrace] / [Thread.getStackTrace] do not see
 * obvious hook-framework signatures.
 *
 * Pure logic (operates on class-name strings) so it is unit-testable on the
 * JVM without any Android or LSPosed dependency. The real hook will call this
 * with the actual stack trace strings.
 *
 * Detection-target class-name patterns are Pacer's own list, derived from
 * public knowledge of common hook frameworks (de.robv.android.xposed,
 * org.lsposed, io.github.libxposed). Keep it small and boring on purpose:
 * only match the well-known framework packages, never user app classes.
 */
object StackCleaner {

    /** Class-name fragments considered framework-internal. */
    val FRAMEWORK_FRAGMENTS: List<String> = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.libxposed",
        "LSPosedBridge",
        "XposedBridge",
        "XposedHelpers",
        "EdHooker",
        "sandhook",
        "whale.",
    )

    /**
     * Returns true when [className] looks like a hook-framework frame.
     */
    fun isFrameworkFrame(className: String): Boolean =
        FRAMEWORK_FRAGMENTS.any { className.contains(it, ignoreCase = true) }

    /**
     * Returns [frames] with framework frames removed. The result is never
     * empty: if everything matched (pathological case), the original list is
     * returned unchanged so callers never lose the entire trace.
     */
    fun clean(frames: List<String>): List<String> {
        val kept = frames.filterNot { isFrameworkFrame(it) }
        return kept.ifEmpty { frames }
    }
}
