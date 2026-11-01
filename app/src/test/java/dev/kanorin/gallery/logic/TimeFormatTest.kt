package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {
    @Test fun minutesAndSeconds() = assertEquals("03:41", TimeFormat.clock(221_000, false))

    @Test fun hoursForLongVideos() = assertEquals("1:04:13", TimeFormat.clock(3_853_000, true))

    @Test fun zeroHoursStillShownWhenAsked() = assertEquals("0:12:08", TimeFormat.clock(728_000, true))

    @Test fun zeroAndNegativeClampToZero() {
        assertEquals("00:00", TimeFormat.clock(0, false))
        assertEquals("00:00", TimeFormat.clock(-5_000, false))
    }

    @Test fun minutesKeepCountingWithoutHours() = assertEquals("64:13", TimeFormat.clock(3_853_000, false))

    @Test fun roundsToTheNearestSecond() {
        assertEquals("00:24", TimeFormat.clock(24_499, false))
        assertEquals("00:25", TimeFormat.clock(24_500, false))
    }

    @Test fun hoursAreDecidedAfterRounding() {
        assertFalse(TimeFormat.showsHours(3_599_499))
        assertTrue(TimeFormat.showsHours(3_599_500))
        assertTrue(TimeFormat.showsHours(3_599_999))
        assertEquals("59:59", TimeFormat.duration(3_599_499))
        assertEquals("1:00:00", TimeFormat.duration(3_599_500))
        assertEquals("1:00:00", TimeFormat.duration(3_599_999))
        assertEquals("03:41", TimeFormat.duration(221_000))
        assertEquals("1:04:13", TimeFormat.duration(3_853_000))
    }

    // The player: both times take their hours from the whole duration, so they never disagree.
    @Test fun aPositionTakesItsHoursFromTheDuration() {
        assertEquals("0:03:41", TimeFormat.position(221_000, 3_853_000))
        assertEquals("1:04:13", TimeFormat.position(3_853_000, 3_853_000))
        assertEquals("03:41", TimeFormat.position(221_000, 728_000))
        assertEquals("0:30:00", TimeFormat.position(1_800_000, 3_599_500))
        assertEquals("59:59", TimeFormat.position(3_599_499, 3_599_499))
    }

    @Test fun aPositionStaysWithinTheDuration() {
        assertEquals("12:08", TimeFormat.position(800_000, 728_000))
        assertEquals("00:00", TimeFormat.position(-1, 728_000))
    }

    // Task 14 review: an unknown duration (0) mustn't pin the elapsed time at 00:00.
    @Test fun anUnknownDurationLetsThePositionCount() {
        assertEquals("00:05", TimeFormat.position(5_000, 0))
        assertEquals("01:05", TimeFormat.position(65_000, 0))
        assertEquals("00:00", TimeFormat.position(-1, 0))
        assertEquals("00:07", TimeFormat.position(7_000, -1))
    }
}
