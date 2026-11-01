package dev.kanorin.gallery.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec 5.5, mockup phone 5: the dialog alone. The remembered-choice skip is in DeleteFlowTest (it lives in the flow). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class DeleteDialogTest {
    @get:Rule val rule = createComposeRule()

    private val choices = mutableListOf<Pair<DeleteChoice, Boolean>>()
    private var cancelled = 0

    private fun show(photos: Int = 2, videos: Int = 1) = rule.setContent {
        GalleryTheme { DeleteDialog(photos, videos, onChoice = { c, r -> choices += c to r }, onCancel = { cancelled++ }) }
    }

    @Test fun theMockupsCopy() {
        show()
        rule.onNodeWithText("Delete 3 items?").assertIsDisplayed()
        rule.onNodeWithText("2 photos, 1 video").assertIsDisplayed()
        rule.onNodeWithText("Move to Recycle bin").assertIsDisplayed()
        rule.onNodeWithText("Restore any time within 30 days").assertIsDisplayed()
        rule.onNodeWithText("Delete permanently").assertIsDisplayed()
        rule.onNodeWithText("Gone from the phone. Can't be undone").assertIsDisplayed()
        rule.onNodeWithText("Remember my choice").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithTag(REMEMBER_TAG).assertIsOff()
    }

    @Test fun oneOfAKind() {
        show(photos = 1, videos = 0)
        rule.onNodeWithText("Delete 1 item?").assertIsDisplayed()
        rule.onNodeWithText("1 photo").assertIsDisplayed()
    }

    @Test fun binWithoutRemembering() {
        show()
        rule.onNodeWithText("Move to Recycle bin").performClick()
        assertEquals(listOf(DeleteChoice.BIN to false), choices)
        assertEquals(0, cancelled)
    }

    @Test fun permanentWithRememberTicked() {
        show()
        rule.onNodeWithText("Remember my choice").performClick()
        rule.onNodeWithTag(REMEMBER_TAG).assertIsOn()
        rule.onNodeWithText("Delete permanently").performClick()
        assertEquals(listOf(DeleteChoice.PERMANENT to true), choices)
    }

    @Test fun tickingTwiceUnticks() {
        show()
        rule.onNodeWithTag(REMEMBER_TAG).performClick()
        rule.onNodeWithTag(REMEMBER_TAG).performClick()
        rule.onNodeWithTag(REMEMBER_TAG).assertIsOff()
        rule.onNodeWithText("Move to Recycle bin").performClick()
        assertEquals(listOf(DeleteChoice.BIN to false), choices)
    }

    @Test fun cancelChoosesNothing() {
        show()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals(emptyList<Pair<DeleteChoice, Boolean>>(), choices)
    }
}
