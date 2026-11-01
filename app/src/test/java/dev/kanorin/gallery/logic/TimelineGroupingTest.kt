package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TimelineGroupingTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    // Brief says "Thu 30 Oct 2026"; the real calendar day is a Friday (the mockup used 2025 weekdays).
    private val now = at(2026, 10, 30, 14, 32)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, zone: ZoneId = kl) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun label(ms: Long, by: GroupBy, now: Long = this.now, zone: ZoneId = kl) =
        TimelineGrouping.label(TimelineGrouping.groupKey(ms, by, zone), by, now, zone)

    @Test fun sameDayIsToday() = assertEquals("Today", label(at(2026, 10, 30, 0, 5), GroupBy.DAY))

    @Test fun previousDayIsYesterday() = assertEquals("Yesterday", label(at(2026, 10, 29, 23, 59), GroupBy.DAY))

    @Test fun olderDayThisYearShowsWeekdayAndDate() = assertEquals("Wed, 28 Oct", label(at(2026, 10, 28), GroupBy.DAY))

    @Test fun olderYearShowsTheYear() = assertEquals("Tue, 28 Oct 2025", label(at(2025, 10, 28), GroupBy.DAY))

    @Test fun septemberIsThreeLetters() = assertEquals("Mon, 21 Sep", label(at(2026, 9, 21), GroupBy.DAY))

    @Test fun monthLabel() = assertEquals("Oct 2026", label(at(2026, 10, 3), GroupBy.MONTH))

    @Test fun yearLabel() = assertEquals("2026", label(at(2026, 2, 3), GroupBy.YEAR))

    @Test fun yesterdayAcrossNewYear() {
        val newYear = at(2027, 1, 1, 9, 0)
        assertEquals("Yesterday", label(at(2026, 12, 31, 23, 0), GroupBy.DAY, now = newYear))
        assertEquals("Wed, 30 Dec 2026", label(at(2026, 12, 30), GroupBy.DAY, now = newYear))
    }

    @Test fun dayKeyIsLocalMidnight() =
        assertEquals(at(2026, 10, 28, 0, 0), TimelineGrouping.groupKey(at(2026, 10, 28, 23, 59), GroupBy.DAY, kl))

    @Test fun monthKeyIsTheFirstAtMidnight() =
        assertEquals(at(2026, 10, 1, 0, 0), TimelineGrouping.groupKey(at(2026, 10, 30, 23, 59), GroupBy.MONTH, kl))

    @Test fun yearKeyIsJanuaryFirstAtMidnight() =
        assertEquals(at(2026, 1, 1, 0, 0), TimelineGrouping.groupKey(at(2026, 10, 30), GroupBy.YEAR, kl))

    @Test fun noneKeyIsZero() = assertEquals(0L, TimelineGrouping.groupKey(at(2026, 10, 30), GroupBy.NONE, kl))

    @Test fun localMidnightStartsANewDay() {
        val midnight = at(2026, 10, 30, 0, 0)
        assertEquals(midnight, TimelineGrouping.groupKey(midnight, GroupBy.DAY, kl))
        assertEquals(at(2026, 10, 29, 0, 0), TimelineGrouping.groupKey(midnight - 1, GroupBy.DAY, kl))
    }

    @Test fun fixedZoneIsTheOffsetAtNow() {
        assertEquals(ZoneOffset.ofHours(8), TimelineGrouping.fixedZone(kl, now))
        val london = ZoneId.of("Europe/London")
        assertEquals(ZoneOffset.ofHours(1), TimelineGrouping.fixedZone(london, at(2026, 7, 1, zone = london)))
        assertEquals(ZoneOffset.UTC, TimelineGrouping.fixedZone(london, at(2026, 12, 1, zone = london)))
    }

    @Test fun fixedZoneKeysAndLabelsAgree() {
        // A summer photo seen in winter: grouped and labelled with one offset, so the header never shifts a day.
        val london = ZoneId.of("Europe/London")
        val winter = TimelineGrouping.fixedZone(london, at(2026, 12, 1, zone = london))
        val photo = at(2026, 7, 10, 0, 30, zone = london) // 9 Jul 23:30 UTC
        assertEquals("Thu, 9 Jul", label(photo, GroupBy.DAY, now = at(2026, 12, 1, zone = london), zone = winter))
    }
}
