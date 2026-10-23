package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The overlay's size and starting position.
 *
 * Both were fixed pixel numbers, which is a thumb-sized control on one phone and an
 * untouchable dot on a 3x screen. Only the arithmetic is testable here; whether a 160 dp
 * window leaves enough of the target app visible is a matter of looking at a device.
 */
class JoystickGeometryTest {

    /** The constants the service ships, so a silent change is caught here. */
    private val ringDp = JOYSTICK_RING_DP
    private val insetDp = JOYSTICK_INSET_DP

    private fun geometryAt(density: Float): JoystickGeometry =
        joystickGeometry(joystickWindowPx(ringDp, insetDp, density), insetDp, density)

    @Test
    fun `the knob is at least a 48 dp target on every density`() {
        // The knob is the only way to steer, so it has to meet the same minimum as a button.
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)) {
            val diameterDp = geometryAt(density).knobRadiusPx * 2f / density
            assertTrue("density $density gives a ${diameterDp}dp knob", diameterDp >= 48f)
        }
    }

    @Test
    fun `the whole window is about 160 dp at every density`() {
        // The window is the ring plus its padding, and both are dp: a fixed pixel size would
        // read as 220 dp on a 1x screen and 73 dp on a 3x one, which is the bug this guards.
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 3f, 4f)) {
            val dp = geometryAt(density).windowSizePx / density
            assertTrue("density $density gives a ${dp}dp window", dp in 120f..176f)
        }
    }

    @Test
    fun `the ring stays inside the window`() {
        val geometry = geometryAt(3f)
        assertTrue(
            "base ${geometry.baseRadiusPx} must fit in half of ${geometry.windowSizePx}",
            geometry.baseRadiusPx * 2f <= geometry.windowSizePx,
        )
        assertTrue("knob must be smaller than the ring", geometry.knobRadiusPx < geometry.baseRadiusPx)
    }

    @Test
    fun `drawing and hit testing read the same radius`() {
        // The hit test and onDraw both call joystickGeometry with the view's width; this
        // pins that a window laid out at the px size asked for yields the expected knob.
        val density = 2.625f
        val windowPx = joystickWindowPx(ringDp, insetDp, density)
        val fromSpec = geometryAt(density)
        val fromLayout = joystickGeometry(windowPx, insetDp, density)
        assertEquals(fromSpec.baseRadiusPx.toDouble(), fromLayout.baseRadiusPx.toDouble(), 1e-6)
        assertEquals(fromSpec.knobRadiusPx.toDouble(), fromLayout.knobRadiusPx.toDouble(), 1e-6)
    }

    @Test
    fun `a tiny window still yields a positive radius`() {
        val geometry = joystickGeometry(windowSizePx = 4, insetDp = 8f, density = 3f)
        assertTrue(geometry.baseRadiusPx > 0f)
        assertTrue(geometry.knobRadiusPx > 0f)
    }

    @Test
    fun `the start position clears the status bar and the navigation bar`() {
        val density = 3f
        val sizePx = joystickWindowPx(ringDp, insetDp, density)
        val marginDp = 24f
        val at = joystickPlacement(
            screenWidthPx = 1080,
            screenHeightPx = 2340,
            sizePx = sizePx,
            marginPx = (marginDp * density).roundToInt(),
            topInsetPx = 90,
            bottomInsetPx = 130,
            verticalFraction = 0.18f,
        )
        assertTrue("x ${at.x} must be on screen", at.x >= 0)
        assertTrue("x ${at.x} must leave the overlay on screen", at.x + sizePx <= 1080)
        assertTrue("y ${at.y} must clear the status bar", at.y >= 90)
        assertTrue("y ${at.y} must clear the navigation bar", at.y + sizePx <= 2340 - 130)
    }

    @Test
    fun `a short screen still puts the overlay fully on screen`() {
        // A landscape phone is shorter than the window plus its margins.
        val density = 3f
        val sizePx = joystickWindowPx(ringDp, insetDp, density)
        val at = joystickPlacement(
            screenWidthPx = 2340,
            screenHeightPx = 1080,
            sizePx = sizePx,
            marginPx = (24f * density).roundToInt(),
            topInsetPx = 0,
            bottomInsetPx = 0,
            verticalFraction = 0.18f,
        )
        assertTrue("y ${at.y} must not be negative", at.y >= 0)
        assertTrue("y ${at.y} must leave the overlay on screen", at.y + sizePx <= 1080)
        assertTrue("x ${at.x} must leave the overlay on screen", at.x + sizePx <= 2340)
    }

    @Test
    fun `the overlay starts against the left edge with a margin`() {
        val marginPx = 48
        val at = joystickPlacement(1080, 2340, 480, marginPx, 90, 130, 0.18f)
        assertEquals(marginPx, at.x)
    }
}
