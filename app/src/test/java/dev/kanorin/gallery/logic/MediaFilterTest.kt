package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilterTest {
    // Final review I3: an all-off kind filter could only ever show an empty grid.
    @Test fun theLastKindOnStaysOn() {
        val photosOnly = MediaFilter(videos = false, gifs = false)
        assertEquals(photosOnly, photosOnly.withChip(ShowChip.PHOTOS, false))
        assertTrue(photosOnly.isLastKindOn(ShowChip.PHOTOS))
        assertFalse(photosOnly.isLastKindOn(ShowChip.VIDEOS))
        val two = MediaFilter(gifs = false)
        assertEquals(MediaFilter(photos = false, gifs = false), two.withChip(ShowChip.PHOTOS, false))
        assertFalse(two.isLastKindOn(ShowChip.PHOTOS))
        assertFalse("narrowers are not kinds", MediaFilter().isLastKindOn(ShowChip.SCREENSHOTS))
    }

    @Test fun narrowedIsASearchOrAnyChipAwayFromShowingEverything() {
        assertFalse(MediaFilter().narrowed)
        assertFalse("sort and grouping show everything", MediaFilter(sort = SortField.NAME, groupBy = GroupBy.NONE).narrowed)
        assertTrue(MediaFilter(screenshotsOnly = true).narrowed)
        assertTrue(MediaFilter(favouritesOnly = true).narrowed)
        assertTrue(MediaFilter(videos = false).narrowed)
        assertTrue(MediaFilter(search = SearchQuery("beach", null, null)).narrowed)
    }
}
