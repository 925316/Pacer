// The satellite counts most recently reported to this process, plus the
// capacity of the real GnssStatus arrays.
//
// Two interfaces describe the same sky: the extras on a Location and the
// GnssStatus the GNSS callback delivers. If each one invented its own counts,
// an app that reads both would see a contradiction without any single hook
// being detectable. So the injector publishes what it computed, the status
// patcher reports exactly that (clamped to the arrays the real status carries),
// and the capacity the patcher learns is published back so the extras never
// claim more satellites than the receiver can list.
package org.pacer.hook.hooks

import org.pacer.motion.SatelliteStatus

object LastSky {

    /** Before anything has been built: a plausible mid-latitude sky. */
    @Volatile private var status: SatelliteStatus = SatelliteStatus.withFix(11, 9)

    /** Length of the shortest indexed array in the real status; 0 until learned. */
    @Volatile private var capacity = 0

    /** Publishes the counts for this moment, clamped to the known capacity. */
    @Synchronized
    fun publish(inView: Int, usedInFix: Int): SatelliteStatus {
        val cap = capacity
        val next = if (cap > 0) {
            SatelliteStatus.of(minOf(inView, cap), minOf(usedInFix, cap))
        } else {
            SatelliteStatus.withFix(inView, usedInFix)
        }
        status = next
        return next
    }

    fun current(): SatelliteStatus = status

    /**
     * Learned from the real GnssStatus: how many satellites this receiver can
     * actually list. Only the first non-zero answer is kept, and the published
     * status is re-clamped so a value published before the capacity was known
     * cannot claim more than the receiver can carry.
     */
    @Synchronized
    fun noteCapacity(arrays: Int) {
        if (arrays <= 0) return
        if (capacity == 0) capacity = arrays
        status = SatelliteStatus.of(
            minOf(status.inView, capacity),
            minOf(status.usedInFix, capacity),
        )
    }
}
