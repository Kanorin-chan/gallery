package dev.kanorin.gallery.ui.common

import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Final review M7: a Room / DataStore call the user started never crashes the app; it says what failed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionActionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java).build()
    private val root by lazy { tmp.newFolder("storage", "emulated", "0") }
    private val ops by lazy { FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path) }
    private val toasts = ToastHostState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() {
        cancelAndDrain(scope)
        db.close()
    }

    private fun actions(
        pathsInOrder: suspend () -> List<String> = { emptyList() },
        settings: Settings = Settings(MemoryStore()),
    ) = SelectionActions(
        db.mediaDao(), settings, SavedStateHandle(), ops, RecycleBin(db, ops), toasts, scope, scope,
        pathsInOrder, File("Pictures"),
    )

    // DataStore's read failing (disk trouble) on the way to the delete dialog.
    private object BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk")
    }

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun indexed(name: String): File = File(root, "DCIM/Camera/$name").apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    MediaEntity(
                        path = path, folderPath = parent!!, name = name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = 1, dateModified = lastModified(), size = 3, width = 4, height = 3, durationMs = 0,
                        orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    ),
                ),
            )
        }
    }

    // Final review M8: a file deleted or moved elsewhere since it was selected drops out before the op.
    @Test fun aSelectedFileGoneFromTheIndexIsLeftOutOfTheDelete() {
        val a = indexed("a.jpg")
        val actions = actions()
        actions.askDelete(listOf(a.path, File(root, "DCIM/Camera/gone.jpg").path))
        until { actions.deleteAsk.value != null }
        assertEquals(DeleteAsk(listOf(a.path), 1, 0), actions.deleteAsk.value)
    }

    @Test fun aSelectedFileGoneFromTheIndexIsLeftOutOfAMove() {
        val a = indexed("a.jpg")
        val b = indexed("b.jpg")
        val unindexed = File(root, "DCIM/Camera/c.jpg").apply { writeBytes(byteArrayOf(4)) }
        val dest = File(root, "Pictures/Trip").apply { mkdirs() }
        val actions = actions()
        actions.selection.setAll(listOf(a.path, b.path, unindexed.path), on = true)
        actions.transfer(Transfer.MOVE, dest.path)
        until { toasts.toast.value?.message?.startsWith("Moved") == true }
        assertEquals("Moved 2 items", toasts.toast.value?.message)
        assertTrue(unindexed.isFile)
        assertTrue(File(dest, "a.jpg").isFile && File(dest, "b.jpg").isFile)
    }

    @Test fun aSelectAllThatCantReadThePathsSaysSo() {
        val actions = actions(pathsInOrder = { throw IOException("disk") })
        actions.toggleAll()
        until { toasts.toast.value?.message == "Couldn't select everything" }
        assertTrue(actions.selection.selected.isEmpty())
    }

    @Test fun aDeleteThatCantReadItsSettingsSaysSo() {
        val actions = actions(settings = Settings(BrokenStore))
        actions.askDelete(listOf(indexed("a.jpg").path))
        until { toasts.toast.value?.message == "Couldn't start the delete" }
        assertNull(actions.deleteAsk.value)
    }
}
