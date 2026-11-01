package dev.kanorin.gallery.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomPillTest {
    @get:Rule val rule = createComposeRule()

    private var current by mutableIntStateOf(4)

    @Test fun flashModeShowsOnEachChangeAndHides1200MsAfterTheLast() {
        rule.setContent { GalleryTheme { ZoomPill(2, 6, current, visible = true, onPick = null) } }
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertDoesNotExist()
        rule.mainClock.autoAdvance = false

        change(3)
        rule.onNodeWithTag(ZOOM_PILL_TAG).assert(hasText("Grid", substring = true))
        rule.mainClock.advanceTimeBy(600)
        change(2)
        rule.mainClock.advanceTimeBy(900)
        // Past 1.2 s + fade from the first change, under 1.2 s from the last.
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertExists()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertDoesNotExist()
    }

    // With the clock paused, a write from the test needs frames plus idle syncs before it recomposes.
    private fun change(to: Int) {
        rule.runOnUiThread { current = to }
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
    }

    @Test fun pickModeIsAlwaysShownAndNumbersAreTappable() {
        val picked = mutableListOf<Int>()
        rule.setContent { GalleryTheme { ZoomPill(2, 6, 3, visible = true, onPick = { picked += it }) } }
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertExists()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertExists()
        rule.onNodeWithText("5").performClick()
        assertEquals(listOf(5), picked)
    }

    @Test fun notVisibleHidesEvenInPickMode() {
        rule.setContent { GalleryTheme { ZoomPill(2, 6, 3, visible = false, onPick = {}) } }
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertDoesNotExist()
    }
}
