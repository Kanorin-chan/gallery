package dev.kanorin.gallery.ui.folder

import android.os.Looper
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.NAME_FIELD_TAG
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.common.ZOOM_PILL_TAG
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.io.File

/** Spec 5.6 on the real screen: Room in memory, real settings, real files, real gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class FolderScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var trip: File
    private lateinit var camera: File
    private lateinit var empty: File
    private lateinit var db: GalleryDb
    private lateinit var ops: FileOps
    private lateinit var bin: RecycleBin
    private val settings = Settings(MemoryStore())
    private val toasts = ToastHostState()
    private val vms = mutableListOf<FolderViewModel>()
    private var shown: FolderViewModel? by mutableStateOf(null)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Four orders that all differ: taken c a b v · modified v a b c · name a b c v · size c b a v.
    private fun seed() {
        root = tmp.newFolder("storage", "emulated", "0")
        trip = File(root, "Pictures/Trip KL").apply { mkdirs() }
        camera = File(root, "DCIM/Camera").apply { mkdirs() }
        empty = File(root, "Pictures/Nothing").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, GalleryDb::class.java).allowMainThreadQueries().build()
        ops = FileOps(db, {}, roots = { if (Looper.myLooper() == Looper.getMainLooper()) rootsOnMain++; listOf(root) }, storageRoot = root.path)
        bin = RecycleBin(db, ops)
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    row(trip, "c.jpg", taken = 3000, modified = 100, bytes = 3000),
                    row(trip, "a.jpg", taken = 2000, modified = 300, bytes = 1000),
                    row(trip, "b.jpg", taken = 1000, modified = 200, bytes = 2000),
                    row(trip, "v.mp4", taken = 500, modified = 400, bytes = 500, video = true),
                    row(camera, "cam.jpg", taken = 9000, modified = 900, bytes = 100),
                ),
            )
            db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES ('${File(trip, "b.jpg").path}', 5)")
        }
    }

    private fun row(dir: File, name: String, taken: Long, modified: Long, bytes: Int, video: Boolean = false): MediaEntity {
        val f = File(dir, name).apply { writeBytes(ByteArray(bytes)) }
        return MediaEntity(
            path = f.path, folderPath = dir.path, name = name, mimeType = if (video) "video/mp4" else "image/jpeg",
            isVideo = video, dateTaken = taken, dateModified = modified, size = bytes.toLong(), width = 4, height = 3,
            durationMs = if (video) 5_000 else 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
        )
    }

    private val firstSync = MutableStateFlow(false)
    private var rootsOnMain = 0

    private fun vmFor(path: String) = FolderViewModel(
        path, db.mediaDao(), settings, SavedStateHandle(), ops, bin, storageRoot = root.path, toasts = toasts,
        firstSync = firstSync,
    ).also { vms += it }

    /** Opens [path]; a second call is "leave and come back" (a fresh ViewModel on the same Room and settings). */
    private fun open(path: String) {
        val next = vmFor(path)
        if (vms.size == 1) shown = next else { rule.runOnUiThread { shown = next }; rule.waitForIdle() }
        if (vms.size == 1) {
            rule.setContent {
                GalleryTheme {
                    ToastHost(toasts) { shown?.let { key(it) { FolderScreen(it, onBack = {}) } } }
                }
            }
        }
        until { shown!!.stats.value != null && exists(hasTestTag(FOLDER_TITLE_TAG)) }
    }

    @After fun tearDown() {
        vms.forEach { cancelAndDrain(it.viewModelScope) }
        if (::db.isInitialized) db.close()
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = rule.waitUntil(10_000) { exists(hasText(text)) }
    private fun chip(tag: String, text: String) = rule.waitUntil(10_000) { exists(hasTestTag(tag) and hasText(text)) }
    private fun sortChip(label: String, arrow: String) {
        chip(CHIP_SORT_TAG, label)
        chip(CHIP_SORT_DIRECTION_TAG, arrow)
    }
    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { exists(hasTestTag(tag)) }

    // Plain state, not semantics: pump Main each poll, or a coroutine waiting on it never resumes.
    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }

    private fun names() = runBlocking {
        val vm = shown!!
        db.mediaDao().orderedPaths(vm.filter.value!!, vm.path.value).map { File(it).name }
    }

    private fun centre(r: DpRect): Offset = with(rule.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }
    private fun tile(i: Int) = centre(rule.onNodeWithTag(tileTag(i)).getUnclippedBoundsInRoot())

    private fun select(vararg tiles: Int) {
        val first = tile(tiles.first())
        rule.onRoot().performTouchInput { down(first) }
        rule.mainClock.advanceTimeBy(400)
        rule.onRoot().performTouchInput { up() }
        for (t in tiles.drop(1)) {
            val p = tile(t)
            rule.onRoot().performTouchInput { click(p) }
        }
        waitForText("${tiles.size} selected")
    }

    // ---- app bar

    @Test fun appBarShowsTheNameAndCountAndSize() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(FOLDER_TITLE_TAG).assertTextEquals("Trip KL")
        // 3000 + 1000 + 2000 + 500 bytes.
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertTextEquals("4 items · " + Formatter.formatShortFileSize(context, 6_500))
    }

    @Test fun oneItemIsSingular() {
        seed()
        open(camera.path)
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertTextEquals("1 item · " + Formatter.formatShortFileSize(context, 100))
    }

    @Test fun favouritesIsTitledAndCounted() {
        seed()
        open(FAVORITES_FOLDER)
        rule.onNodeWithTag(FOLDER_TITLE_TAG).assertTextEquals("Favourites")
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertTextEquals("1 item · " + Formatter.formatShortFileSize(context, 2_000))
        // Nothing to rename or hide on the built-in folder, and the Favourites chip would be a no-op.
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_GRID_SIZE_TAG).assertExists()
        assertFalse(exists(hasTestTag(MENU_RENAME_TAG)))
        assertFalse(exists(hasTestTag(MENU_HIDE_TAG)))
        assertFalse(exists(hasTestTag(CHIP_FAVOURITES_TAG)))
    }

    // ---- grid size

    @Test fun firstOpenUsesTheSettingsDefault() {
        seed()
        runBlocking { settings.setGridInside(4) }
        open(trip.path)
        until { shown!!.columns.value == 4 }
    }

    @Test fun gridFiveIsRememberedPerFolder() {
        seed()
        open(trip.path)
        until { shown!!.columns.value == 3 }
        val before = rule.onNodeWithTag(tileTag(0)).getUnclippedBoundsInRoot()
        rule.onNodeWithText("5").performClick()
        until { shown!!.columns.value == 5 }
        // 5 across 360 dp: the tile is about 72 dp, and it was about 120 dp at 3.
        val after = rule.onNodeWithTag(tileTag(0)).getUnclippedBoundsInRoot()
        assertTrue("narrower tiles at 5", (after.right - after.left) < (before.right - before.left) * 0.7f)

        open(trip.path) // leave and come back
        until { shown!!.columns.value == 5 }
        open(camera.path) // another folder keeps its own
        until { shown!!.columns.value == 3 }
    }

    @Test fun thePillIsAlwaysThereWithTappableNumbersTwoToSix() {
        seed()
        open(trip.path)
        waitForTag(ZOOM_PILL_TAG)
        for (n in 2..6) rule.onNodeWithText("$n").assertExists()
        rule.onNodeWithText("6").performClick()
        until { shown!!.columns.value == 6 }
    }

    @Test fun oneRangeSharedByTheStepperPillAndSetter() {
        seed()
        assertEquals(2..6, INSIDE_COLUMN_RANGE)
        assertEquals(Settings.GRID_INSIDE_RANGE, INSIDE_COLUMN_RANGE)
        open(trip.path)
        until { shown!!.columns.value == 3 }
        shown!!.setColumns(7) // outside the range: dropped
        shown!!.setColumns(1)
        rule.waitForIdle()
        assertEquals(3, shown!!.columns.value)
        assertNull(runBlocking { db.mediaDao().observePrefs(trip.path).first() }?.gridColumns)
    }

    @Test fun theMenusGridSizeRowPointsAtThePill() {
        seed()
        open(trip.path)
        waitForTag(ZOOM_PILL_TAG)
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_GRID_SIZE_TAG).performClick()
        // Still just the one pill; the row doesn't open anything else.
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertExists()
    }

    // ---- sort and chips

    @Test fun sortChipCyclesTheFieldAndTheOrderChanges() {
        seed()
        open(trip.path)
        sortChip("Date taken", "↓")
        until { names() == listOf("c.jpg", "a.jpg", "b.jpg", "v.mp4") }

        rule.onNodeWithTag(CHIP_SORT_TAG).performClick()
        sortChip("Date modified", "↓")
        until { names() == listOf("v.mp4", "a.jpg", "b.jpg", "c.jpg") }

        rule.onNodeWithTag(CHIP_SORT_TAG).performClick()
        // Names start A to Z, which is the name's usual order: ↓.
        sortChip("Name", "↓")
        until { names() == listOf("a.jpg", "b.jpg", "c.jpg", "v.mp4") }

        rule.onNodeWithTag(CHIP_SORT_TAG).performClick()
        sortChip("Size", "↓")
        until { names() == listOf("c.jpg", "b.jpg", "a.jpg", "v.mp4") }

        rule.onNodeWithTag(CHIP_SORT_TAG).performClick()
        sortChip("Length", "↓")
        until { names() == listOf("v.mp4", "c.jpg", "a.jpg", "b.jpg") }

        rule.onNodeWithTag(CHIP_SORT_TAG).performClick() // wraps round
        sortChip("Date taken", "↓")
    }

    @Test fun holdingTheSortChipFlipsTheDirection() {
        seed()
        open(trip.path)
        until { names() == listOf("c.jpg", "a.jpg", "b.jpg", "v.mp4") }
        rule.onNodeWithTag(CHIP_SORT_TAG).performTouchInput { longClick() } // the shortcut
        sortChip("Date taken", "↑")
        until { names() == listOf("v.mp4", "b.jpg", "a.jpg", "c.jpg") }
    }

    @Test fun tappingTheArrowFlipsTheDirectionAndSaysSo() {
        seed()
        open(trip.path)
        until { names() == listOf("c.jpg", "a.jpg", "b.jpg", "v.mp4") }
        rule.onNodeWithTag(CHIP_SORT_DIRECTION_TAG).assert(hasContentDescription("Sort direction, newest first"))
        rule.onNodeWithTag(CHIP_SORT_DIRECTION_TAG).performClick()
        sortChip("Date taken", "↑") // the label did not move on
        until { names() == listOf("v.mp4", "b.jpg", "a.jpg", "c.jpg") }
        rule.onNodeWithTag(CHIP_SORT_DIRECTION_TAG).assert(hasContentDescription("Sort direction, oldest first"))
        // Tapping the label next cycles the field, at its usual order.
        rule.onNodeWithTag(CHIP_SORT_TAG).performClick()
        sortChip("Date modified", "↓")
        // The label offers the hold shortcut to a screen reader.
        rule.onNodeWithTag(CHIP_SORT_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    @Test fun theFavouritesChipIsACheckboxThatSaysItsState() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(CHIP_FAVOURITES_TAG).assertIsOff()
        rule.onNodeWithTag(CHIP_FAVOURITES_TAG).performClick()
        rule.onNodeWithTag(CHIP_FAVOURITES_TAG).assertIsOn()
    }

    @Test fun sortIsRememberedPerFolder() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(CHIP_SORT_TAG).performClick()
        sortChip("Date modified", "↓")
        rule.onNodeWithTag(CHIP_SORT_TAG).performTouchInput { longClick() } // oldest first
        until { names() == listOf("c.jpg", "b.jpg", "a.jpg", "v.mp4") }

        open(trip.path)
        sortChip("Date modified", "↑")
        until { shown!!.sort.value?.field == SortField.DATE_MODIFIED && shown!!.sort.value?.descending == false }

        open(camera.path) // untouched: the first-open order
        sortChip("Date taken", "↓")
    }

    @Test fun typeChipCyclesAllPhotosVideos() {
        seed()
        open(trip.path)
        chip(CHIP_TYPE_TAG, "All types")
        rule.onNodeWithTag(CHIP_TYPE_TAG).performClick()
        chip(CHIP_TYPE_TAG, "Photos")
        until { names() == listOf("c.jpg", "a.jpg", "b.jpg") }
        rule.onNodeWithTag(CHIP_TYPE_TAG).performClick()
        chip(CHIP_TYPE_TAG, "Videos")
        until { names() == listOf("v.mp4") }
        rule.onNodeWithTag(CHIP_TYPE_TAG).performClick()
        chip(CHIP_TYPE_TAG, "All types")
    }

    @Test fun favouritesChipNarrowsToFavouritesAndIsNotRemembered() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(CHIP_FAVOURITES_TAG).performClick()
        until { names() == listOf("b.jpg") }
        // Not remembered: the next visit shows everything again.
        open(trip.path)
        until { names().size == 4 }
    }

    // ---- Favourites folder

    private fun favourite(vararg files: File) = runBlocking {
        files.forEachIndexed { i, f ->
            db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO favorites(path, addedAt) VALUES (?, ?)", arrayOf(f.path, 10L + i))
        }
    }

    @Test fun sortChipsReorderTheFavouritesGrid() {
        seed()
        favourite(File(trip, "a.jpg"), File(trip, "c.jpg"), File(camera, "cam.jpg")) // plus b.jpg from seed()
        open(FAVORITES_FOLDER)
        // taken: cam 9000, c 3000, a 2000, b 1000
        until { names() == listOf("cam.jpg", "c.jpg", "a.jpg", "b.jpg") }
        rule.onNodeWithTag(CHIP_SORT_TAG).performClick() // modified: cam 900, a 300, b 200, c 100
        until { names() == listOf("cam.jpg", "a.jpg", "b.jpg", "c.jpg") }
        rule.onNodeWithTag(CHIP_SORT_TAG).performClick() // name A to Z
        until { names() == listOf("a.jpg", "b.jpg", "c.jpg", "cam.jpg") }
        rule.onNodeWithTag(CHIP_SORT_TAG).performClick() // size: c 3000, b 2000, a 1000, cam 100
        until { names() == listOf("c.jpg", "b.jpg", "a.jpg", "cam.jpg") }
        rule.onNodeWithTag(CHIP_SORT_DIRECTION_TAG).performClick()
        until { names() == listOf("cam.jpg", "a.jpg", "b.jpg", "c.jpg") }
    }

    @Test fun deletingAFavouriteDropsItsTileAndTheCount() {
        seed()
        favourite(File(trip, "a.jpg"), File(trip, "c.jpg"))
        open(FAVORITES_FOLDER)
        waitForTag(tileTag(2))
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertTextEquals("3 items · " + Formatter.formatShortFileSize(context, 6_000))
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Delete 1 item?")
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until { shown!!.stats.value?.count == 2 }
        rule.waitUntil(10_000) { !exists(hasTestTag(tileTag(2))) }
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertTextEquals("2 items · " + Formatter.formatShortFileSize(context, 3_000))
    }

    @Test fun movingAFavouriteKeepsItOnceAtItsNewPath() {
        // The favourite row follows the file, so the grid shows it at the new path, never twice, never a ghost.
        seed()
        favourite(File(trip, "a.jpg"))
        open(FAVORITES_FOLDER)
        waitForTag(tileTag(1))
        val other = File(root, "Pictures/Other").apply { mkdirs() }
        rule.runOnUiThread { shown!!.actions.selection.setAll(listOf(File(trip, "a.jpg").path), true) }
        rule.runOnUiThread { shown!!.actions.transfer(dev.kanorin.gallery.logic.Transfer.MOVE, other.path) }
        until { File(other, "a.jpg").isFile }
        until {
            val paths = runBlocking { db.mediaDao().orderedPaths(shown!!.filter.value!!, FAVORITES_FOLDER) }
            File(other, "a.jpg").path in paths && File(trip, "a.jpg").path !in paths && paths.size == 2
        }
        until { shown!!.stats.value?.count == 2 }
    }

    @Test fun aFavouriteInAHiddenFolderIsNotCountedOrShown() {
        seed()
        favourite(File(trip, "a.jpg"))
        runBlocking { db.mediaDao().setHiddenOverride(trip.path, true) }
        open(FAVORITES_FOLDER)
        until { shown!!.stats.value?.count == 0 }
        rule.onNodeWithTag(FOLDER_EMPTY_TAG).assertTextEquals("This folder is empty")
        runBlocking { assertEquals(0, db.mediaDao().favouritesSummary().first().count) }
    }

    // ---- search, menu, empty

    @Test fun inlineSearchFiltersThisFolderOnly() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(FOLDER_SEARCH_TAG).performClick()
        waitForTag(FOLDER_SEARCH_FIELD_TAG)
        rule.onNodeWithTag(FOLDER_SEARCH_FIELD_TAG).performTextInput("b.j")
        until { names() == listOf("b.jpg") }
        // "cam.jpg" lives in another folder and never matched.
        rule.onNodeWithContentDescription("Close search").performClick()
        waitForTag(FOLDER_TITLE_TAG)
        until { names().size == 4 }
    }

    @Test fun hideFolderThenUnhide() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithText("Hide folder").performClick()
        until { runBlocking { db.mediaDao().observePrefs(trip.path).first() }?.hiddenOverride == true }
        // The folder stays open after hiding it.
        rule.onNodeWithTag(FOLDER_TITLE_TAG).assertTextEquals("Trip KL")
        until { shown!!.stats.value?.hidden == true }
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithText("Unhide folder").performClick()
        until { runBlocking { db.mediaDao().observePrefs(trip.path).first() }?.hiddenOverride == false }
    }

    @Test fun renameIsOfferedOnlyWhereThePolicyAllowsIt() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_RENAME_TAG).assertExists()
        rule.onNodeWithTag(MENU_RENAME_TAG).performClick()
        rule.onNodeWithText("Rename folder").assertExists() // Task 9's dialog

        // The camera folder is written to by another app.
        open(camera.path)
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_HIDE_TAG).assertExists()
        assertFalse(exists(hasTestTag(MENU_RENAME_TAG)))
    }

    // Round 2 (D): the Rename check reads the roots off the main thread.
    @Test fun theRenameCheckNeverAsksForTheRootsOnMain() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        waitForTag(MENU_RENAME_TAG)
        assertEquals(0, rootsOnMain)
    }

    @Test fun renamingFollowsTheFolderToItsNewPath() {
        seed()
        open(trip.path)
        rule.onNodeWithTag(FOLDER_MENU_TAG).performClick()
        rule.onNodeWithTag(MENU_RENAME_TAG).performClick()
        rule.onNodeWithTag(NAME_FIELD_TAG).performTextInput("2")
        rule.onNodeWithText("Rename").performClick()
        until { File(root, "Pictures/Trip KL2").isDirectory }
        // The screen follows: new name, same four items, nothing empty.
        waitForText("Trip KL2")
        until { shown!!.stats.value?.count == 4 }
        assertFalse(exists(hasTestTag(FOLDER_EMPTY_TAG)))
    }

    // Final review M3: null from a new request until Room answers for it (never the last request's list).
    @Test fun sectionsAreNullUntilRoomAnswersForTheCurrentRequest() {
        seed()
        val vm = vmFor(trip.path)
        val seen = Collections.synchronizedList(mutableListOf<Int?>())
        val watch = CoroutineScope(Dispatchers.Unconfined).launch { vm.sections.collect { seen += it?.sumOf { s -> s.count } } }
        try {
            until { seen.lastOrNull() == 4 }
            assertEquals(null, seen.first())
            seen.clear()
            vm.cycleType() // Photos
            until { seen.lastOrNull() == 3 }
            assertEquals(listOf(null, 3), seen.toList())
        } finally {
            watch.cancel()
        }
    }

    @Test fun anEmptyFolderSaysSo() {
        seed()
        open(empty.path)
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertTextEquals("0 items · " + Formatter.formatShortFileSize(context, 0))
        rule.onNodeWithTag(FOLDER_EMPTY_TAG).assertTextEquals("This folder is empty")
    }

    // Round 2 (B): "This folder is empty" waits for the first pass like "No photos yet"; no results never wait.
    @Test fun anEmptyFolderWaitsForTheFirstSyncButNoMatchesDoNot() {
        seed()
        firstSync.value = true
        open(empty.path)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertFalse(exists(hasTestTag(FOLDER_EMPTY_TAG)))
        firstSync.value = false
        waitForTag(FOLDER_EMPTY_TAG)
        rule.onNodeWithTag(FOLDER_EMPTY_TAG).assertTextEquals("This folder is empty")

        firstSync.value = true
        open(trip.path)
        shown!!.setQuery("zzz")
        until { exists(hasTestTag(FOLDER_EMPTY_TAG) and hasText("No matching items")) }
    }

    @Test fun aFolderDeletedFromUnderUsDoesNotCrash() {
        seed()
        open("${root.path}/Pictures/Gone")
        rule.onNodeWithTag(FOLDER_TITLE_TAG).assertTextEquals("Gone")
        rule.onNodeWithTag(FOLDER_EMPTY_TAG).assertTextEquals("This folder is empty")
    }

    @Test fun emptyWhenTheLastFileLeaves() {
        seed()
        open(camera.path)
        waitForTag(tileTag(0))
        runBlocking { db.mediaDao().deleteByPaths(listOf(File(camera, "cam.jpg").path)) }
        waitForTag(FOLDER_EMPTY_TAG)
    }

    // ---- selection and delete go through the shared code

    @Test fun selectingShowsTheBarAndDeleteUsesTheSharedDialog() {
        seed()
        open(trip.path)
        waitForTag(tileTag(3))
        select(0, 1)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Delete 2 items?")
        rule.onNodeWithText("2 photos").assertExists()
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until { !File(trip, "c.jpg").exists() && !File(trip, "a.jpg").exists() }
        waitForText("2 moved to Recycle bin")
        until { shown!!.stats.value?.count == 2 }
        rule.onNodeWithTag(FOLDER_SUBTITLE_TAG).assertExists()
    }
}
