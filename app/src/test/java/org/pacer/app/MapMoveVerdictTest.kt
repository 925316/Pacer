package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pacer.motion.MotionOwner

/**
 * Which runs the map's move control may move.
 *
 * The rule used to live in the long-press callback, where the only way to check it was to
 * hold a map on a device. It decides whether the reported position changes, so it is worth
 * pinning on the JVM.
 */
class MapMoveVerdictTest {

    @Test
    fun `a hand-steered run can be moved`() {
        assertEquals(MoveVerdict.ALLOWED, moveVerdict(MotionOwner.HAND))
    }

    @Test
    fun `a plan run can be moved even though it cannot be steered`() {
        // Moving re-anchors the clock; the plan keeps owning the speed. Refusing here would
        // be reading "not hand-steerable" as "not movable".
        assertEquals(MoveVerdict.ALLOWED, moveVerdict(MotionOwner.PLAN))
    }

    @Test
    fun `a route owns its position, paused or not`() {
        assertEquals(MoveVerdict.ROUTE_OWNS_POSITION, moveVerdict(MotionOwner.ROUTE))
        assertEquals(MoveVerdict.ROUTE_OWNS_POSITION, moveVerdict(MotionOwner.ROUTE_PAUSED))
    }

    @Test
    fun `nothing to move when no run is stored`() {
        assertEquals(MoveVerdict.NO_RUN, moveVerdict(MotionOwner.NONE))
    }
}
