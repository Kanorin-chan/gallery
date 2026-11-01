package dev.kanorin.gallery.ui.bin

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.BinPolicy
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.viewer.binViewerRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Spec 5.5: the Recycle bin screen on real binned files, Room in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class BinScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var camera: File
    private lateinit var db: GalleryDb
    private lateinit var vm: BinViewModel
    private lateinit var bin: RecycleBin
    private lateinit var root: File
    private val toasts = ToastHostState()
    private val now = 1_790_000_000_000L
    private val day = BinPolicy.DAY_MS
    private val hour = 3_600_000L
    private var backs = 0

    // Newest first: tile 0 = a (just binned), 1 = b (10 d 1 h ago), 2 = c (29 d 1 h ago).
    private val a get() = File(camera, "a.jpg")
    private val b get() = File(camera, "b.jpg")
    private val c get() = File(camera, "c.mp4")

    @Before fun setUp() {
        root = tmp.newFolder("storage", "emulated", "0")
        camera = File(root, "DCIM/Camera").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        val ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path)
        var binnedAt = now
        val bin = RecycleBin(db, ops, clock = { binnedAt })
        runBlocking {
            for ((f, at) in listOf(c to now - 29 * day - hour, b to now - 10 * day - hour, a to now)) {
                f.writeBytes(byteArrayOf(1, 2, 3))
                db.mediaDao().upsert(listOf(row(f)))
                binnedAt = at
                assertEquals(listOf(f.path), bin.moveToBin(listOf(f.path)).done)
            }
        }
        this.bin = bin
    }

    private val routes = mutableListOf<String>()

    private fun open() {
        vm = BinViewModel(bin, SavedStateHandle(), toasts, clock = { now })
        rule.setContent { GalleryTheme { ToastHost(toasts) { BinScreen(vm, onBack = { backs++ }, onOpen = { routes += it }) } } }
        rule.waitUntil(10_000) { exists(hasTestTag(binTileTag(2))) }
    }

    @After fun tearDown() {
        if (::vm.isInitialized) cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun row(f: File) = MediaEntity(
        path = f.path, folderPath = f.parent!!, name = f.name, mimeType = if (f.name.endsWith("mp4")) "video/mp4" else "image/jpeg",
        isVideo = f.name.endsWith("mp4"), dateTaken = 1, dateModified = f.lastModified(), size = 3, width = 4, height = 3,
        durationMs = if (f.name.endsWith("mp4")) 61_000 else 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
    )

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = rule.waitUntil(10_000) { exists(hasText(text)) }
    private fun centre(r: DpRect): Offset = with(rule.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }
    private fun tile(i: Int) = centre(rule.onNodeWithTag(binTileTag(i)).getUnclippedBoundsInRoot())
    // Plain state, not semantics: pump Main each poll, or a coroutine waiting on it never resumes.
    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }
    private fun binCount() = runBlocking { db.binDao().all().size }
    private fun favourites(): List<String> =
        db.openHelper.readableDatabase.query("SELECT path FROM favorites").use { c -> List(c.count) { c.moveToNext(); c.getString(0) } }

    private fun hold(i: Int) {
        val p = tile(i)
        rule.onRoot().performTouchInput { down(p) }
        rule.mainClock.advanceTimeBy(400)
        rule.onRoot().performTouchInput { up() }
    }

    // Final review M9: a tap (not selecting) opens the binned file alone; while selecting it still toggles.
    @Test fun aTapOpensTheBinnedFileInTheViewer() {
        open()
        rule.onRoot().performTouchInput { click(tile(2)) }
        rule.waitForIdle()
        val c = runBlocking { db.binDao().all() }.single { it.originalPath == this@BinScreenTest.c.path }
        assertEquals(listOf(binViewerRoute(c.binPath, "video/mp4")), routes)
        hold(0)
        waitForText("1 selected")
        rule.onRoot().performTouchInput { click(tile(1)) }
        waitForText("2 selected")
        assertEquals(1, routes.size)
    }

    @Test fun eachTileShowsItsDaysLeft() {
        open()
        rule.onNodeWithText("Recycle bin").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
        rule.onNodeWithText("30 days left").assertIsDisplayed()
        rule.onNodeWithText("20 days left").assertIsDisplayed()
        rule.onNodeWithText("Last day").assertIsDisplayed()
        rule.onNodeWithText("01:01").assertIsDisplayed()
    }

    @Test fun holdAndTapSelectThenRestoreBringsThemBack() {
        open()
        hold(0)
        waitForText("1 selected")
        val p = tile(1)
        rule.onRoot().performTouchInput { click(p) }
        waitForText("2 selected")
        rule.onNodeWithTag(BIN_RESTORE_TAG).performClick()

        until { a.isFile && b.isFile }
        waitForText("Restored 2 items")
        assertFalse(c.exists())
        assertEquals(1, binCount())
        rule.onNodeWithText("Recycle bin").assertIsDisplayed()
        assertTrue("selection cleared", vm.selection.selected.isEmpty())
        rule.waitUntil(10_000) { !exists(hasTestTag(binTileTag(1))) }
    }

    @Test fun holdAndDragSelectsTheRange() {
        open()
        val from = tile(0)
        val to = tile(2)
        rule.onRoot().performTouchInput { down(from) }
        rule.mainClock.advanceTimeBy(400)
        rule.onRoot().performTouchInput { moveTo(Offset((from.x + to.x) / 2, from.y)) }
        rule.onRoot().performTouchInput { moveTo(to) }
        rule.onRoot().performTouchInput { up() }
        waitForText("3 selected")
    }

    @Test fun deleteForeverAsksFirstAndCancelKeepsIt() {
        open()
        hold(0)
        rule.onNodeWithTag(BIN_DELETE_TAG).performClick()
        waitForText("Delete 1 item forever?")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(10_000) { !exists(hasText("Delete 1 item forever?")) }
        assertEquals(3, binCount())

        rule.onNodeWithTag(BIN_DELETE_TAG).performClick()
        waitForText("Delete 1 item forever?")
        rule.onNodeWithTag(BIN_CONFIRM_TAG).assertTextEquals("Delete forever")
        rule.onNodeWithTag(BIN_CONFIRM_TAG).performClick()

        until { binCount() == 2 }
        waitForText("1 deleted permanently")
        assertFalse(a.exists())
        val left = File(camera.parentFile!!.parentFile, ".gallery-bin").list()!!.filter { it != ".nomedia" }
        assertEquals("two files and their sidecars", 2, left.count { !it.endsWith(".json") })
        assertEquals(2, left.count { it.endsWith(".json") })
    }

    @Test fun emptyBinAsksThenDeletesEverything() {
        open()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithTag(EMPTY_BIN_TAG).performClick()
        waitForText("Empty the Recycle bin?")
        rule.onNodeWithText("Empty bin").assertIsDisplayed()
        rule.onNodeWithTag(BIN_CONFIRM_TAG).performClick()

        until { binCount() == 0 }
        waitForText("3 deleted permanently")
        waitForText("Recycle bin is empty")
        assertFalse(a.exists() || b.exists() || c.exists())
    }

    @Test fun closeAndBackEndSelectionThenBackLeaves() {
        open()
        hold(0)
        waitForText("1 selected")
        rule.onNodeWithContentDescription("Close").performClick()
        waitForText("Recycle bin")
        hold(1)
        waitForText("1 selected")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Recycle bin")
        assertEquals(0, backs)
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun selectAllTogglesEveryItem() {
        open()
        hold(0)
        rule.onNodeWithContentDescription("Select all").performClick()
        waitForText("3 selected")
        rule.onNodeWithContentDescription("Select all").performClick()
        until { vm.selection.selected.isEmpty() }
    }

    // Ruling AB: a row whose file is gone never shows as a broken tile; opening the screen settles it.
    @Test fun aRowWhoseFileIsGoneIsNeverATileAndItsFavouriteGoesBack() {
        val back = File(camera, "back.jpg").apply { writeBytes(byteArrayOf(5)) }
        val ghost = File(root, "${StorageLayout.BIN_DIR_NAME}/77777777-7777-7777-7777-777777777777.jpg").path
        runBlocking { db.binDao().insert(BinEntity(ghost, back.path, now, 3, "image/jpeg", false, 0)) }
        db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES (?, 1)", arrayOf(ghost))

        open()

        assertFalse(exists(hasTestTag(binTileTag(3))))
        until { binCount() == 3 }
        assertEquals(listOf(back.path), favourites())
    }

    // Minor 6: Empty bin deletes the items on screen when it's confirmed, nothing it never showed.
    @Test fun emptyBinDeletesOnlyWhatTheScreenShows() {
        val d = File(camera, "d.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        runBlocking { db.mediaDao().upsert(listOf(row(d))) }
        // A remembered "Delete permanently" waiting on its UNDO: hidden here.
        val held = runBlocking { bin.moveToBin(listOf(d.path), pendingErase = true) }.landed.single()
        open()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithTag(EMPTY_BIN_TAG).performClick()
        waitForText("Empty the Recycle bin?")
        rule.onNodeWithTag(BIN_CONFIRM_TAG).performClick()

        until { binCount() == 1 }
        waitForText("3 deleted permanently")
        assertTrue(File(held).isFile)
    }
}
