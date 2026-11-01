package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinPolicyTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val deletedAt = 1_790_000_000_000L

    @Test fun twentyNineDaysTwentyThreeHoursIsNotExpired() {
        assertFalse(BinPolicy.isExpired(deletedAt, deletedAt + 29 * day + 23 * hour))
    }

    @Test fun thirtyDaysAndOneMsIsExpired() {
        assertTrue(BinPolicy.isExpired(deletedAt, deletedAt + 30 * day + 1))
    }

    @Test fun exactlyThirtyDaysIsTheLastMomentKept() {
        assertFalse(BinPolicy.isExpired(deletedAt, deletedAt + 30 * day))
    }

    @Test fun retentionIsThirtyDays() {
        assertEquals(30, BinPolicy.RETENTION_DAYS)
    }

    @Test fun daysLeftRoundsUpAndTheLastDayHasItsOwnLabel() {
        assertEquals("30 days left", BinPolicy.daysLeftLabel(deletedAt, deletedAt))
        assertEquals("30 days left", BinPolicy.daysLeftLabel(deletedAt, deletedAt + 1))
        assertEquals("29 days left", BinPolicy.daysLeftLabel(deletedAt, deletedAt + day))
        assertEquals("2 days left", BinPolicy.daysLeftLabel(deletedAt, deletedAt + 28 * day + 1))
        assertEquals("1 day left", BinPolicy.daysLeftLabel(deletedAt, deletedAt + 29 * day))
        assertEquals("Last day", BinPolicy.daysLeftLabel(deletedAt, deletedAt + 29 * day + 1))
        assertEquals("Last day", BinPolicy.daysLeftLabel(deletedAt, deletedAt + 30 * day))
        // Expired but not purged yet (its card was out): still the last day, never a negative count.
        assertEquals("Last day", BinPolicy.daysLeftLabel(deletedAt, deletedAt + 40 * day))
    }

    @Test fun aResumePurgesOnceADay() {
        val now = deletedAt
        assertTrue("never purged", BinPolicy.purgeDue(null, now))
        assertFalse(BinPolicy.purgeDue(now - 23 * hour, now))
        assertTrue(BinPolicy.purgeDue(now - 24 * hour, now))
        // The clock went back: the stored time is in the future, so it can't be trusted.
        assertTrue(BinPolicy.purgeDue(now + hour, now))
    }
}
