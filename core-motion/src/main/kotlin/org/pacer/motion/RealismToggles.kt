// Which signal-realism behaviours are simulated.
//
// These exist as separate switches because each one is a visible property of the track
// rather than an internal detail, and a test may want one of them turned off: a fixed
// satellite count is easier to compare against a reference, a warm-up ramp makes the
// first thirty seconds of a short run look wrong, and dropout is the only one that can
// make a receiving app show a gap. All of them are ON by default, because together
// they are what makes a track look like a person's rather than a perfect line.
package org.pacer.motion

data class RealismToggles(
    /** Keep the last heading while standing still instead of reporting 0 (north). */
    val bearingHold: Boolean = true,
    /** Slow random walk on the reported altitude. */
    val altitudeDrift: Boolean = true,
    /** Report a wide accuracy that converges over the first half minute. */
    val accuracyWarmup: Boolean = true,
    /** Vary the satellite count inside a plausible band. */
    val satelliteJitter: Boolean = true,
    /** Occasional signal loss for a few seconds. */
    val signalDropout: Boolean = true,
)
