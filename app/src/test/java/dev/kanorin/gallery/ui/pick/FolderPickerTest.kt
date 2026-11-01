package dev.kanorin.gallery.ui.pick

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.kanorin.gallery.data.FavouritesSummary
import dev.kanorin.gallery.data.FolderSummary
import dev.kanorin.gallery.logic.FolderOrdering
import dev.kanorin.gallery.ui.common.NAME_FIELD_TAG
import dev.kanorin.gallery.ui.folders.FoldersModel
import dev.kanorin.gallery.ui.folders.NEW_FOLDER_TAG
import dev.kanorin.gallery.ui.folders.folderCardTag
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
class FolderPickerTest {
    @get:Rule val rule = createComposeRule()

    private val root = "/storage/emulated/0"
    private val cam = "$root/DCIM/Camera"
    private val trip = "$root/Pictures/Trip"
    private val picked = mutableListOf<String>()
    private val created = mutableListOf<String>()
    private var backs = 0

    @Before fun setUp() {
        val folders = listOf(
            FolderSummary(cam, "Camera", 2, 2, "$cam/a.jpg", 900, false),
            FolderSummary(trip, "Trip", 1, 1, "$trip/b.jpg", 800, false),
        )
        val arranged = FolderOrdering.arrange(folders, emptyList(), emptyList(), root, favourites = FavouritesSummary(3, null))
        rule.setContent {
            GalleryTheme {
                FolderPickerScreen(
                    title = "Move to folder", model = FoldersModel(arranged, emptyMap()), columns = 3,
                    onColumns = {}, onPick = { picked += it }, newFolderSiblings = { setOf("Trip") },
                    onCreateFolder = { created += it }, onBack = { backs++ },
                )
            }
        }
    }

    @Test fun listsTheFoldersInTheFoldersViewsOrderWithoutFavourites() {
        rule.onNodeWithText("Move to folder").assertIsDisplayed()
        rule.onNodeWithTag(folderCardTag(cam)).assertIsDisplayed()
        rule.onNodeWithTag(folderCardTag(trip)).assertIsDisplayed()
        rule.onNodeWithTag(folderCardTag("favorites://")).assertDoesNotExist()
        rule.onNodeWithTag(NEW_FOLDER_TAG).assertIsDisplayed()
    }

    @Test fun aTapPicksTheFolderAndALongPressOpensNoMenu() {
        rule.onNodeWithTag(folderCardTag(trip)).performTouchInput { longClick() }
        rule.onNodeWithText("Hide").assertDoesNotExist()
        rule.onNodeWithTag(folderCardTag(trip)).performClick()
        assertTrue(trip in picked)
    }

    @Test fun newFolderAsksForANameAndHandsItOn() {
        rule.onNodeWithTag(NEW_FOLDER_TAG).performClick()
        rule.onNodeWithText("Created in Pictures/ on your phone").assertIsDisplayed()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("Holiday")
        rule.waitUntil(5_000) {
            if (created.isEmpty() && rule.onAllNodes(hasTestTag(NAME_FIELD_TAG)).fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithText("Create").performClick()
            }
            created.isNotEmpty()
        }
        assertEquals(listOf("Holiday"), created)
    }

    @Test fun backGoesBack() {
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
