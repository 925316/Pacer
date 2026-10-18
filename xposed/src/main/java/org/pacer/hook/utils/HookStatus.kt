// Per-process install report.
//
// The module has never run on a real device during development, so the hard
// question is always "did the hook actually attach inside this process?".
// Every installer runs through HookUtils.runCatchingLogged, which records here
// how many methods it attached and whether it threw, and PacerHook logs one
// summary line at the end. Filtering logcat for "pacer hooks:" therefore tells
// the user exactly which installers worked in the target process, instead of
// leaving them to guess from behaviour.
package org.pacer.hook.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object HookStatus {

    private val totalHooked = AtomicInteger(0)
    private val hookedPerTag = ConcurrentHashMap<String, Int>()
    private val failures = ConcurrentHashMap<String, String>()

    /** How a system component resolved: the class, the candidate that matched, the loader. */
    private data class Resolution(
        val resolved: String?,
        val candidateIndex: Int,
        val candidateCount: Int,
        val loader: String?,
    )

    private val components = ConcurrentHashMap<String, Resolution>()

    /**
     * Records which class a system component resolved to, and how.
     *
     * A component that resolves to nothing turns every dependent hook into a
     * silent no-op - the hardest failure to diagnose from behaviour alone - so a
     * miss reports how many names were searched before giving up. A hit reports
     * the loader only when it is not the obvious one: "via apex" means the default
     * loader could not see the service, which is the normal state of Wi-Fi,
     * connectivity and telephony on Android 12 and later.
     */
    fun noteComponent(
        component: String,
        resolved: String?,
        candidateIndex: Int,
        candidateCount: Int,
        loader: String?,
    ) {
        components[component] = Resolution(resolved, candidateIndex, candidateCount, loader)
    }

    /** Monotonic count of methods/constructors hooked in this process. */
    fun countHooked(): Int = totalHooked.get()

    /**
     * Records an installer that ran to completion, together with how many
     * hooks it added (the delta in [countHooked] across the call).
     */
    fun recordOk(tag: String, added: Int) {
        if (added > 0) totalHooked.addAndGet(added)
        hookedPerTag.merge(tag, added, Int::plus)
    }

    /** Records an installer that threw; the message is kept short for logcat. */
    fun recordFailure(tag: String, t: Throwable) {
        failures[tag] = t.javaClass.simpleName + ": " + (t.message ?: "no message")
    }

    /** One line suitable for logcat. */
    fun summary(): String {
        val ok = hookedPerTag.entries
            .sortedBy { it.key }
            .joinToString(", ") { "${it.key}=${it.value}" }
        val bad = if (failures.isEmpty()) {
            "none"
        } else {
            failures.entries.joinToString(", ") { "${it.key}(${it.value})" }
        }
        val resolved = if (components.isEmpty()) {
            "none"
        } else {
            components.entries.sortedBy { it.key }.joinToString(", ") { describe(it.key, it.value) }
        }
        return "pacer hooks: total=${totalHooked.get()} | installed[$ok] | " +
            "components[$resolved] | failed[$bad]"
    }

    /**
     * One component as it reads in the report. The quiet case is just
     * `NAME=class`; `#2/3` says a later candidate matched, `via apex` says the
     * ordinary loaders could not see the class, and `missing(3 searched)` says
     * nothing matched at all.
     */
    private fun describe(component: String, r: Resolution): String {
        val name = r.resolved ?: return "$component=missing(${r.candidateCount} searched)"
        val at = if (r.candidateIndex > 1) "#${r.candidateIndex}/${r.candidateCount}" else ""
        val via = if (r.loader != null && r.loader != "caller") " via ${r.loader}" else ""
        return "$component=$name$at$via"
    }
}
