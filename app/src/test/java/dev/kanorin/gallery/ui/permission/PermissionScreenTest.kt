package dev.kanorin.gallery.ui.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import dev.kanorin.gallery.settings.Settings as AppSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowStorageManager::class, ShadowMediaStore::class])
class PermissionScreenTest {
    @get:Rule val rule = createComposeRule()

    private val explanation =
        "Gallery needs access to all files so it can show every photo and video on your phone."
    private val mediaText = "Allow media management so deleting and moving doesn't ask every time."
    private val granted = PermissionState(allFiles = true, manageMedia = false)

    @Before fun resetShadows() {
        ShadowStorageManager.granted = false
        ShadowMediaStore.canManage = false
    }

    // Shadow statics outlive the class: a leaked grant would start the media index in later tests.
    @After fun clearGrant() {
        ShadowStorageManager.granted = false
    }

    private fun setGate(state: PermissionState, settings: AppSettings) = rule.setContent {
        GalleryTheme { PermissionGate(state, settings, {}, {}) { androidx.compose.material3.Text("CONTENT") } }
    }

    @Test fun permissionScreen_showsExplanationAndButton() {
        var clicks = 0
        rule.setContent {
            GalleryTheme {
                PermissionScreen(PermissionState(allFiles = false, manageMedia = false), { clicks++ }, {}, {})
            }
        }
        rule.onNodeWithText(explanation).assertIsDisplayed()
        rule.onNodeWithText("Allow all files access").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        rule.onNodeWithText(mediaText).assertDoesNotExist()
    }

    @Test fun mediaStep_hasAllowAndNotNowButtons() {
        var media = 0
        var skip = 0
        rule.setContent { GalleryTheme { PermissionScreen(granted, {}, { media++ }, { skip++ }) } }
        rule.onNodeWithText(mediaText).assertIsDisplayed()
        rule.onNodeWithText("Allow all files access").assertDoesNotExist()
        rule.onNodeWithText("Allow media management").performClick()
        rule.onNodeWithText("Not now").performClick()
        assertEquals(1, media)
        assertEquals(1, skip)
    }

    @Test fun gate_showsMediaStepWhenAllFilesGrantedAndNotManaged() {
        setGate(granted, AppSettings(MemoryStore()))
        rule.onNodeWithText(mediaText).assertIsDisplayed()
        rule.onNodeWithText("CONTENT").assertDoesNotExist()
    }

    @Test fun gate_notNowPersistsAndShowsContent() {
        val settings = AppSettings(MemoryStore())
        setGate(granted, settings)
        rule.onNodeWithText("Not now").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("CONTENT").assertIsDisplayed()
        assertTrue(runBlocking { settings.manageMediaPromptDismissed.first() })
    }

    @Test fun gate_showsContentDirectlyWhenAlreadyDismissed() {
        val settings = AppSettings(MemoryStore())
        runBlocking { settings.setManageMediaPromptDismissed(true) }
        setGate(granted, settings)
        rule.waitForIdle()
        rule.onNodeWithText("CONTENT").assertIsDisplayed()
        rule.onNodeWithText(mediaText).assertDoesNotExist()
    }

    @Test fun gate_showsContentWhenManaged() {
        setGate(PermissionState(allFiles = true, manageMedia = true), AppSettings(MemoryStore()))
        rule.waitForIdle()
        rule.onNodeWithText("CONTENT").assertIsDisplayed()
    }

    @Test fun gate_withoutAllFilesNeverShowsContentEvenIfDismissed() {
        val settings = AppSettings(MemoryStore())
        runBlocking { settings.setManageMediaPromptDismissed(true) }
        setGate(PermissionState(allFiles = false, manageMedia = true), settings)
        rule.waitForIdle()
        rule.onNodeWithText(explanation).assertIsDisplayed()
        rule.onNodeWithText("CONTENT").assertDoesNotExist()
    }

    @Test fun grantAllFiles_startsAppSpecificSettingsPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        launchAllFilesAccess(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.action)
        assertEquals("package:dev.kanorin.gallery", intent.data.toString())
    }

    @Test fun grantAllFiles_fallsBackWhenAppPageMissing() {
        val started = mutableListOf<Intent>()
        launchAllFilesAccess(throwingContext(started) { it.data != null })
        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            ),
            started.map { it.action },
        )
        assertNull(started.last().data)
    }

    @Test fun grantAllFiles_doesNotCrashWhenNoSettingsPageExists() {
        val started = mutableListOf<Intent>()
        launchAllFilesAccess(throwingContext(started) { true })
        assertEquals(2, started.size)
    }

    @Test fun manageMedia_startsRequestForThisPackage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        launchManageMedia(activity)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_MANAGE_MEDIA, intent.action)
        assertEquals("package:dev.kanorin.gallery", intent.data.toString())
    }

    @Test fun manageMedia_doesNotCrashWhenNoPageExists() {
        val started = mutableListOf<Intent>()
        launchManageMedia(throwingContext(started) { true })
        assertEquals(1, started.size)
    }

    @Test fun readPermissionState_reflectsAllFiles() {
        assertFalse(readPermissionState(RuntimeEnvironment.getApplication()).allFiles)
        ShadowStorageManager.granted = true
        assertTrue(readPermissionState(RuntimeEnvironment.getApplication()).allFiles)
    }

    @Test fun readPermissionState_manageMediaFollowsMediaStoreOnApi31Plus() {
        assertFalse(readPermissionState(RuntimeEnvironment.getApplication()).manageMedia)
        ShadowMediaStore.canManage = true
        assertTrue(readPermissionState(RuntimeEnvironment.getApplication()).manageMedia)
    }

    @Test @Config(sdk = [30])
    fun readPermissionState_manageMediaTrueBelowApi31() {
        assertTrue(readPermissionState(RuntimeEnvironment.getApplication()).manageMedia)
    }

    private fun throwingContext(started: MutableList<Intent>, throwIf: (Intent) -> Boolean) =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                started += intent
                if (throwIf(intent)) throw ActivityNotFoundException()
            }
        }
}
