package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferTextTest {
    @Test fun progressCountsWithThousandsSeparators() {
        assertEquals("Moving 120 of 3,400…", TransferText.progress(Transfer.MOVE, 120, 3_400))
        assertEquals("Copying 1 of 2…", TransferText.progress(Transfer.COPY, 1, 2))
    }

    @Test fun resultsSayHowManyWentAndHowManyDidNot() {
        assertEquals("Moved 3 items", TransferText.result(Transfer.MOVE, 3, 0))
        assertEquals("Moved 1 item", TransferText.result(Transfer.MOVE, 1, 0))
        assertEquals("Copied 1,200 items", TransferText.result(Transfer.COPY, 1_200, 0))
        assertEquals("Moved 2 items · 1 couldn't be moved", TransferText.result(Transfer.MOVE, 2, 1))
        assertEquals("3 couldn't be copied", TransferText.result(Transfer.COPY, 0, 3))
    }

    @Test fun anIndexThatLagsIsSaid() {
        assertEquals("Moved 5 items · some may take a moment to show", TransferText.result(Transfer.MOVE, 5, 0, lagging = 2))
        assertEquals("Moved 4 items · 1 couldn't be moved · some may take a moment to show", TransferText.result(Transfer.MOVE, 4, 1, lagging = 4))
        assertEquals("1 couldn't be copied", TransferText.result(Transfer.COPY, 0, 1, lagging = 0))
    }
}
