package dev.kanorin.gallery.ui.main

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.allMediaPaths
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.fileops.copyBytes
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.ToastHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch

/** A long op is not tied to the screen: it runs in the op scope, shows its progress line, and outlives the ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileOpsScopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val opScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: GalleryDb

    // Snapshot state written outside a composition: drain Main so AndroidUiDispatcher isn't left stuck (STATUS test trap).
    @After fun tearDown() {
        opScope.cancel()
        db.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test fun aMoveKeepsGoingWithItsScreenAndViewModelGone() {
        val root = tmp.newFolder("storage", "emulated", "0")
        val sd = tmp.newFolder("storage", "1A2B-3C4D")
        val camera = File(root, "DCIM/Camera").apply { mkdirs() }
        val dest = File(sd, "Pictures").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java).build()
        val files = List(2) { File(camera, "IMG_$it.jpg").apply { writeBytes(ByteArray(1_000) { 7 }) } }
        runBlocking {
            db.mediaDao().upsert(
                files.map {
                    MediaEntity(
                        path = it.path, folderPath = camera.path, name = it.name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = 1, dateModified = it.lastModified(), size = it.length(), width = 4, height = 3,
                        durationMs = 0, orientation = 0, mediaStoreId = 1, hidden = false, volume = "external_primary",
                    )
                },
            )
        }
        // Across volumes, so the copier runs; it holds the first file until the screen is gone.
        val gate = CountDownLatch(1)
        val ops = FileOps(db, {}, roots = { listOf(root, sd) }, storageRoot = root.path, copier = { from, to -> gate.await(); copyBytes(from, to) })
        val toasts = ToastHostState()
        val vm = MainViewModel(db.mediaDao(), Settings(MemoryStore()), SavedStateHandle(), ops, RecycleBin(db, ops), storageRoot = root.path, toasts = toasts, opScope = opScope)
        vm.selection.setAll(files.map { it.path }, on = true)

        vm.transfer(Transfer.MOVE, dest.path)

        assertFalse("the selection ends when the op starts", vm.selection.active)
        waitFor("the progress line") { toasts.progress.value == "Moving 1 of 2…" }
        // More than a rotation does: the ViewModel itself is cleared.
        vm.viewModelScope.cancel()
        gate.countDown()
        waitFor("the result") { toasts.toast.value?.message == "Moved 2 items" }
        assertNull(toasts.progress.value)
        files.forEach {
            assertFalse(it.exists())
            assertTrue(File(dest, it.name).isFile)
        }
        assertEquals(files.map { File(dest, it.name).path }.toSet(), db.allMediaPaths().toSet())
    }

    // ---- one op per ask (review r1 #6): the second call of a pair finds nothing left to do

    private lateinit var root: File
    private lateinit var camera: File
    private lateinit var trip: File
    private lateinit var a: File
    private val toasts = ToastHostState()

    private fun model(configure: FileOps.() -> Unit = {}): MainViewModel {
        root = tmp.newFolder("storage", "emulated", "0")
        camera = File(root, "DCIM/Camera").apply { mkdirs() }
        trip = File(root, "Pictures/Trip").apply { mkdirs() }
        a = File(camera, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java).build()
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    MediaEntity(
                        path = a.path, folderPath = camera.path, name = a.name, mimeType = "image/jpeg", isVideo = false,
                        dateTaken = 1, dateModified = a.lastModified(), size = 3, width = 4, height = 3,
                        durationMs = 0, orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
                    ),
                ),
            )
        }
        val ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path).apply(configure)
        return MainViewModel(db.mediaDao(), Settings(MemoryStore()), SavedStateHandle(), ops, RecycleBin(db, ops), storageRoot = root.path, toasts = toasts, opScope = opScope)
    }

    // Every launched op finished; Main is pumped meanwhile (createFolderForTransfer hops to it).
    private fun settle() {
        val until = System.currentTimeMillis() + 10_000
        while (opScope.coroutineContext.job.children.any { it.isActive }) {
            check(System.currentTimeMillis() < until) { "ops still running" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun transferTwiceCopiesOnce() {
        val vm = model()
        vm.selection.setAll(listOf(a.path), on = true)
        vm.transfer(Transfer.COPY, trip.path)
        vm.transfer(Transfer.COPY, trip.path)
        assertTrue("the second call launches nothing", opScope.coroutineContext.job.children.count() <= 1)
        settle()
        assertEquals(listOf("a.jpg"), trip.list()!!.toList())
        assertEquals("Copied 1 item", toasts.toast.value?.message)
    }

    @Test fun transferToTwiceCopiesOnce() {
        val vm = model()
        vm.selection.setAll(listOf(a.path), on = true)
        vm.beginTransfer(Transfer.COPY)
        vm.transferTo(trip.path)
        vm.transferTo(trip.path)
        assertTrue("the second call launches nothing", opScope.coroutineContext.job.children.count() <= 1)
        settle()
        assertEquals(listOf("a.jpg"), trip.list()!!.toList())
    }

    @Test fun createFolderForTransferTwiceMakesOneFolderAndOneCopy() {
        val vm = model()
        vm.selection.setAll(listOf(a.path), on = true)
        vm.beginTransfer(Transfer.COPY)
        vm.createFolderForTransfer("Holiday")
        vm.createFolderForTransfer("Holiday")
        // The first can't finish before Main is pumped, so this count is exact.
        assertEquals("the second call launches nothing", 1, opScope.coroutineContext.job.children.count())
        settle()
        val holiday = File(root, "Pictures/Holiday")
        assertEquals(listOf("a.jpg"), holiday.list()!!.toList())
        assertEquals(setOf("Trip", "Holiday"), File(root, "Pictures").list()!!.toSet())
        assertEquals("Copied 1 item", toasts.toast.value?.message)
    }

    @Test fun aStuckRenameToastSaysWhereTheFolderIs() {
        val vm = model { dirRenamer = { from, to -> to.name != "TRIP" && to.name != "Trip" && from.renameTo(to) } }
        vm.renameFolder(trip.path, "TRIP")
        settle()
        assertEquals("Rename got stuck — the folder is at Trip (recovering)", toasts.toast.value?.message)
    }

    @Test fun anOpThatThrowsIsAToastNotACrash() {
        val vm = model { beforeQuery = { throw IllegalStateException("Cannot perform this operation because the connection pool has been closed") } }
        vm.selection.setAll(listOf(a.path), on = true)
        vm.transfer(Transfer.MOVE, trip.path)
        settle()
        assertEquals("1 couldn't be moved", toasts.toast.value?.message)
        assertTrue(a.isFile)
    }

    @Test fun aLaggingIndexIsInTheResultToast() {
        val vm = model { beforeBatchCommit = { throw IOException("database or disk is full") } }
        runBlocking { db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES (?, 1)", arrayOf(a.path)) }
        vm.selection.setAll(listOf(a.path), on = true)
        vm.transfer(Transfer.MOVE, trip.path)
        settle()
        assertEquals("Moved 1 item · some may take a moment to show", toasts.toast.value?.message)
        assertTrue(File(trip, "a.jpg").isFile)
    }
}
