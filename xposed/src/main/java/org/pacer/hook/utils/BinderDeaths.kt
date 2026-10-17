// Tracks remote binders this module keeps a reference to, so a client that dies
// is dropped instead of being served forever.
//
// Keyed BY BINDER rather than by DeathRecipient: keying by recipient lets a
// client that registers repeatedly pile up recipients for the same binder, each
// one firing separately. One record per binder makes a repeat watch idempotent,
// and a client that goes away cleanly can be unwatched immediately instead of
// waiting for a death that never comes.
package org.pacer.hook.utils

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

object BinderDeaths {

    private class Record(val recipient: IBinder.DeathRecipient, val onDeath: () -> Unit)

    private val records = ConcurrentHashMap<IBinder, Record>()

    @Volatile private var closed = false

    /**
     * Runs [onDeath] at most once if [binder] dies. A repeat watch for the same
     * binder replaces the action and keeps the existing link, so counting on one
     * death per watch is never necessary.
     */
    fun watch(binder: IBinder, onDeath: () -> Unit) {
        if (closed) return
        val existing = records[binder]
        if (existing != null) {
            records[binder] = Record(existing.recipient, onDeath)
            return
        }
        val recipient = IBinder.DeathRecipient {
            // Removed first: the callback runs exactly once even if the death
            // notification is delivered twice.
            records.remove(binder)?.let { if (!closed) it.onDeath() }
        }
        records[binder] = Record(recipient, onDeath)
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: Throwable) {
            // Already dead, or a binder that refuses the link. Treat it as gone
            // rather than keeping a record nothing will ever fire for.
            records.remove(binder)
        }
    }

    /** Forgets [binder]: the client deregistered, so nothing is owed to it. */
    fun unwatch(binder: IBinder) {
        val record = records.remove(binder) ?: return
        try {
            binder.unlinkToDeath(record.recipient, 0)
        } catch (_: Throwable) {
        }
    }

    /** Unlinks everything. Called before the module's class loader is retired. */
    fun close() {
        closed = true
        for (binder in records.keys.toList()) unwatch(binder)
    }
}
