package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeleteTextTest {
    @Test fun dialogTitleAndSubline() {
        assertEquals("Delete 3 items?", DeleteText.title(3))
        assertEquals("Delete 1 item?", DeleteText.title(1))
        assertEquals("2 photos, 1 video", DeleteText.subline(2, 1))
        assertEquals("1 photo", DeleteText.subline(1, 0))
        assertEquals("3 videos", DeleteText.subline(0, 3))
        assertEquals("1,200 photos, 2 videos", DeleteText.subline(1_200, 2))
        assertNull(DeleteText.subline(0, 0))
    }

    @Test fun binScreenConfirms() {
        assertEquals("Delete 2 items forever?", DeleteText.forever(2))
        assertEquals("Delete 1 item forever?", DeleteText.forever(1))
    }

    @Test fun progressLines() {
        assertEquals("Deleting 120 of 3,400…", DeleteText.progress(120, 3_400))
        assertEquals("Restoring 1 of 2…", DeleteText.restoring(1, 2))
    }

    @Test fun binnedResults() {
        assertEquals("3 moved to Recycle bin", DeleteText.binned(3, 0))
        assertEquals("2 moved to Recycle bin · 1 couldn't be deleted", DeleteText.binned(2, 1))
        assertEquals("3 couldn't be deleted", DeleteText.binned(0, 3))
    }

    @Test fun deletedResults() {
        assertEquals("3 deleted permanently", DeleteText.deleted(3, 0))
        assertEquals("1,000 deleted permanently · 2 couldn't be deleted", DeleteText.deleted(1_000, 2))
        assertEquals("1 couldn't be deleted", DeleteText.deleted(0, 1))
        assertEquals("3 deleted permanently · some may take a moment to show", DeleteText.deleted(3, 0, lagging = 1))
    }

    @Test fun restoredResults() {
        assertEquals("Restored 3 items", DeleteText.restored(3, 0))
        assertEquals("Restored 1 item", DeleteText.restored(1, 0))
        assertEquals("Restored 2 items · 1 couldn't be restored", DeleteText.restored(2, 1))
        assertEquals("2 couldn't be restored", DeleteText.restored(0, 2))
        assertEquals("The SD card isn't available", DeleteText.restored(0, 2, cardOut = true))
        assertEquals("Restored 1 item · 1 couldn't be restored", DeleteText.restored(1, 1, cardOut = true))
        assertEquals("2 couldn't be restored — they're in the Recycle bin", DeleteText.undoFailed(2))
    }
}
