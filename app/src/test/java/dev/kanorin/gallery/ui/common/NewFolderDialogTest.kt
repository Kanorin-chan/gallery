package dev.kanorin.gallery.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.kanorin.gallery.ui.theme.GalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class NewFolderDialogTest {
    @get:Rule val rule = createComposeRule()

    private val created = mutableListOf<String>()
    private var dismissed = 0

    private fun show(siblings: Collection<String>? = listOf("Camera", "Trip")) = rule.setContent {
        GalleryTheme { NewFolderDialog(siblings, onConfirm = { created += it }, onDismiss = { dismissed++ }) }
    }

    @Test fun theMockupsCopy() {
        show()
        rule.onNodeWithText("New folder").assertIsDisplayed()
        rule.onNodeWithText("Created in Pictures/ on your phone").assertIsDisplayed()
        rule.onNodeWithText("Folder name").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsDisplayed()
    }

    @Test fun errorsShowInlineAndNothingIsCreated() {
        show()
        rule.onNodeWithText("Create").performClick()
        rule.onNodeWithText("Give it a name").assertIsDisplayed()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("a/b")
        rule.onNodeWithText("Give it a name").assertDoesNotExist()
        rule.onNodeWithText("Create").performClick()
        rule.onNodeWithText("Can't use \\ / : * ? \" < > |").assertIsDisplayed()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextReplacement("camera")
        rule.onNodeWithText("Create").performClick()
        rule.onNodeWithText("A folder with that name already exists").assertIsDisplayed()
        assertEquals(emptyList<String>(), created)
        assertEquals(0, dismissed)
    }

    @Test fun createSendsTheTrimmedNameAndCloses() {
        show()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("  旅行 2026 🌸 ")
        rule.onNodeWithText("Create").performClick()
        assertEquals(listOf("旅行 2026 🌸"), created)
        assertEquals(1, dismissed)
    }

    @Test fun theKeyboardsDoneCreatesToo() {
        show()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("Trip KL")
        rule.onNodeWithTag(NAME_FIELD_TAG).performImeAction()
        assertEquals(listOf("Trip KL"), created)
    }

    @Test fun cancelCloses() {
        show()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), created)
    }

    @Test fun createWaitsWhileTheFolderIsStillBeingListed() {
        show(siblings = null)
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("Trip")
        rule.onNodeWithText("Create").performClick()
        assertEquals(emptyList<String>(), created)
    }

    @Test fun renameStartsFromTheCurrentName() {
        rule.setContent {
            GalleryTheme {
                NewFolderDialog(
                    listOf("Camera"), onConfirm = { created += it }, onDismiss = { dismissed++ },
                    title = "Rename folder", subline = null, confirm = "Rename", initial = "Trip",
                )
            }
        }
        rule.onNodeWithText("Rename folder").assertIsDisplayed()
        rule.onNodeWithText("Created in Pictures/ on your phone").assertDoesNotExist()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput(" KL")
        rule.onNodeWithText("Rename").performClick()
        assertEquals(listOf("Trip KL"), created)
    }

    @Test fun namesStopAtSixtyCharacters() {
        show()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("x".repeat(60))
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("y")
        rule.onNodeWithText("Create").performClick()
        assertEquals(listOf("x".repeat(60)), created)
    }
}
