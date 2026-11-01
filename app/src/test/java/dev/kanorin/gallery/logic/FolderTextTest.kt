package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTextTest {
    @Test fun subtitleIsSingularForOne() {
        assertEquals("1 item · 4 KB", FolderText.subtitle(1, "4 KB"))
        assertEquals("0 items · 0 B", FolderText.subtitle(0, "0 B"))
        assertEquals("1,204 items · 3.2 GB", FolderText.subtitle(1_204, "3.2 GB"))
    }

    @Test fun theArrowIsDownForTheFieldsUsualOrder() {
        assertEquals("↓", FolderText.sortArrow(SortField.DATE_TAKEN, true))
        assertEquals("↑", FolderText.sortArrow(SortField.DATE_TAKEN, false))
        assertEquals("↓", FolderText.sortArrow(SortField.NAME, false))
        assertEquals("↑", FolderText.sortArrow(SortField.NAME, true))
        assertEquals("↓", FolderText.sortArrow(SortField.SIZE, true))
        assertEquals("Date modified", FolderText.sortLabel(SortField.DATE_MODIFIED))
    }

    @Test fun theArrowIsDescribedForAScreenReader() {
        assertEquals("Sort direction, newest first", FolderText.sortDirection(SortField.DATE_TAKEN, true))
        assertEquals("Sort direction, oldest first", FolderText.sortDirection(SortField.DATE_MODIFIED, false))
        assertEquals("Sort direction, A to Z", FolderText.sortDirection(SortField.NAME, false))
        assertEquals("Sort direction, Z to A", FolderText.sortDirection(SortField.NAME, true))
        assertEquals("Sort direction, largest first", FolderText.sortDirection(SortField.SIZE, true))
        assertEquals("Sort direction, smallest first", FolderText.sortDirection(SortField.SIZE, false))
    }

    // Final review M11: one default for the folder screen and a VIEW intent's folder in the viewer.
    @Test fun aFolderWithNoSavedSortIsDateTakenNewestFirst() {
        assertEquals(SortField.DATE_TAKEN to true, FolderText.folderSort(null, null))
        assertEquals(FolderText.DEFAULT_SORT to true, FolderText.folderSort("GONE_FIELD", null))
        assertEquals(SortField.NAME to false, FolderText.folderSort("NAME", null))
        assertEquals(SortField.SIZE to false, FolderText.folderSort("SIZE", false))
    }
}
