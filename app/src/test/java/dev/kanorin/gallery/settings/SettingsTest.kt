package dev.kanorin.gallery.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsTest {
    private val settings = Settings(MemoryStore())

    @Test fun defaultsAreAsSpecified() = runTest {
        assertEquals(10, settings.skipSeconds.first())
        assertEquals(2f, settings.holdSpeed.first())
        assertNull(settings.rememberedDeleteChoice.first())
        assertEquals(4, settings.gridAll.first())
        assertEquals(3, settings.gridFolders.first())
        assertEquals(3, settings.gridInside.first())
        assertFalse(settings.showHidden.first())
        assertFalse(settings.manageMediaPromptDismissed.first())
    }

    @Test fun skipSecondsRoundTrips() = runTest {
        settings.setSkipSeconds(5)
        assertEquals(5, settings.skipSeconds.first())
    }

    @Test fun holdSpeedRoundTrips() = runTest {
        settings.setHoldSpeed(1.5f)
        assertEquals(1.5f, settings.holdSpeed.first())
        settings.setHoldSpeed(3f)
        assertEquals(3f, settings.holdSpeed.first())
    }

    @Test fun deleteChoiceRoundTripsAndClears() = runTest {
        settings.setRememberedDeleteChoice(DeleteChoice.PERMANENT)
        assertEquals(DeleteChoice.PERMANENT, settings.rememberedDeleteChoice.first())
        settings.setRememberedDeleteChoice(DeleteChoice.BIN)
        assertEquals(DeleteChoice.BIN, settings.rememberedDeleteChoice.first())
        settings.setRememberedDeleteChoice(null)
        assertNull(settings.rememberedDeleteChoice.first())
    }

    @Test fun gridAndHiddenRoundTrip() = runTest {
        settings.setGridAll(6)
        settings.setGridFolders(2)
        settings.setGridInside(5)
        settings.setShowHidden(true)
        assertEquals(6, settings.gridAll.first())
        assertEquals(2, settings.gridFolders.first())
        assertEquals(5, settings.gridInside.first())
        assertTrue(settings.showHidden.first())
    }

    @Test fun manageMediaPromptDismissedRoundTrips() = runTest {
        settings.setManageMediaPromptDismissed(true)
        assertTrue(settings.manageMediaPromptDismissed.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun skipSecondsRejectsOtherValues() = runTest { settings.setSkipSeconds(7) }

    @Test(expected = IllegalArgumentException::class)
    fun holdSpeedRejectsOtherValues() = runTest { settings.setHoldSpeed(4f) }

    @Test fun gridRangesAreAsSpecified() {
        assertEquals(2..6, Settings.GRID_ALL_RANGE)
        assertEquals(2..4, Settings.GRID_FOLDERS_RANGE)
        assertEquals(2..6, Settings.GRID_INSIDE_RANGE)
    }

    @Test fun gridSizesOutsideTheirRangeAreRefused() = runTest {
        for (bad in listOf<suspend () -> Unit>(
            { settings.setGridAll(7) }, { settings.setGridAll(1) },
            { settings.setGridFolders(5) }, { settings.setGridInside(7) },
        )) {
            try {
                bad()
                throw AssertionError("accepted an out-of-range grid size")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(4, settings.gridAll.first())
        assertEquals(3, settings.gridFolders.first())
        assertEquals(3, settings.gridInside.first())
    }
}
