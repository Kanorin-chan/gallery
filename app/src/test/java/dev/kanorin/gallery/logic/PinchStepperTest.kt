package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinchStepperTest {
    @Test fun spreadPastAQuarterDropsAColumn() {
        val s = PinchStepper(2, 6, 4)
        assertEquals(3, s.onScale(1.3f))
        assertEquals(3, s.columns)
    }

    @Test fun squeezeAtTheMaximumIsClamped() {
        val s = PinchStepper(2, 6, 6)
        assertNull(s.onScale(0.7f))
        assertEquals(6, s.columns)
    }

    @Test fun smallFactorsAccumulate() {
        val s = PinchStepper(2, 6, 4)
        assertNull(s.onScale(1.1f))
        assertNull(s.onScale(1.1f))
        assertEquals(3, s.onScale(1.05f))
    }

    @Test fun squeezeAddsAColumn() = assertEquals(5, PinchStepper(2, 6, 4).onScale(0.75f))

    @Test fun accumulatorRestartsAfterAStep() {
        val s = PinchStepper(2, 6, 4)
        assertEquals(3, s.onScale(1.3f))
        // 1.3 x 1.1 would pass 1.25 again if the first step's scale were still counted.
        assertNull(s.onScale(1.1f))
    }

    @Test fun accumulatorRestartsAfterAClampedStep() {
        val s = PinchStepper(2, 6, 6)
        assertNull(s.onScale(0.7f))
        assertNull(s.onScale(1.1f))
    }

    @Test fun resetForgetsAPartialGesture() {
        val s = PinchStepper(2, 6, 4)
        assertNull(s.onScale(1.2f))
        s.reset()
        assertNull(s.onScale(1.1f))
    }

    @Test fun oneLongSpreadStepsDownToTheMinimumOnly() {
        val s = PinchStepper(2, 4, 3)
        assertEquals(2, s.onScale(1.3f))
        assertNull(s.onScale(1.3f))
        assertEquals(2, s.columns)
    }
}
