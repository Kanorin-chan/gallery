package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SearchQueryTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, kl).toInstant().toEpochMilli()
    private val now = at(2026, 10, 30, 14)
    private fun parse(raw: String, at: Long = now) = SearchParser.parse(raw, at, kl)

    @Test fun yesterdayIsTheWholePreviousLocalDay() =
        assertEquals(SearchQuery(null, at(2026, 10, 29), at(2026, 10, 30)), parse("yesterday"))

    @Test fun todayIsTheWholeLocalDay() =
        assertEquals(SearchQuery(null, at(2026, 10, 30), at(2026, 10, 31)), parse("Today"))

    @Test fun monthAndYearIsTheWholeMonth() {
        assertEquals(SearchQuery(null, at(2026, 10, 1), at(2026, 11, 1)), parse("Oct 2026"))
        assertEquals(SearchQuery(null, at(2026, 10, 1), at(2026, 11, 1)), parse("october 2026"))
        assertEquals(SearchQuery(null, at(2026, 12, 1), at(2027, 1, 1)), parse("DEC 2026"))
    }

    @Test fun isoDates() {
        assertEquals(SearchQuery(null, at(2026, 10, 28), at(2026, 10, 29)), parse("2026-10-28"))
        assertEquals(SearchQuery(null, at(2026, 10, 1), at(2026, 11, 1)), parse("2026-10"))
        assertEquals(SearchQuery(null, at(2026, 1, 1), at(2027, 1, 1)), parse("2026"))
        assertEquals(SearchQuery(null, at(2026, 12, 31), at(2027, 1, 1)), parse("2026-12-31"))
    }

    @Test fun dayAndMonthUseThisYear() {
        assertEquals(SearchQuery(null, at(2026, 10, 28), at(2026, 10, 29)), parse("28 oct"))
        assertEquals(SearchQuery(null, at(2025, 3, 5), at(2025, 3, 6)), parse("5 March 2025"))
    }

    @Test fun aBareMonthIsTheMostRecentOneUpToNow() {
        assertEquals(SearchQuery(null, at(2026, 10, 1), at(2026, 11, 1)), parse("oct"))
        assertEquals(SearchQuery(null, at(2026, 3, 1), at(2026, 4, 1)), parse("mar"))
        assertEquals("december is still ahead this year", SearchQuery(null, at(2025, 12, 1), at(2026, 1, 1)), parse("dec"))
    }

    @Test fun textBesideADateGivesBoth() {
        assertEquals(SearchQuery("beach", at(2026, 10, 1), at(2026, 11, 1)), parse("beach oct"))
        assertEquals(SearchQuery("family trip", at(2026, 10, 29), at(2026, 10, 30)), parse("family yesterday trip"))
    }

    @Test fun anythingElseIsText() {
        assertEquals(SearchQuery("旅行", null, null), parse("旅行"))
        assertEquals(SearchQuery("IMG_2026 0001", null, null), parse("  IMG_2026   0001 "))
        assertEquals("impossible dates stay text", SearchQuery("2026-13-01", null, null), parse("2026-13-01"))
        assertEquals(SearchQuery("31 feb", null, null), parse("31 feb"))
        assertEquals(SearchQuery("octopus", null, null), parse("octopus"))
    }

    @Test fun blankIsEmpty() {
        assertTrue(parse("").isEmpty)
        assertTrue(parse("   ").isEmpty)
        assertNull(parse("x").from)
    }

    @Test fun likePatternEscapesWildcards() {
        assertEquals("%50\\%\\_a\\\\b%", SearchQuery.likePattern("50%_a\\b"))
    }
}
