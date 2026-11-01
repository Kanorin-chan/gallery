package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class GalleryToastTest {
    @get:Rule val rule = createComposeRule()

    private val toasts = ToastHostState()

    @Before fun setUp() {
        rule.setContent { GalleryTheme { ToastHost(toasts) { Box {} } } }
    }

    // With the clock paused, a write from outside needs frames plus idle syncs before it recomposes.
    private fun settle() = repeat(3) {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test fun aToastShowsForThreeAndAHalfSeconds() {
        rule.mainClock.autoAdvance = false
        toasts.show("Folder “Trip” created", "ADD PHOTOS")
        settle()
        rule.onNodeWithText("Folder “Trip” created").assertIsDisplayed()
        rule.onNodeWithText("ADD PHOTOS").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(3_300)
        rule.onNodeWithTag(TOAST_TAG).assertExists()
        rule.mainClock.advanceTimeBy(300)
        settle()
        rule.onNodeWithTag(TOAST_TAG).assertDoesNotExist()
    }

    @Test fun theActionRunsOnceAndDismissesTheToast() {
        var taps = 0
        toasts.show("Folder “Trip” created", "ADD PHOTOS") { taps++ }
        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()
        assertEquals(1, taps)
        rule.onNodeWithTag(TOAST_TAG).assertDoesNotExist()
    }

    @Test fun aNewToastReplacesTheOldAndRestartsTheClock() {
        rule.mainClock.autoAdvance = false
        toasts.show("first")
        settle()
        rule.mainClock.advanceTimeBy(3_000)
        toasts.show("second")
        settle()
        rule.onNodeWithText("first").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText("second").assertExists()
    }

    @Test fun theProgressLineShowsUntilClearedThenTheResult() {
        rule.mainClock.autoAdvance = false
        toasts.setProgress("Moving 120 of 3,400…")
        settle()
        rule.onNodeWithText("Moving 120 of 3,400…").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithText("Moving 120 of 3,400…").assertExists()
        toasts.setProgress(null)
        toasts.show("Moved 3,400 items")
        settle()
        rule.onNodeWithText("Moving 120 of 3,400…").assertDoesNotExist()
        rule.onNodeWithText("Moved 3,400 items").assertIsDisplayed()
    }

    @Test fun showIsSafeFromABackgroundThread() {
        val t = Thread { toasts.show("from IO") }
        t.start()
        t.join()
        rule.waitForIdle()
        rule.onNodeWithText("from IO").assertExists()
        assertTrue(toasts.toast.value != null)
    }

    // Ruling AD: a held "Delete permanently" finishes when its toast leaves without its UNDO.
    @Test fun onGoneRunsWhenTheToastTimesOutOrIsReplaced() {
        rule.mainClock.autoAdvance = false
        var gone = 0
        toasts.show("a", "UNDO", onAction = {}, onGone = { gone++ })
        settle()
        rule.mainClock.advanceTimeBy(TOAST_MS + 100)
        settle()
        assertEquals("timed out", 1, gone)
        toasts.show("b", "UNDO", onAction = {}, onGone = { gone++ })
        toasts.show("c")
        assertEquals("replaced", 2, gone)
        settle()
        rule.mainClock.advanceTimeBy(TOAST_MS + 100)
        settle()
        assertEquals("once only", 2, gone)
    }

    @Test fun theActionIsNotAnExpiry() {
        var gone = 0
        var acted = 0
        toasts.show("d", "UNDO", onAction = { acted++ }, onGone = { gone++ })
        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()
        rule.mainClock.advanceTimeBy(TOAST_MS + 100)
        assertEquals(1, acted)
        assertEquals(0, gone)
        // An action that arrives after its toast has gone does nothing.
        var late = 0
        toasts.show("e", "UNDO", onAction = { late++ }, onGone = {})
        val e = toasts.toast.value!!
        toasts.show("f")
        toasts.act(e)
        assertEquals(0, late)
    }
}
