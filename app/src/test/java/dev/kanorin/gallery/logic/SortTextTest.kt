package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortTextTest {
    @Test fun dateTakenDefaultReadsNewestFirst() {
        assertTrue(SortText.defaultDescending(SortField.DATE_TAKEN))
        assertEquals("newest first ↓", SortText.order(SortField.DATE_TAKEN, true))
        assertEquals("oldest first ↑", SortText.order(SortField.DATE_MODIFIED, false))
    }

    @Test fun nameStartsAtoZ() {
        assertFalse(SortText.defaultDescending(SortField.NAME))
        assertEquals("A to Z ↓", SortText.order(SortField.NAME, false))
        assertEquals("Z to A ↑", SortText.order(SortField.NAME, true))
    }

    @Test fun sizeStartsLargestFirst() {
        assertTrue(SortText.defaultDescending(SortField.SIZE))
        assertEquals("largest first ↓", SortText.order(SortField.SIZE, true))
        assertEquals("smallest first ↑", SortText.order(SortField.SIZE, false))
        assertTrue(SortText.defaultDescending(SortField.DURATION))
        assertEquals("longest first ↓", SortText.order(SortField.DURATION, true))
        assertEquals("shortest first ↑", SortText.order(SortField.DURATION, false))
        assertEquals("Length", SortText.title(SortField.DURATION))
    }
}
