package dev.kanorin.gallery.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.bin.BIN_CONFIRM_TAG
import dev.kanorin.gallery.ui.bin.BIN_DELETE_TAG
import dev.kanorin.gallery.ui.bin.BIN_RESTORE_TAG
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Final review M9: a Recycle bin item opens alone in the viewer, read-only, with Restore and Delete forever. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class BinViewerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var db: GalleryDb
    private lateinit var bin: RecycleBin
    private lateinit var vm: ViewerViewModel
    private val toasts = ToastHostState()
    private var closed = 0

    private val photo get() = File(root, "DCIM/Camera/a.jpg")

    @Before fun setUp() {
        root = tmp.newFolder("storage", "emulated", "0")
        db = Room.inMemoryDatabaseBuilder(context, GalleryDb::class.java).allowMainThreadQueries().build()
        val ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path)
        bin = RecycleBin(db, ops)
        photo.parentFile!!.mkdirs()
        photo.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    MediaEntity(
                        path = photo.path, folderPath = photo.parent!!, name = photo.name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = 1, dateModified = photo.lastModified(), size = photo.length(), width = 40, height = 30,
                        durationMs = 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    ),
                ),
            )
            bin.moveToBin(listOf(photo.path))
        }
    }

    @After fun tearDown() {
        if (::vm.isInitialized) cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun binPath() = runBlocking { db.binDao().all() }.single().binPath
    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }

    private fun open(): String {
        val path = binPath()
        vm = ViewerViewModel(
            ViewerArgs(MediaFilter(), null, 0, null, type = "image/jpeg", binPath = path), db.mediaDao(), db.pathDao(),
            Settings(MemoryStore()), SavedStateHandle(), FileOps(db, {}, roots = { listOf(root) }), bin,
            roots = { listOf(root) }, resolver = null, toasts = toasts, storageRoot = root.path,
        )
        rule.setContent { GalleryTheme { ToastHost(toasts) { ViewerScreen(vm, onBack = { closed++ }) } } }
        until { exists(hasTestTag(viewerPageTag(File(path).toURI().toString()))) || exists(hasTestTag(BIN_RESTORE_TAG)) }
        return path
    }

    @Test fun itShowsRestoreAndDeleteForeverOnly() {
        open()
        rule.onNodeWithTag(BIN_RESTORE_TAG).assertExists()
        rule.onNodeWithTag(BIN_DELETE_TAG).assertExists()
        listOf(ACTION_SHARE_TAG, ACTION_EDIT_TAG, ACTION_FAVOURITE_TAG, ACTION_DELETE_TAG, ACTION_MORE_TAG).forEach {
            assertFalse(it, exists(hasTestTag(it)))
        }
    }

    // Round 2 (E): the file's time is read once, off Main, by the ViewModel; the screen never stats it.
    @Test fun theBinnedFilesTimeIsReadOnceIntoTheBrowseState() {
        val binned = open()
        val single = vm.browse.value as Browse.Single
        assertEquals(binned, single.binPath)
        assertEquals(File(binned).lastModified(), single.binModified)
    }

    @Test fun restoreBringsTheFileBackAndCloses() {
        val binned = open()
        rule.onNodeWithTag(BIN_RESTORE_TAG).performClick()
        until { photo.isFile && toasts.toast.value?.message == "Restored 1 item" }
        assertFalse(File(binned).exists())
        assertEquals(1, closed)
    }

    @Test fun deleteForeverAsksThenErasesAndCloses() {
        val binned = open()
        rule.onNodeWithTag(BIN_DELETE_TAG).performClick()
        until { exists(hasText("Delete 1 item forever?")) }
        assertEquals(0, closed)
        rule.onNodeWithTag(BIN_CONFIRM_TAG).performClick()
        until { !File(binned).exists() && toasts.toast.value?.message == "1 deleted permanently" }
        assertFalse(photo.exists())
        assertEquals(1, closed)
    }
}
