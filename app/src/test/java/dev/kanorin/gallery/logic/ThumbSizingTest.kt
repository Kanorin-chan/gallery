package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbSizingTest {
    @Test fun smallerThanTargetIsNotSampled() = assertEquals(1, sampleSizeFor(200, 100, 256))

    @Test fun exactlyTargetIsNotSampled() = assertEquals(1, sampleSizeFor(256, 256, 256))

    @Test fun picksLargestPowerOfTwoKeepingBothSidesAtTarget() {
        // 4000x3000 at 256: 3000/8 = 375 >= 256, 3000/16 = 187 < 256
        assertEquals(8, sampleSizeFor(4000, 3000, 256))
    }

    @Test fun theShortSideDecides() = assertEquals(2, sampleSizeFor(10_000, 600, 256))

    @Test fun bothSidesStayAtOrAboveTarget() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 12_000 to 300, 513 to 513, 1024 to 1024)) {
            val s = sampleSizeFor(w, h, 256)
            assertTrue("$w x $h s=$s", w / s >= 256 || s == 1)
            assertTrue("$w x $h s=$s", h / s >= 256 || s == 1)
        }
    }

    @Test fun twoHundredMegapixelsDecodesSmall() {
        val w = 16_320
        val h = 12_240
        val s = sampleSizeFor(w, h, 256)
        val longEdge = maxOf(w, h) / s
        assertTrue("long edge $longEdge", longEdge <= 1024)
        assertTrue("long edge $longEdge", longEdge >= 256)
        assertTrue(minOf(w, h) / s >= 256)
    }

    @Test fun mediaStoreBoxIsFourThirdsRoundedUp() {
        assertEquals(342, thumbBoxPx(256)) // 341.33
        assertEquals(355, thumbBoxPx(266)) // 354.67
        assertEquals(400, thumbBoxPx(300)) // exact
        assertEquals(2, thumbBoxPx(1))
    }

    @Test fun aFourByThreePhotoFittedInTheBoxStillCoversTheTile() {
        for (s in 1..1024) {
            val box = thumbBoxPx(s)
            assertTrue("s=$s box=$box", reachesTarget(box, box * 3 / 4, s))
        }
    }

    @Test fun theShortSideJudgesTheTarget() {
        assertFalse(reachesTarget(256, 192, 256)) // the old fit-in-256 result: soft when crop-filled
        assertTrue(reachesTarget(342, 256, 256))
        assertTrue(reachesTarget(256, 342, 256))
        assertFalse(reachesTarget(1000, 255, 256))
    }

    @Test fun badInputsFallBackToOne() {
        assertEquals(1, sampleSizeFor(0, 0, 256))
        assertEquals(1, sampleSizeFor(-5, 100, 256))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
    }

    // The player's scrub preview: the frame must cover its bubble once crop-filled.
    @Test fun aCoverSizeFillsTheBoxKeepingTheAspect() {
        assertEquals(637 to 358, coverSize(1920, 1080, 264, 358))
        assertEquals(264 to 470, coverSize(1080, 1920, 264, 358))
        assertEquals(320 to 180, coverSize(1920, 1080, 320, 180))
    }

    @Test fun aCoverSizeNeverExceedsTheSource() = assertEquals(160 to 90, coverSize(160, 90, 264, 358))

    @Test fun anUnknownSizeHasNoCoverSize() {
        assertEquals(null, coverSize(0, 1080, 264, 358))
        assertEquals(null, coverSize(1920, 1080, 0, 358))
    }
}
