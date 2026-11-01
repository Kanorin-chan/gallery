package dev.kanorin.gallery.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import dev.kanorin.gallery.BuildConfig
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.folder.INSIDE_COLUMN_RANGE
import dev.kanorin.gallery.ui.folders.FOLDER_COLUMN_RANGE
import dev.kanorin.gallery.ui.permission.PermissionState
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val settings = Settings(MemoryStore())
    private var backs = 0
    private var permissionTaps = 0

    private fun show() {
        rule.setContent {
            GalleryTheme { SettingsScreen(settings, onBack = { backs++ }, onPermissions = { permissionTaps++ }) }
        }
    }

    private fun <T> read(f: (Settings) -> Flow<T>): T = runBlocking { f(settings).first() }
    private fun tag(t: String) = rule.onNodeWithTag(t).apply { performScrollTo() }

    @Test fun aRememberedBinChoiceTurnsTheSwitchOffAndNamesIt() {
        runBlocking { settings.setRememberedDeleteChoice(DeleteChoice.BIN) }
        show()
        tag(DELETE_ASKS_TAG).assertIsOff().assertIsEnabled()
        rule.onNodeWithText("Always moves to Recycle bin").assertIsDisplayed()
    }

    @Test fun aRememberedPermanentChoiceIsNamed() {
        runBlocking { settings.setRememberedDeleteChoice(DeleteChoice.PERMANENT) }
        show()
        rule.onNodeWithText("Always deletes permanently").assertExists()
    }

    @Test fun turningDeleteAsksEveryTimeOnForgetsTheChoice() {
        runBlocking { settings.setRememberedDeleteChoice(DeleteChoice.BIN) }
        show()
        tag(DELETE_ASKS_TAG).performClick()
        rule.waitForIdle()
        assertNull(read { it.rememberedDeleteChoice })
        tag(DELETE_ASKS_TAG).assertIsOn().assertIsNotEnabled()
    }

    @Test fun withNoChoiceTheSwitchIsOnAndLocked() {
        show()
        tag(DELETE_ASKS_TAG).assertIsOn().assertIsNotEnabled()
    }

    @Test fun pickingFiveSecondsSavesIt() {
        show()
        tag(skipTag(10)).assertIsSelected()
        tag(skipTag(5)).performClick()
        rule.waitForIdle()
        assertEquals(5, read { it.skipSeconds })
        tag(skipTag(5)).assertIsSelected()
    }

    @Test fun pickingAHoldSpeedSavesIt() {
        show()
        tag(holdTag(2f)).assertIsSelected()
        listOf(1.5f, 3f, 2f).forEach {
            tag(holdTag(it)).performClick()
            rule.waitForIdle()
            assertEquals(it, read { s -> s.holdSpeed })
        }
        rule.onNodeWithText("1.5×").assertExists()
        rule.onNodeWithText("3×").assertExists()
    }

    @Test fun steppersMoveByOneAndStopAtTheSharedRanges() {
        show()
        assertEquals(Settings.DEFAULT_GRID_ALL, read { it.gridAll })
        tag(plusTag(GRID_ALL_TAG)).performClick(); rule.waitForIdle()
        assertEquals(Settings.DEFAULT_GRID_ALL + 1, read { it.gridAll })
        tag(minusTag(GRID_FOLDERS_TAG)).performClick(); rule.waitForIdle()
        assertEquals(Settings.DEFAULT_GRID_FOLDERS - 1, read { it.gridFolders })
        tag(plusTag(GRID_INSIDE_TAG)).performClick(); rule.waitForIdle()
        assertEquals(Settings.DEFAULT_GRID_INSIDE + 1, read { it.gridInside })
    }

    @Test fun steppersAreDisabledAtTheirEnds() {
        runBlocking {
            settings.setGridAll(Settings.GRID_ALL_RANGE.last)
            settings.setGridFolders(FOLDER_COLUMN_RANGE.first)
            settings.setGridInside(INSIDE_COLUMN_RANGE.last)
        }
        show()
        tag(plusTag(GRID_ALL_TAG)).assertIsNotEnabled()
        tag(minusTag(GRID_ALL_TAG)).assertIsEnabled()
        tag(minusTag(GRID_FOLDERS_TAG)).assertIsNotEnabled()
        tag(plusTag(GRID_INSIDE_TAG)).assertIsNotEnabled()
        tag(valueTag(GRID_ALL_TAG)).assertTextEquals(Settings.GRID_ALL_RANGE.last.toString())
    }

    @Test fun showHiddenFoldersFollowsTheSetting() {
        show()
        tag(SHOW_HIDDEN_TAG).assertIsOff().performClick()
        rule.waitForIdle()
        assertEquals(true, read { it.showHidden })
        tag(SHOW_HIDDEN_TAG).assertIsOn()
    }

    @Test fun permissionsRowSectionsAndFooter() {
        show()
        tag(PERMISSIONS_ROW_TAG).performClick()
        assertEquals(1, permissionTaps)
        rule.onNodeWithText("Gallery ${BuildConfig.VERSION_NAME}").performScrollTo().assertIsDisplayed()
        listOf("Video", "Delete", "Grid", "Folders", "Permissions").forEach { assertTrue(it, rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()) }
    }

    @Test fun backArrowGoesBack() {
        show()
        rule.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        assertEquals(1, backs)
    }

    // ---- permissions page ----

    private var state = PermissionState(allFiles = true, manageMedia = false)
    private var openedAll = 0
    private var openedMedia = 0

    private fun showPermissions() {
        rule.setContent {
            GalleryTheme {
                PermissionsSettingsScreen(
                    onBack = { backs++ }, readState = { state },
                    onOpenAllFiles = { openedAll++ }, onOpenManageMedia = { openedMedia++ },
                )
            }
        }
    }

    @Test fun permissionsPageShowsBothStatesAndButtons() {
        showPermissions()
        rule.onNodeWithText("All files access: Allowed").assertIsDisplayed()
        rule.onNodeWithText("Media management: Not allowed").assertIsDisplayed()
        rule.onNodeWithTag(ALL_FILES_BUTTON_TAG).performClick()
        rule.onNodeWithTag(MANAGE_MEDIA_BUTTON_TAG).performClick()
        assertEquals(1, openedAll)
        assertEquals(1, openedMedia)
    }

    @Test fun mediaManagementButtonShowsEvenAfterNotNow() {
        runBlocking { settings.setManageMediaPromptDismissed(true) }
        showPermissions()
        rule.onNodeWithTag(MANAGE_MEDIA_BUTTON_TAG).assertIsDisplayed()
    }

    @Test fun permissionsPageBackGoesBack() {
        showPermissions()
        rule.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        assertEquals(1, backs)
    }

    @Test fun permissionsPageRereadsOnResume() {
        showPermissions()
        rule.onNodeWithText("Media management: Not allowed").assertExists()
        state = PermissionState(allFiles = true, manageMedia = true)
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText("Media management: Allowed").assertExists()
    }
}
