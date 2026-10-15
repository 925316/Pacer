// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

/**
 * Motion phase of a single simulated run.
 *
 * IDLE -> ACQUIRE (ramp 0 to cruise) -> CRUISE (noisy steady) ->
 * DECEL (slow down) -> PAUSE (standstill) -> ACQUIRE ...
 */
enum class MotionPhase {
    IDLE,
    ACQUIRE,
    CRUISE,
    DECEL,
    PAUSE,
}
