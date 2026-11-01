package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollMathTest {
    @Test fun topIsZero() = assertEquals(0f, FastScrollMath.fraction(0, 30, 1000, atEnd = false), 0f)

    @Test fun midwayIsLinearInFlatIndex() = assertEquals(0.5f, FastScrollMath.fraction(485, 30, 1000, atEnd = false), 1e-6f)

    @Test fun theEndIsOneEvenWhenTheVisibleCountWobbles() {
        assertEquals(1f, FastScrollMath.fraction(970, 30, 1000, atEnd = true), 0f)
        assertEquals(1f, FastScrollMath.fraction(958, 36, 1000, atEnd = true), 0f)
    }

    @Test fun contentThatFitsStaysAtTheTop() = assertEquals(0f, FastScrollMath.fraction(0, 40, 30, atEnd = true), 0f)

    @Test fun emptyIsZero() = assertEquals(0f, FastScrollMath.fraction(0, 0, 0, atEnd = true), 0f)

    @Test fun targetMapsTheHandleBackToAFlatIndex() {
        assertEquals(0, FastScrollMath.target(0f, 30, 1000))
        assertEquals(485, FastScrollMath.target(0.5f, 30, 1000))
    }

    @Test fun targetAtTheBottomIsTheLastEntry() = assertEquals(999, FastScrollMath.target(1f, 30, 1000))

    @Test fun targetClampsOutOfRangeFractions() {
        assertEquals(0, FastScrollMath.target(-0.2f, 30, 1000))
        assertEquals(999, FastScrollMath.target(1.4f, 30, 1000))
    }

    @Test fun releasingTheHandleLeavesItWhereItWas() {
        for (first in listOf(0, 1, 77, 400, 969)) {
            val f = FastScrollMath.fraction(first, 30, 1000, atEnd = false)
            assertEquals(first, FastScrollMath.target(f, 30, 1000))
        }
    }
}
