package dev.kanorin.gallery.ui.folders

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class HiddenFoldersTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val root = "/storage/emulated/0"
    private val trip = "$root/Pictures/Trip KL"
    private val stickers = "$root/WhatsApp/.Stickers"
    private val secret = "$root/Pictures/Secret"
    private lateinit var db: GalleryDb
    private lateinit var vm: HiddenFoldersViewModel
    private val settings = Settings(MemoryStore())
    private var backs = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        vm = HiddenFoldersViewModel(db.mediaDao(), settings, root)
        runBlocking {
            db.mediaDao().upsert(
                listOf(media(trip, "k.jpg", false), media(stickers, "x.webp", true), media(secret, "s.jpg", false)),
            )
            db.mediaDao().setHiddenOverride(secret, true)
        }
    }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun media(folder: String, name: String, hidden: Boolean) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = "image/jpeg", isVideo = false,
        dateTaken = 1, dateModified = 1, size = 1, width = 4, height = 3, durationMs = 0, orientation = 0,
        mediaStoreId = null, hidden = hidden, volume = "external_primary",
    )

    private fun rows(path: String) = rule.onAllNodes(hasTestTag(hiddenRowTag(path))).fetchSemanticsNodes()

    private fun show() {
        rule.setContent { GalleryTheme { HiddenFoldersScreen(vm, onBack = { backs++ }) } }
        rule.waitUntil(10_000) { rows(stickers).isNotEmpty() }
    }

    @Test fun listsRuleHiddenAndOverrideHiddenFoldersOnly() {
        show()
        rule.onNodeWithText("Hidden folders").assertIsDisplayed()
        assertEquals(1, rows(secret).size)
        assertTrue(rows(trip).isEmpty())
    }

    @Test fun unhideRemovesTheRowAndTheFolderReturnsToTheFoldersGrid() {
        show()
        val before = runBlocking { db.mediaDao().foldersModel(settings, root).first() }
        assertTrue(before.arranged.others.none { it.path == stickers })

        rule.onNodeWithTag(unhideTag(stickers)).performClick()
        rule.waitUntil(10_000) { rows(stickers).isEmpty() }
        assertEquals(1, rows(secret).size)

        val after = runBlocking { db.mediaDao().foldersModel(settings, root).first() }
        assertTrue(after.arranged.others.any { it.path == stickers })
        assertTrue(after.arranged.hidden.none { it.path == stickers })
        assertEquals(false, runBlocking { db.mediaDao().folderPrefs().first() }.single { it.path == stickers }.hiddenOverride)
    }

    @Test fun backButtonCallsBack() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
