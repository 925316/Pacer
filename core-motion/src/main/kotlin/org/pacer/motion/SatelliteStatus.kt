package org.pacer.motion

/**
 * Satellite counts for one moment, in the only shape that can be reported
 * without contradicting itself: the number used in the fix can never exceed the
 * number the receiver can see.
 *
 * Four interfaces describe this sky (Location extras, NMEA, GnssStatus, and the
 * fixed-satellite query apps use as a cheap GPS health check), and each of them
 * is read on its own. A detector does not need to find a hook for any of them if
 * two of them disagree, so the clamping lives in one place and every layer
 * builds its numbers through it.
 */
data class SatelliteStatus(val inView: Int, val usedInFix: Int) {

    companion object {

        /** Largest visible count any constellation set reports; a sanity ceiling. */
        const val MAX_REPORTED = 32

        /** A 3D fix needs four satellites; fewer cannot be a reported position. */
        const val MIN_USED_FOR_FIX = 4

        /** Clamps both counts, keeping used at or below in view. */
        fun of(inView: Int, usedInFix: Int): SatelliteStatus {
            val view = inView.coerceIn(0, MAX_REPORTED)
            return SatelliteStatus(view, usedInFix.coerceIn(0, view))
        }

        /**
         * Counts for a moment that is reporting a fix. A weak sky is lifted to
         * the minimum a solution needs rather than reported as a contradiction
         * between "position known" and "not enough satellites".
         */
        fun withFix(inView: Int, usedInFix: Int): SatelliteStatus {
            val view = inView.coerceIn(MIN_USED_FOR_FIX, MAX_REPORTED)
            return SatelliteStatus(view, usedInFix.coerceIn(MIN_USED_FOR_FIX, view))
        }
    }
}
