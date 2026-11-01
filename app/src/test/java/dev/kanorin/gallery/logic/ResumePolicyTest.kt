package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {
    private val minute = 60_000L

    @Test fun aShortVideoAlwaysStartsAtTheBeginning() = assertEquals(0L, ResumePolicy.startPosition(9 * minute, 4 * minute))

    @Test fun aLongVideoResumesWhereItWasLeft() = assertEquals(30 * minute, ResumePolicy.startPosition(61 * minute, 30 * minute))

    @Test fun theLastTenSecondsStartOver() = assertEquals(0L, ResumePolicy.startPosition(61 * minute, 60 * minute + 55_000))

    @Test fun nothingSavedStartsAtTheBeginning() = assertEquals(0L, ResumePolicy.startPosition(61 * minute, null))

    @Test fun exactlyTenMinutesIsNotLong() = assertEquals(0L, ResumePolicy.startPosition(10 * minute, 4 * minute))

    @Test fun justOverTenMinutesIsLong() = assertEquals(4 * minute, ResumePolicy.startPosition(10 * minute + 1, 4 * minute))

    @Test fun theFirstFiveSecondsStartOver() {
        assertEquals(0L, ResumePolicy.startPosition(61 * minute, 4_999))
        assertEquals(5_000L, ResumePolicy.startPosition(61 * minute, 5_000))
    }

    @Test fun theWindowEndsTenSecondsBeforeTheEnd() {
        val d = 61 * minute
        assertEquals(d - 10_000, ResumePolicy.startPosition(d, d - 10_000))
        assertEquals(0L, ResumePolicy.startPosition(d, d - 9_999))
    }

    @Test fun anUnknownDurationStartsAtTheBeginning() = assertEquals(0L, ResumePolicy.startPosition(0, 30 * minute))
}
