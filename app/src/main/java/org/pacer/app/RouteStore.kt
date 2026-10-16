// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.app

import android.content.Context
import org.pacer.motion.RouteDraft

/**
 * Keeps the in-progress route across launches, so a half-built route is not
 * lost when the screen is closed. Stored with the shared [RouteDraft] JSON
 * format, which is also what is pushed to the hook.
 */
class RouteStore(context: Context) {

    private val prefs = context.getSharedPreferences("pacer_routes", Context.MODE_PRIVATE)

    fun load(): RouteDraft =
        RouteDraft.parse(prefs.getString(KEY_DRAFT, "") ?: "")

    fun save(draft: RouteDraft) {
        prefs.edit().putString(KEY_DRAFT, draft.toJson()).apply()
    }

    private companion object {
        const val KEY_DRAFT = "draft"
    }
}
