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
    private val components = ConcurrentHashMap<String, String>()

    /**
     * Records the class a system component resolved to, or "missing".
     *
     * A component that resolves to nothing turns every dependent hook into a
     * silent no-op - the hardest failure to diagnose from behaviour alone - so
     * this is what makes it a single visible line in logcat.
     */
    fun noteComponent(component: String, resolved: String?) {
        components[component] = resolved ?: "missing"
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
            components.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }
        }
        return "pacer hooks: total=${totalHooked.get()} | installed[$ok] | " +
            "components[$resolved] | failed[$bad]"
    }

    /** Clears the report; used when re-installing into a reused process. */
    fun reset() {
        totalHooked.set(0)
        hookedPerTag.clear()
        failures.clear()
        components.clear()
    }
}
