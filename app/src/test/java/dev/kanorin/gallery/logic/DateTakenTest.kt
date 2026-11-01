package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DateTakenTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val modified = 1_790_000_000_000L

    @Test fun mediaStoreValueWinsAndExifIsNotRead() {
        var exifReads = 0
        assertEquals(123L, DateTaken.resolve(123L, { exifReads++; 999L }, modified))
        assertEquals(0, exifReads)
    }

    @Test fun zeroFallsBackToExif() {
        assertEquals(999L, DateTaken.resolve(0L, { 999L }, modified))
    }

    @Test fun noExifFallsBackToModified() {
        assertEquals(modified, DateTaken.resolve(0L, { null }, modified))
        assertEquals(modified, DateTaken.resolve(-1L, { null }, modified))
        // A pre-1970 EXIF date is junk, not a real date.
        assertEquals(modified, DateTaken.resolve(0L, { -5L }, modified))
    }

    @Test fun exifIsWallClockInTheGivenZone() {
        val expected = LocalDateTime.of(2026, 9, 15, 8, 30, 0).atZone(kl).toInstant().toEpochMilli()
        assertEquals(expected, DateTaken.parseExif("2026:09:15 08:30:00", kl))
        assertEquals(expected, DateTaken.parseExif("2026-09-15 08:30:00", kl))
        assertEquals(expected, DateTaken.parseExif(" 2026:09:15 08:30:00\u0000", kl))
    }

    @Test fun junkExifIsNull() {
        assertNull(DateTaken.parseExif(null, kl))
        assertNull(DateTaken.parseExif("", kl))
        assertNull(DateTaken.parseExif("0000:00:00 00:00:00", kl))
        assertNull(DateTaken.parseExif("    :  :     :  :  ", kl))
        assertNull(DateTaken.parseExif("yesterday", kl))
    }
}
