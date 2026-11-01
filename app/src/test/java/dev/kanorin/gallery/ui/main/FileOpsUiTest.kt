package dev.kanorin.gallery.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.NAME_FIELD_TAG
import dev.kanorin.gallery.ui.common.TOAST_ACTION_TAG
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.folders.NEW_FOLDER_TAG
import dev.kanorin.gallery.ui.folders.folderCardTag
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.timeline.TIMELINE_GRID_TAG
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Spec 5.2 / 5.3 file ops through the real main screen: real files in a temp dir, Room in memory, the app's toasts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class FileOpsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var camera: File
    private lateinit var trip: File
    private lateinit var db: GalleryDb
    private lateinit var vm: MainViewModel
    private val toasts = ToastHostState()
    private var picks = 0

    @Before fun setUp() {
        root = tmp.newFolder("storage", "emulated", "0")
        camera = File(root, "DCIM/Camera").apply { mkdirs() }
        trip = File(root, "Pictures/Trip").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()
        // Tile i is IMG_i (newest first); t.jpg is older than all of them.
        val rows = List(3) { i -> row(File(camera, "IMG_$i.jpg"), now - i * 60_000L) } + row(File(trip, "t.jpg"), now - 10 * 86_400_000L)
        runBlocking { db.mediaDao().upsert(rows) }
        val ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path)
        vm = MainViewModel(db.mediaDao(), Settings(MemoryStore()), SavedStateHandle(), ops, RecycleBin(db, ops), storageRoot = root.path, toasts = toasts)
        rule.setContent { GalleryTheme { ToastHost(toasts) { MainScreen(vm, onPickFolder = { picks++ }) } } }
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(2))) }
    }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun row(f: File, taken: Long): MediaEntity {
        f.writeBytes(byteArrayOf(1, 2, 3))
        return MediaEntity(
            path = f.path, folderPath = f.parent!!, name = f.name, mimeType = "image/jpeg", isVideo = false,
            dateTaken = taken, dateModified = f.lastModified(), size = 3, width = 4, height = 3, durationMs = 0,
            orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
        )
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = rule.waitUntil(10_000) { exists(hasText(text)) }

    private fun centre(r: DpRect): Offset = with(rule.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }
    private fun tile(i: Int) = centre(rule.onNodeWithTag(tileTag(i)).getUnclippedBoundsInRoot())

    // The dialog checks the name once the folder's listing is in: press until it has.
    private fun press(button: String, done: () -> Boolean) = rule.waitUntil(10_000) {
        if (done()) return@waitUntil true
        if (exists(hasText(button))) rule.onNodeWithText(button).performClick()
        rule.waitForIdle()
        done()
    }

    private fun createFolder(name: String): File {
        vm.toggleMode()
        rule.waitUntil(10_000) { exists(hasTestTag(NEW_FOLDER_TAG)) }
        rule.onNodeWithTag(NEW_FOLDER_TAG).performClick()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput(name)
        val made = File(root, "Pictures/$name")
        press("Create") { made.isDirectory }
        return made
    }

    @Test fun newFolderMakesARealFolderWhoseCardComesJustBeforeNewFolder() {
        val made = createFolder("Holiday 🌸")
        rule.waitUntil(10_000) { exists(hasTestTag(folderCardTag(made.path))) }
        val card = rule.onNodeWithTag(folderCardTag(made.path)).getUnclippedBoundsInRoot()
        val plus = rule.onNodeWithTag(NEW_FOLDER_TAG).getUnclippedBoundsInRoot()
        assertTrue(card.top < plus.top || (card.top == plus.top && card.left < plus.left))
        rule.onNodeWithText("Folder “Holiday 🌸” created").assertIsDisplayed()
        rule.onNodeWithText("ADD PHOTOS").assertIsDisplayed()
    }

    @Test fun aNameAlreadyOnDiskShowsTheInlineError() {
        vm.toggleMode()
        rule.waitUntil(10_000) { exists(hasTestTag(NEW_FOLDER_TAG)) }
        rule.onNodeWithTag(NEW_FOLDER_TAG).performClick()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("trip")
        press("Create") { exists(hasText("A folder with that name already exists")) }
        assertEquals(listOf("Trip"), File(root, "Pictures").list()!!.toList())
    }

    @Test fun addPhotosSelectsInTheAllViewThenMovesIntoTheNewFolder() {
        val made = createFolder("Holiday")
        waitForText("ADD PHOTOS")
        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()
        waitForText("0 selected")
        rule.onNodeWithTag(ADD_TO_TAG).assertIsDisplayed()
        rule.onNodeWithText("Add to Holiday").assertIsDisplayed()
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(0))) }
        val p = tile(0)
        rule.onRoot().performTouchInput { click(p) }
        rule.onNodeWithText("1 selected").assertExists()

        rule.onNodeWithTag(ADD_TO_TAG).performClick()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Move").performClick()

        rule.waitUntil(10_000) { File(made, "IMG_0.jpg").isFile }
        assertFalse(File(camera, "IMG_0.jpg").exists())
        waitForText("Moved 1 item")
        rule.onNodeWithText("Search photos").assertExists()
        assertNull(vm.addTarget.value)
    }

    @Test fun addPhotosCopyKeepsTheOriginal() {
        val made = createFolder("Holiday")
        waitForText("ADD PHOTOS")
        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(1))) }
        val p = tile(1)
        rule.onRoot().performTouchInput { click(p) }
        rule.onNodeWithTag(ADD_TO_TAG).performClick()
        rule.onNodeWithText("Copy").performClick()
        rule.waitUntil(10_000) { File(made, "IMG_1.jpg").isFile }
        assertTrue(File(camera, "IMG_1.jpg").isFile)
        waitForText("Copied 1 item")
    }

    @Test fun backLeavesAddPhotos() {
        createFolder("Holiday")
        waitForText("ADD PHOTOS")
        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()
        waitForText("0 selected")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Search photos")
        assertNull(vm.addTarget.value)
        rule.onNodeWithTag(ADD_TO_TAG).assertDoesNotExist()
    }

    @Test fun theBarsMenuOpensThePickerAndThePickersAnswerMoves() {
        val p = tile(0)
        rule.onRoot().performTouchInput { down(p) }
        rule.mainClock.advanceTimeBy(400)
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Copy to folder").assertIsDisplayed()
        rule.onNodeWithText("Move to folder").performClick()
        assertEquals(1, picks)
        assertEquals(Transfer.MOVE, vm.pendingTransfer.value)

        rule.runOnUiThread { vm.transferTo(trip.path) }

        rule.waitUntil(10_000) { File(trip, "IMG_0.jpg").isFile }
        assertFalse(File(camera, "IMG_0.jpg").exists())
        waitForText("Moved 1 item")
        assertNull(vm.pendingTransfer.value)
        runBlocking { assertEquals(trip.path, db.mediaDao().byPath(File(trip, "IMG_0.jpg").path)!!.folderPath) }
    }

    @Test fun renameFromTheLongPressMenuRenamesTheFolderOnDisk() {
        vm.toggleMode()
        rule.waitUntil(10_000) { exists(hasTestTag(folderCardTag(trip.path))) }
        rule.onNodeWithTag(folderCardTag(trip.path)).performTouchInput { longClick() }
        rule.onNodeWithText("Rename").performClick()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextReplacement("Trip 2026 🌸")
        val renamed = File(root, "Pictures/Trip 2026 🌸")
        press("Rename") { renamed.isDirectory }
        rule.waitUntil(10_000) { exists(hasTestTag(folderCardTag(renamed.path))) }
        assertFalse(trip.exists())
        assertTrue(File(renamed, "t.jpg").isFile)
        runBlocking { assertEquals(renamed.path, db.mediaDao().byPath(File(renamed, "t.jpg").path)!!.folderPath) }
    }

    @Test fun inAddPhotosTheAutoScrollZoneSitsAboveTheButton() {
        val now = System.currentTimeMillis()
        runBlocking { db.mediaDao().upsert(List(60) { i -> row(File(camera, "old_$i.jpg"), now - 20 * 86_400_000L - i * 1_000L) }) }
        rule.runOnUiThread { vm.startAdding(trip.path) }
        waitForText("0 selected")
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.sumOf { it.count } == 64 }
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(0))) }
        val p = tile(0)
        rule.onRoot().performTouchInput { down(p) }
        rule.mainClock.advanceTimeBy(400)
        val buttonTop = with(rule.density) { rule.onNodeWithTag(ADD_TO_TAG).getUnclippedBoundsInRoot().top.toPx() }
        rule.mainClock.autoAdvance = false
        // Just above the button: outside a zone measured from the screen's bottom, inside one measured from the button.
        rule.onRoot().performTouchInput { moveTo(Offset(p.x, buttonTop - with(rule.density) { 20.dp.toPx() })) }
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        val n = rule.onNode(hasText("selected", substring = true)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
            .substringBefore(" selected").toInt()
        assertTrue("selected $n", n > 30)
    }

    @Test fun inAddPhotosTheLastRowScrollsClearOfTheButton() {
        val now = System.currentTimeMillis()
        runBlocking { db.mediaDao().upsert(List(60) { i -> row(File(camera, "old_$i.jpg"), now - 20 * 86_400_000L - i * 1_000L) }) }
        rule.runOnUiThread { vm.startAdding(trip.path) }
        waitForText("0 selected")
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.sumOf { it.count } == 64 }
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performScrollToIndex(TimelineLayout(vm.sections.value!!, true).totalEntries - 1)
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(63))) }
        val tileBottom = rule.onNodeWithTag(tileTag(63)).getUnclippedBoundsInRoot().bottom
        val buttonTop = rule.onNodeWithTag(ADD_TO_TAG).getUnclippedBoundsInRoot().top
        assertTrue("last tile bottom $tileBottom must clear the button top $buttonTop", tileBottom <= buttonTop)
    }
}
