package dev.kanorin.gallery.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
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
import dev.kanorin.gallery.data.allMediaPaths
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.settings.DeleteChoice
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.TOAST_ACTION_TAG
import dev.kanorin.gallery.ui.common.TOAST_MS
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

/** Spec 5.5 through the real main screen: the bar's delete, the dialog or the remembered choice, the toast and UNDO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class DeleteFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var camera: File
    private lateinit var trip: File
    private lateinit var db: GalleryDb
    private lateinit var vm: MainViewModel
    private lateinit var bin: RecycleBin
    private val settings = Settings(MemoryStore())
    private val toasts = ToastHostState()

    // Tile 0 = IMG_0, 1 = IMG_1, 2 = clip.mp4 (newest first); t.jpg in Trip is older than all of them.
    private val img0 get() = File(camera, "IMG_0.jpg")
    private val img1 get() = File(camera, "IMG_1.jpg")
    private val clip get() = File(camera, "clip.mp4")

    private fun start(remembered: DeleteChoice? = null) {
        root = tmp.newFolder("storage", "emulated", "0")
        camera = File(root, "DCIM/Camera").apply { mkdirs() }
        trip = File(root, "Pictures/Trip").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()
        val rows = listOf(
            row(img0, now, byteArrayOf(1)), row(img1, now - 60_000L, byteArrayOf(2)), row(clip, now - 120_000L, byteArrayOf(3), video = true),
            row(File(trip, "t.jpg"), now - 10 * 86_400_000L, byteArrayOf(4)),
        )
        runBlocking {
            db.mediaDao().upsert(rows)
            settings.setRememberedDeleteChoice(remembered)
        }
        val ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path)
        bin = RecycleBin(db, ops)
        vm = MainViewModel(db.mediaDao(), settings, SavedStateHandle(), ops, bin, storageRoot = root.path, toasts = toasts)
        rule.setContent { GalleryTheme { ToastHost(toasts) { MainScreen(vm) } } }
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(3))) }
    }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun row(f: File, taken: Long, bytes: ByteArray, video: Boolean = false): MediaEntity {
        f.writeBytes(bytes)
        return MediaEntity(
            path = f.path, folderPath = f.parent!!, name = f.name, mimeType = if (video) "video/mp4" else "image/jpeg",
            isVideo = video, dateTaken = taken, dateModified = f.lastModified(), size = f.length(), width = 4, height = 3,
            durationMs = if (video) 61_000 else 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
        )
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun waitForText(text: String) = rule.waitUntil(10_000) { exists(hasText(text)) }
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

    // Plain state, not semantics: pump Main each poll, or a coroutine waiting on it never resumes.
    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }
    private fun binRows() = runBlocking { db.binDao().all() }
    private fun allPaths() = db.allMediaPaths().toSet()

    @Test fun binThenUndoBringsExactlyThoseBack() {
        start()
        select(0, 1, 2)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Delete 3 items?")
        rule.onNodeWithText("2 photos, 1 video").assertExists()
        rule.onNodeWithText("Move to Recycle bin").performClick()

        until { !img0.exists() && !img1.exists() && !clip.exists() }
        waitForText("3 moved to Recycle bin")
        rule.onNodeWithText("UNDO").assertExists()
        rule.onNodeWithText("Search photos").assertExists()
        assertTrue("no stale paths stay selected", vm.selection.selected.isEmpty())
        assertEquals(3, binRows().size)
        assertEquals(setOf(File(trip, "t.jpg").path), allPaths())
        assertNull("not ticked: nothing remembered", runBlocking { settings.rememberedDeleteChoice.first() })

        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()

        until { img0.isFile && img1.isFile && clip.isFile }
        until { binRows().isEmpty() }
        assertArrayEquals(byteArrayOf(1), img0.readBytes())
        assertEquals(setOf(img0.path, img1.path, clip.path, File(trip, "t.jpg").path), allPaths())
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(3))) }
    }

    @Test fun deletePermanentlyGoesForGood() {
        start()
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Delete 1 item?")
        rule.onNodeWithText("1 photo").assertExists()
        rule.onNodeWithText("Delete permanently").performClick()

        until { !img0.exists() }
        waitForText("1 deleted permanently")
        assertFalse(exists(hasTestTag(TOAST_ACTION_TAG)))
        assertTrue(binRows().isEmpty())
        assertFalse(File(root, StorageLayout.BIN_DIR_NAME).exists())
        assertFalse(img0.path in allPaths())
        assertTrue(vm.selection.selected.isEmpty())
    }

    @Test fun rememberMyChoiceIsSavedAndTheNextDeleteSkipsTheDialog() {
        start()
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Remember my choice")
        rule.onNodeWithText("Remember my choice").performClick()
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until { !img0.exists() }
        until { runBlocking { settings.rememberedDeleteChoice.first() } == DeleteChoice.BIN }

        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(1))) }
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        until { !img1.exists() }
        waitForText("1 moved to Recycle bin")
        assertFalse(exists(hasText("Delete 1 item?")))
        assertEquals(2, binRows().size)
    }

    // Ruling AD: a remembered "Delete permanently" can still be undone (select all + one stray tap).
    @Test fun withARememberedChoiceTheDialogIsSkipped() {
        start(remembered = DeleteChoice.PERMANENT)
        select(0, 1)
        rule.onNodeWithContentDescription("Delete").performClick()
        until { !img0.exists() && !img1.exists() }
        waitForText("2 deleted permanently")
        assertFalse(exists(hasText("Delete 2 items?")))
        assertNull(vm.deleteAsk.value)
        assertFalse(img0.path in allPaths())
        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()

        until { img0.isFile && img1.isFile }
        until { binRows().isEmpty() }
        assertArrayEquals(byteArrayOf(1), img0.readBytes())
        assertTrue(img0.path in allPaths())
    }

    // Fix round 2 (N1): an UNDO the user tapped is never erased, even when the restore itself fails.
    @Test fun aHeldDeleteWhoseUndoFailsStaysInTheBinVisibleAndUnerased() {
        start(remembered = DeleteChoice.PERMANENT)
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("1 deleted permanently")
        val before = System.currentTimeMillis()
        bin.renamer = { _, _ -> false }

        rule.onNodeWithTag(TOAST_ACTION_TAG).performClick()

        waitForText("1 couldn't be restored — they're in the Recycle bin")
        val kept = binRows().single()
        assertFalse("no longer held", kept.pendingErase)
        assertTrue("a fresh 30 days from the UNDO", kept.deletedAt >= before)
        assertEquals(listOf(kept.binPath), runBlocking { bin.items().first() }.map { it.binPath })
        rule.mainClock.advanceTimeBy(TOAST_MS + 500)
        runBlocking { bin.purgeExpired(System.currentTimeMillis()) }
        assertTrue(File(kept.binPath).isFile)
        assertEquals(listOf(kept.binPath), binRows().map { it.binPath })
        assertFalse(img0.exists())
    }

    @Test fun aRememberedPermanentIsErasedWhenItsUndoRunsOut() {
        start(remembered = DeleteChoice.PERMANENT)
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("1 deleted permanently")
        val held = binRows().single()
        assertTrue(held.pendingErase)
        assertTrue("held in the bin while UNDO is offered", File(held.binPath).isFile)

        rule.mainClock.advanceTimeBy(TOAST_MS + 500)

        until { binRows().isEmpty() }
        assertFalse(File(held.binPath).exists())
        assertFalse(img0.exists())
    }

    // Minor 5: what the dialog counted is what goes, whatever happens to the selection meanwhile.
    @Test fun theDialogDeletesExactlyWhatItCounted() {
        start()
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Delete 1 item?")
        rule.runOnUiThread { vm.selection.toggle(img1.path) }
        rule.onNodeWithText("Move to Recycle bin").performClick()

        until { !img0.exists() }
        waitForText("1 moved to Recycle bin")
        assertTrue(img1.isFile)
        assertEquals(listOf(img0.path), binRows().map { it.originalPath })
    }

    // Minor 7: each toast's UNDO carries its own op's files.
    @Test fun undoAfterASecondBinOpRestoresOnlyTheFirstOpsItems() {
        start(remembered = DeleteChoice.BIN)
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("1 moved to Recycle bin")
        val first = toasts.toast.value!!
        rule.waitUntil(10_000) { exists(hasTestTag(tileTag(2))) && !exists(hasTestTag(tileTag(3))) }
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        until { !img1.exists() && toasts.toast.value !== first }

        rule.runOnUiThread { first.onAction!!() }

        until { img0.isFile }
        until { binRows().size == 1 }
        assertFalse(img1.exists())
        assertEquals(listOf(img1.path), binRows().map { it.originalPath })
    }

    @Test fun cancelKeepsTheFilesAndTheSelection() {
        start()
        select(0)
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Cancel")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(10_000) { !exists(hasText("Delete 1 item?")) }
        rule.onNodeWithText("1 selected").assertExists()
        assertTrue(img0.isFile)
        assertTrue(binRows().isEmpty())
    }

    @Test fun aFileThatCantBeBinnedIsCounted() {
        start()
        select(0, 1)
        assertTrue(img1.delete()) // another app got there first
        rule.onNodeWithContentDescription("Delete").performClick()
        waitForText("Move to Recycle bin")
        rule.onNodeWithText("Move to Recycle bin").performClick()
        waitForText("1 moved to Recycle bin · 1 couldn't be deleted")
        assertEquals(listOf(img0.path), binRows().map { it.originalPath })
    }
}
