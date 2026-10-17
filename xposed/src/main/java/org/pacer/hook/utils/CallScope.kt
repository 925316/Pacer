// Thread bound re-entrancy guard.
//
// A hook that answers a call by calling back into the same API must be able to
// tell "the app asked" from "I am answering": without that, delivering a
// synthetic scan result re-enters the interceptor that was about to swallow it.
// The mature implementations carry the same helper for the same reason.
package org.pacer.hook.utils

object CallScope {

    private val inside = ThreadLocal.withInitial { false }

    /** True while the current thread is inside [enter]. */
    fun active(): Boolean = inside.get() == true

    /**
     * Runs [block] with the guard set, restoring the previous value afterwards
     * even when it throws, so one failure cannot leave the thread permanently
     * flagged.
     */
    fun <T> enter(block: () -> T): T {
        val previous = inside.get()
        inside.set(true)
        try {
            return block()
        } finally {
            inside.set(previous)
        }
    }
}
