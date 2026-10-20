package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionOwnerTest {

    @Test
    fun `no run owns anything`() {
        val owner = MotionOwner.of(active = false, onRoute = true, plan = "3.0@60")
        assertEquals(MotionOwner.NONE, owner)
        assertFalse(owner.acceptsHandSteering)
    }

    @Test
    fun `a route owns the run even when a plan is also configured`() {
        // The route engine builds its samples from the route and uses the plan only as
        // the speed curve inside that build, so the route is what the run follows.
        val owner = MotionOwner.of(active = true, onRoute = true, plan = "3.0@60")
        assertEquals(MotionOwner.ROUTE, owner)
        assertFalse(owner.acceptsHandSteering)
        assertTrue(owner.isAutomatic)
    }

    @Test
    fun `a straight run with a plan is automatic`() {
        // Every position formula gives the plan precedence over the flat speed, so
        // hand steering would change nothing except the anchor.
        val owner = MotionOwner.of(active = true, onRoute = false, plan = "3.0@60;1.4@30")
        assertEquals(MotionOwner.PLAN, owner)
        assertFalse(owner.acceptsHandSteering)
        assertTrue(owner.isAutomatic)
    }

    @Test
    fun `a straight run without a plan is steered by hand`() {
        val owner = MotionOwner.of(active = true, onRoute = false, plan = "")
        assertEquals(MotionOwner.HAND, owner)
        assertTrue(owner.acceptsHandSteering)
        assertFalse(owner.isAutomatic)
    }

    @Test
    fun `a blank plan counts as no plan`() {
        // The app writes "" for "no plan"; a whitespace-only field must not silently
        // switch the run to automatic either.
        assertEquals(MotionOwner.HAND, MotionOwner.of(active = true, onRoute = false, plan = "   "))
    }

    @Test
    fun `a plan the engine cannot parse does not claim ownership`() {
        // The hook side falls back to the flat speed for unparseable text, so the app
        // must agree: otherwise the knob is locked for a speed nobody is using.
        assertEquals(MotionOwner.HAND, MotionOwner.of(active = true, onRoute = false, plan = "fast"))
    }
}
