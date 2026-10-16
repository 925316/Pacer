package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnModelTest {

    @Test
    fun `zero heading change is zero degrees`() {
        assertEquals(0.0, TurnModel.headingChangeDegrees(45.0, 45.0), 1e-9)
    }

    @Test
    fun `wrap around uses the shortest angle`() {
        assertEquals(20.0, TurnModel.headingChangeDegrees(350.0, 10.0), 1e-9)
    }

    @Test
    fun `opposite headings give 180 degrees`() {
        assertEquals(180.0, TurnModel.headingChangeDegrees(90.0, 270.0), 1e-9)
    }

    @Test
    fun `right angle is 90 degrees`() {
        assertEquals(90.0, TurnModel.headingChangeDegrees(0.0, 90.0), 1e-9)
    }

    @Test
    fun `heading change is symmetric`() {
        assertEquals(
            TurnModel.headingChangeDegrees(10.0, 350.0),
            TurnModel.headingChangeDegrees(350.0, 10.0),
            1e-9,
        )
    }

    @Test
    fun `unnormalised bearings are accepted`() {
        assertEquals(90.0, TurnModel.headingChangeDegrees(-90.0, 720.0), 1e-9)
    }

    @Test
    fun `straight route keeps full speed`() {
        assertEquals(1.0, TurnModel.brakingFactor(0.0), 1e-9)
    }

    @Test
    fun `small turn below threshold does not brake`() {
        assertEquals(1.0, TurnModel.brakingFactor(10.0), 1e-9)
    }

    @Test
    fun `sharp and u-turn turns reach the floor`() {
        assertEquals(0.45, TurnModel.brakingFactor(90.0), 1e-9)
        assertEquals(0.45, TurnModel.brakingFactor(180.0), 1e-9)
    }

    @Test
    fun `factor is monotonically non-increasing`() {
        var prev = 1.0
        var turn = 0.0
        while (turn <= 180.0) {
            val f = TurnModel.brakingFactor(turn)
            assertTrue("factor rose at $turn: $f > $prev", f <= prev + 1e-12)
            prev = f
            turn += 5.0
        }
    }

    @Test
    fun `midpoint sits halfway between one and the floor`() {
        assertEquals(0.725, TurnModel.brakingFactor(55.0), 1e-9)
    }

    @Test
    fun `custom floor is respected`() {
        val thresholds = TurnThresholds(smallTurnDeg = 0.0, largeTurnDeg = 180.0, minFactor = 0.6)
        assertEquals(1.0, TurnModel.brakingFactor(0.0, thresholds), 1e-9)
        assertEquals(0.8, TurnModel.brakingFactor(90.0, thresholds), 1e-9)
        assertEquals(0.6, TurnModel.brakingFactor(180.0, thresholds), 1e-9)
    }

    @Test
    fun `custom thresholds shift the knee`() {
        val thresholds = TurnThresholds(smallTurnDeg = 45.0, largeTurnDeg = 90.0, minFactor = 0.5)
        assertEquals(1.0, TurnModel.brakingFactor(45.0, thresholds), 1e-9)
        assertEquals(0.75, TurnModel.brakingFactor(67.5, thresholds), 1e-9)
        assertEquals(0.5, TurnModel.brakingFactor(90.0, thresholds), 1e-9)
    }

    @Test
    fun `out of range turns are clamped`() {
        assertEquals(1.0, TurnModel.brakingFactor(-30.0), 1e-9)
        assertEquals(0.45, TurnModel.brakingFactor(1000.0), 1e-9)
    }

    @Test
    fun `out of range floor is clamped to one`() {
        val thresholds = TurnThresholds(smallTurnDeg = 10.0, largeTurnDeg = 50.0, minFactor = 2.0)
        assertEquals(1.0, TurnModel.brakingFactor(90.0, thresholds), 1e-9)
    }
}
