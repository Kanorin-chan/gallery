package dev.kanorin.gallery.ui.folders

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.ui.common.NAME_FIELD_TAG
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.main.MainScreen
import dev.kanorin.gallery.ui.main.MainViewModel
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Spec 5.3 on the real main screen in Folders mode: Room in memory, real settings, real gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class FoldersUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val root = "/storage/emulated/0"
    private val cam = "$root/DCIM/Camera"
    private val dcimShots = "$root/DCIM/Screenshots"
    private val picShots = "$root/Pictures/Screenshots"
    private val trip = "$root/Pictures/Trip KL"
    private val stickers = "$root/WhatsApp/.Stickers"
    private val sdRoot = "/storage/1A2B-3C4D"
    private val long = "$root/Pictures/A really very long folder name that cannot fit one line"
    // A created folder must exist on disk (final review I1); '/' so its card name reads "Mine" on Windows too.
    private lateinit var mine: String
    private lateinit var db: GalleryDb
    private lateinit var vm: MainViewModel
    private val settings = Settings(MemoryStore())
    private val routes = mutableListOf<String>()
    private val toasts = ToastHostState()
    private var rootsOnMain = 0

    @Before fun setUp() {
        mine = File(tmp.root, "Pictures/Mine").apply { mkdirs() }.path.replace('\\', '/')
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        val roots = {
            if (Looper.myLooper() == Looper.getMainLooper()) rootsOnMain++
            listOf(File(root), File(sdRoot))
        }
        vm = FileOps(db, {}, roots = roots, storageRoot = root).let { ops -> MainViewModel(db.mediaDao(), settings, SavedStateHandle(), ops, RecycleBin(db, ops), storageRoot = root, toasts = toasts) }
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    media(cam, "a.jpg", 900), media(cam, "b.jpg", 800),
                    media(dcimShots, "s.png", 700), media(picShots, "t.png", 600),
                    media(trip, "k.jpg", 500), media(long, "l.jpg", 400),
                    media(stickers, "x.webp", 300, hidden = true),
                ),
            )
            db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES ('$cam/a.jpg', 5)")
            db.openHelper.writableDatabase.execSQL("INSERT INTO created_folders(path) VALUES ('$mine')")
        }
        vm.toggleMode()
        rule.setContent {
            GalleryTheme {
                MainScreen(vm, onNavigate = { routes += it })
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(folderCardTag(trip))).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun media(folder: String, name: String, date: Long, hidden: Boolean = false) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = "image/jpeg", isVideo = false,
        dateTaken = date, dateModified = date, size = 1, width = 4, height = 3, durationMs = 0, orientation = 0,
        mediaStoreId = null, hidden = hidden, volume = "external_primary",
    )

    private fun card(path: String) = rule.onNodeWithTag(folderCardTag(path))
    private fun gone(path: String) = rule.onAllNodes(hasTestTag(folderCardTag(path))).fetchSemanticsNodes().isEmpty()
    private fun waitUntilGone(path: String) = rule.waitUntil(10_000) { gone(path) }

    @Test fun sectionsAndCardsFollowTheMockup() {
        rule.onNodeWithText("Pinned").assertIsDisplayed()
        // 7 media folders - 1 hidden = 6; Camera + DCIM/Screenshots are pinned; Mine is created and empty.
        rule.onNodeWithText("All folders · 4").assertIsDisplayed()
        card(FAV_PATH).assertIsDisplayed()
        rule.onNodeWithTag(folderCountTag(cam), useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithTag(folderCountTag(mine), useUnmergedTree = true).assertTextEquals("Empty")
        rule.onNodeWithTag(folderCountTag(FAV_PATH), useUnmergedTree = true).assertTextEquals("1")
        assertTrue(gone(stickers))
    }

    @Test fun pinnedCardsComeInTheSpecOrder() {
        fun x(p: String) = card(p).getUnclippedBoundsInRoot().left
        assertTrue(x(FAV_PATH) < x(cam))
        assertTrue(x(cam) < x(dcimShots))
    }

    @Test fun twoScreenshotsFoldersShowTheirParent() {
        rule.onNodeWithText("Pictures").assertIsDisplayed()
        // DCIM/Screenshots is pinned and still shows "DCIM" because its twin is below.
        rule.onNodeWithText("DCIM").assertIsDisplayed()
    }

    @Test fun allCoversAreExactlyTheSameSize() {
        val sizes = listOf(FAV_PATH, cam, trip, long, mine).map { p ->
            val b = rule.onNodeWithTag(folderCoverTag(p), useUnmergedTree = true).getUnclippedBoundsInRoot()
            b.right - b.left to b.bottom - b.top
        }
        assertEquals(1, sizes.distinct().size)
        assertEquals(sizes[0].first, sizes[0].second)
    }

    @Test fun longNamesStayOnOneLine() {
        val b = card(long).getUnclippedBoundsInRoot()
        val shorter = card(trip).getUnclippedBoundsInRoot()
        assertEquals(shorter.bottom - shorter.top, b.bottom - b.top)
    }

    @Test fun newFolderIsTheLastCardAndClickable() {
        val last = rule.onNodeWithTag(NEW_FOLDER_TAG).getUnclippedBoundsInRoot()
        listOf(FAV_PATH, cam, dcimShots, trip, picShots, long, mine).forEach { p ->
            val b = card(p).getUnclippedBoundsInRoot()
            assertTrue("$p must come before New folder", b.top < last.top || (b.top == last.top && b.left < last.left))
        }
        rule.onNodeWithTag(NEW_FOLDER_TAG).performClick()
        rule.onNodeWithText("Created in Pictures/ on your phone").assertIsDisplayed()
        assertEquals(emptyList<String>(), routes)
    }

    @Test fun tapNavigatesWithoutOpeningTheMenuAndLongPressOpensItWithoutNavigating() {
        card(trip).performClick()
        assertEquals(1, routes.size)
        rule.onNodeWithText("Hide").assertDoesNotExist()
        routes.clear()
        card(trip).performTouchInput { longClick() }
        rule.onNodeWithText("Hide").assertIsDisplayed()
        assertEquals(emptyList<String>(), routes)
    }

    @Test fun withShowHiddenOnAHiddenCardOffersUnhide() {
        runBlocking { settings.setShowHidden(true) }
        rule.waitUntil(10_000) { !gone(stickers) }
        card(stickers).performTouchInput { longClick() }
        rule.onNodeWithText("Hide").assertDoesNotExist()
        rule.onNodeWithText("Unhide").performClick()
        rule.waitUntil(10_000) { runBlocking { db.mediaDao().folderPrefs().first() }.any { it.path == stickers && it.hiddenOverride == false } }
    }

    @Test fun tappingAFolderSendsItsRoute() {
        card(trip).performClick()
        card(FAV_PATH).performClick()
        assertEquals(listOf("folder/" + Uri.encode(trip), "favorites"), routes)
    }

    @Test fun longPressOffersPinHideAndRenameWhereAllowed() {
        card(trip).performTouchInput { longClick() }
        rule.onNodeWithText("Pin").assertIsDisplayed()
        rule.onNodeWithText("Hide").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertIsDisplayed()
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithText("Rename folder").assertIsDisplayed()
        rule.onNodeWithTag(NAME_FIELD_TAG).assert(hasText("Trip KL"))
    }

    // Final review: an SD card's own root holds photos but is a storage root: no Rename.
    // Round 2 (D): the mounted roots are read off the main thread, never during composition.
    @Test fun theRenameCheckNeverAsksForTheRootsOnMain() {
        card(trip).performTouchInput { longClick() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Rename")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rootsOnMain)
    }

    @Test fun anSdCardRootHasNoRename() {
        runBlocking { db.mediaDao().upsert(listOf(media(sdRoot, "top.jpg", 200))) }
        rule.waitUntil(10_000) { !gone(sdRoot) }
        card(sdRoot).performTouchInput { longClick() }
        rule.onNodeWithText("Hide").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test fun cameraHasNoRenameAndOffersUnpin() {
        card(cam).performTouchInput { longClick() }
        rule.onNodeWithText("Unpin").assertIsDisplayed()
        rule.onNodeWithText("Hide").assertIsDisplayed()
        rule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test fun favouritesHasNoMenu() {
        card(FAV_PATH).performTouchInput { longClick() }
        rule.onNodeWithText("Hide").assertDoesNotExist()
    }

    @Test fun pinMovesAFolderIntoPinnedAndUnpinMovesItBack() {
        val before = card(trip).getUnclippedBoundsInRoot()
        card(trip).performTouchInput { longClick() }
        rule.onNodeWithTag(MENU_PIN_TAG).performClick()
        rule.waitUntil(10_000) { runBlocking { db.mediaDao().folderPrefs().first() }.any { it.path == trip && it.pinned == true } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("All folders · 3")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(card(trip).getUnclippedBoundsInRoot().top < before.top)

        card(trip).performTouchInput { longClick() }
        rule.onNodeWithText("Unpin").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("All folders · 4")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun hideRemovesTheCardAndStoresTheOverride() {
        card(trip).performTouchInput { longClick() }
        rule.onNodeWithTag(MENU_HIDE_TAG).performClick()
        waitUntilGone(trip)
        val prefs = runBlocking { db.mediaDao().folderPrefs().first() }.single { it.path == trip }
        assertEquals(true, prefs.hiddenOverride)
    }

    @Test fun searchFiltersCardsByNameIgnoringCase() {
        vm.setQuery("tRiP")
        waitUntilGone(cam)
        card(trip).assertIsDisplayed()
        assertTrue(gone(long))
    }

    @Test fun pinchPersistsTheFolderColumnsWithinTwoToFour() {
        val b = card(trip).getUnclippedBoundsInRoot()
        val (cx, cy) = with(rule.density) { Offset(((b.left + b.right) / 2).toPx(), ((b.top + b.bottom) / 2).toPx()) }
        // 80 px -> 110 px: one spread step, 3 -> 2.
        rule.onNodeWithTag(FOLDERS_GRID_TAG).performTouchInput {
            pinch(Offset(cx - 40f, cy), Offset(cx - 55f, cy), Offset(cx + 40f, cy), Offset(cx + 55f, cy), durationMillis = 300)
        }
        rule.waitUntil(10_000) { runBlocking { settings.gridFolders.first() } == 2 }
        assertTrue(card(trip).getUnclippedBoundsInRoot().let { it.right - it.left } > b.right - b.left)
    }

    @Test fun theViewModelRefusesColumnsOutsideTheGridsRange() {
        vm.setFolderColumns(5)
        vm.setFolderColumns(1)
        rule.waitForIdle()
        assertEquals(3, runBlocking { settings.gridFolders.first() })
        vm.setFolderColumns(4)
        rule.waitUntil(10_000) { runBlocking { settings.gridFolders.first() } == 4 }
    }

    // Final review I1: deleted outside the app, it goes at the list's next refresh, and so does its row.
    @Test fun aCreatedFolderDeletedOnDiskLosesItsCard() {
        assertTrue(File(mine).delete())
        runBlocking { db.mediaDao().setPinned(trip, true) }
        waitUntilGone(mine)
        // Round 2 (G): the read flow only filters; the row goes on a write path (here the New folder dialog's siblings).
        rule.waitForIdle()
        assertEquals(listOf(mine), runBlocking { db.mediaDao().createdFolders().first() })
        runBlocking { vm.newFolderSiblings() }
        assertTrue(runBlocking { db.mediaDao().createdFolders().first() }.isEmpty())
    }

    // Final review M7: a pin or hide the database refuses is a toast, never a crash.
    @Test fun aPinOrHideThatCantBeSavedSaysSo() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_prefs BEFORE INSERT ON folder_prefs BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        card(trip).performTouchInput { longClick() }
        rule.onNodeWithTag(MENU_PIN_TAG).performClick()
        rule.waitUntil(10_000) { rule.waitForIdle(); toasts.toast.value?.message == "Couldn't pin the folder" }
        card(trip).performTouchInput { longClick() }
        rule.onNodeWithTag(MENU_HIDE_TAG).performClick()
        rule.waitUntil(10_000) { rule.waitForIdle(); toasts.toast.value?.message == "Couldn't hide the folder" }
        card(trip).assertIsDisplayed()
    }

    @Test fun createdFolderIsAnEmptyCardWithAPlaceholderCover() {
        rule.onNodeWithTag(folderCoverTag(mine), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(folderCountTag(mine), useUnmergedTree = true).assertTextEquals("Empty")
    }

    private companion object {
        const val FAV_PATH = "favorites://"
    }
}
