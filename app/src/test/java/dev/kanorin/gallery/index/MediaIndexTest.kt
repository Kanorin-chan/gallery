package dev.kanorin.gallery.index

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.visibleCount
import dev.kanorin.gallery.data.allMediaPaths
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FakeMediaStoreSource.Companion.storeRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var db: GalleryDb
    private lateinit var index: MediaIndex
    private val source = FakeMediaStoreSource()
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val zone = ZoneId.systemDefault()
    private val oct1 = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before fun setUp() {
        root = tmp.newFolder("storage", "emulated", "0")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        index = MediaIndex(db, source, FsWalker(listOf(root)), testScope)
    }

    @After fun tearDown() {
        testScope.cancel()
        db.close()
    }

    private fun file(relative: String): File =
        File(root, relative).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

    private fun jpeg(relative: String, dateTimeOriginal: String?, orientation: Int? = null): File {
        val f = File(root, relative).apply { parentFile!!.mkdirs() }
        f.outputStream().use { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(f.path).apply {
            dateTimeOriginal?.let { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
            orientation?.let { setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
            saveAttributes()
        }
        return f
    }

    private fun sync() = runBlocking { index.sync() }
    private fun row(f: File): MediaEntity? = runBlocking { db.mediaDao().byPath(f.path) }
    private fun paths(): Set<String> = db.allMediaPaths().toSet()
    private fun visible(): Int = db.visibleCount()
    private fun storedGeneration(volume: String): String? =
        runBlocking { db.metaDao().get(MediaIndex.generationKey(volume)) }

    private class CountingWalker(roots: List<File>) : FsWalker(roots) {
        val walks = AtomicInteger()
        override fun walk(): Sequence<File> {
            walks.incrementAndGet()
            return super.walk()
        }
    }

    @Test fun newMediaStoreRowsAppear() {
        val photo = file("DCIM/Camera/IMG_1.jpg")
        val clip = file("DCIM/Camera/VID_1.mp4")
        source.put(storeRow(photo, id = 1, dateTaken = 1_790_000_000_000L))
        source.put(storeRow(clip, id = 2, mimeType = "video/mp4", durationMs = 61_000))
        sync()

        val p = row(photo)!!
        assertEquals(1L, p.mediaStoreId)
        assertEquals(1_790_000_000_000L, p.dateTaken)
        assertFalse(p.hidden)
        val c = row(clip)!!
        assertEquals(2L, c.mediaStoreId)
        assertTrue(c.isVideo)
        assertEquals(61_000L, c.durationMs)
        // The walk saw both files too but adds no second row.
        assertEquals(setOf(photo.path, clip.path), paths())
        assertEquals(2, visible())
    }

    @Test fun fileUnknownToMediaStoreIsFoundByWalker() {
        val meme = file("Download/meme.png").apply { setLastModified(oct1) }
        sync()

        val r = row(meme)!!
        assertNull(r.mediaStoreId)
        assertEquals("meme.png", r.name)
        assertEquals(meme.parent, r.folderPath)
        assertEquals("image/png", r.mimeType)
        assertFalse(r.isVideo)
        assertEquals(3L, r.size)
        assertEquals(oct1, r.dateModified)
        assertEquals("external_primary", r.volume)
        assertFalse(r.hidden)
    }

    @Test fun deletedFileRemovedOnSync() {
        val walked = file("Download/a.jpg")
        val stored = file("DCIM/Camera/b.jpg")
        source.put(storeRow(stored, id = 7))
        sync()
        assertEquals(setOf(walked.path, stored.path), paths())

        walked.delete()
        stored.delete()
        source.remove(7) // MediaStore drops a deleted file's row
        sync()
        assertEquals(emptySet<String>(), paths())
    }

    @Test fun mediaStoreRowsLiveAsLongAsTheirIdIsLive() {
        // Ruling: MediaStore rows are never File.exists()-checked; their id leaving MediaStore removes them.
        val ghost = File(root, "DCIM/Camera/ghost.jpg")
        source.put(storeRow(ghost, id = 9))
        sync()
        sync()
        assertNotNull(row(ghost))
    }

    @Test fun zeroDateTakenFallsBackToModified() {
        val shot = file("Pictures/Screenshots/Screenshot_1.png").apply { setLastModified(oct1) }
        source.put(storeRow(shot, id = 3, dateTaken = 0, dateModified = oct1, mimeType = "image/png"))
        val clip = file("Movies/v.mp4").apply { setLastModified(oct1) }
        source.put(storeRow(clip, id = 4, dateTaken = 0, dateModified = oct1, mimeType = "video/mp4"))
        val walked = file("Download/x.jpg").apply { setLastModified(oct1) }
        sync()

        assertEquals(oct1, row(shot)!!.dateTaken)
        assertEquals(oct1, row(clip)!!.dateTaken)
        assertEquals(oct1, row(walked)!!.dateTaken)
    }

    @Test fun exifDateTimeOriginalFillsMissingDateTaken() {
        val exifTime = LocalDateTime.of(2026, 9, 15, 8, 30).atZone(zone).toInstant().toEpochMilli()
        val walked = jpeg("Download/exif.jpg", "2026:09:15 08:30:00", ExifInterface.ORIENTATION_ROTATE_90)
            .apply { setLastModified(oct1) }
        val stored = jpeg("DCIM/Camera/ms.jpg", "2026:09:15 08:30:00")
        source.put(storeRow(stored, id = 5, dateTaken = 0, dateModified = oct1))
        // MediaStore's own DATE_TAKEN wins: EXIF is not consulted.
        val trusted = jpeg("DCIM/Camera/t.jpg", "2020:01:01 00:00:00")
        source.put(storeRow(trusted, id = 6, dateTaken = 1_790_000_000_000L))
        sync()

        val w = row(walked)!!
        assertEquals(exifTime, w.dateTaken)
        assertEquals(oct1, w.dateModified)
        assertEquals(90, w.orientation)
        assertEquals(64, w.width)
        assertEquals(48, w.height)
        assertEquals(exifTime, row(stored)!!.dateTaken)
        assertEquals(1_790_000_000_000L, row(trusted)!!.dateTaken)
    }

    @Test fun nomediaFolderRowsMarkedHidden() {
        file(".nomedia") // a root-level .nomedia hides nothing
        file("Pictures/Stickers/.nomedia")
        val sticker = file("Pictures/Stickers/s1.webp")
        val nested = file("Pictures/Stickers/pack/s2.webp")
        val status = file("WhatsApp/Media/.Statuses/st.jpg")
        file("Movies/Cache/.nomedia")
        val storedInNomedia = file("Movies/Cache/c.mp4")
        source.put(storeRow(storedInNomedia, id = 11, mimeType = "video/mp4"))
        val normal = file("DCIM/Camera/n.jpg")
        sync()

        assertTrue(row(sticker)!!.hidden)
        assertTrue(row(nested)!!.hidden)
        assertTrue(row(status)!!.hidden)
        assertTrue(row(storedInNomedia)!!.hidden)
        assertFalse(row(normal)!!.hidden)
        assertEquals(1, visible())
    }

    @Test fun nomediaAddedOrRemovedLaterUpdatesExistingRows() {
        val meme = file("Pictures/Memes/m.jpg")
        sync()
        assertFalse(row(meme)!!.hidden)

        val nomedia = file("Pictures/Memes/.nomedia")
        sync()
        assertTrue(row(meme)!!.hidden)

        nomedia.delete()
        sync()
        assertFalse(row(meme)!!.hidden)
    }

    @Test fun anUnhideSurvivesAResyncOfStoreAndWalkerRows() {
        file("Pictures/Stickers/.nomedia")
        val walked = file("Pictures/Stickers/w.webp")
        val stored = file("Pictures/Stickers/s.webp")
        source.put(storeRow(stored, id = 70))
        sync()
        assertTrue(row(walked)!!.hidden)
        val folder = walked.parent!!

        runBlocking { db.mediaDao().setHiddenOverride(folder, false) }
        assertFalse(row(walked)!!.hidden)
        assertFalse(row(stored)!!.hidden)

        // New rows of both kinds arrive, then both halves of the index run again.
        val newWalked = file("Pictures/Stickers/w2.webp")
        val newStored = file("Pictures/Stickers/s2.webp")
        source.put(storeRow(newStored, id = 71))
        runBlocking { index.syncMediaStore() }
        runBlocking { index.fullScan() }
        sync()
        listOf(walked, stored, newWalked, newStored).forEach { assertFalse(it.name, row(it)!!.hidden) }
    }

    @Test fun aHideSurvivesAResync() {
        val walked = file("DCIM/Camera/w.jpg")
        val stored = file("DCIM/Camera/s.jpg")
        source.put(storeRow(stored, id = 80))
        sync()
        runBlocking { db.mediaDao().setHiddenOverride(walked.parent!!, true) }
        assertTrue(row(walked)!!.hidden)

        val newStored = file("DCIM/Camera/s2.jpg")
        val newWalked = file("DCIM/Camera/w2.jpg")
        source.put(storeRow(newStored, id = 81))
        sync()
        listOf(walked, stored, newWalked, newStored).forEach { assertTrue(it.name, row(it)!!.hidden) }
        assertEquals(0, visible())
    }

    @Test fun aHideMadeBetweenTheStoreReadAndItsCommitSurvives() {
        val stored = file("DCIM/Camera/s.jpg")
        source.put(storeRow(stored, id = 90))
        val folder = stored.parent!!
        index.beforeStoreCommit = { db.mediaDao().setHiddenOverride(folder, true) }
        runBlocking { index.syncMediaStore() }
        assertTrue(row(stored)!!.hidden)
        assertEquals(0, visible())
    }

    @Test fun anUnhideMadeBetweenTheStoreReadAndItsCommitSurvives() {
        file("Pictures/Stickers/.nomedia")
        val stored = file("Pictures/Stickers/s.webp")
        source.put(storeRow(stored, id = 91))
        val folder = stored.parent!!
        index.beforeStoreCommit = { db.mediaDao().setHiddenOverride(folder, false) }
        runBlocking { index.syncMediaStore() }
        assertFalse(row(stored)!!.hidden)
    }

    @Test fun aHideMadeDuringTheFinalFolderRefreshSurvives() {
        val walked = file("Pictures/Memes/m.jpg")
        sync()
        val folder = walked.parent!!
        file("Pictures/Memes/.nomedia") // the refresh now wants to hide it by rule...
        index.beforeFolderRefresh = { db.mediaDao().setHiddenOverride(folder, false) } // ...but the user unhid it first
        runBlocking { index.fullScan() }
        assertFalse(row(walked)!!.hidden)
    }

    // Rewritten (final review M2): the old version's rule never changed, so no refresh ran and it guarded nothing.
    @Test fun aHideMadeDuringTheFinalFolderRefreshSurvivesARuleThatUnhides() {
        val nomedia = file("Pictures/Memes/.nomedia")
        val walked = file("Pictures/Memes/m.jpg")
        sync()
        assertTrue(row(walked)!!.hidden)
        val folder = walked.parent!!
        nomedia.delete() // the refresh now wants to show it by rule...
        index.beforeFolderRefresh = { db.mediaDao().setHiddenOverride(folder, true) } // ...but the user hid it first
        runBlocking { index.fullScan() }
        assertTrue(row(walked)!!.hidden)
    }

    // Final review M2: a folder the walk couldn't list (or skipped) must not lose its rows while its files are there.
    @Test fun aRowTheWalkMissedIsKeptWhileItsFileExists() {
        val kept = file("Pictures/Flaky/a.jpg")
        val gone = file("Pictures/Old/b.jpg")
        runBlocking { index.fullScan() }
        gone.delete()
        val blind = object : FsWalker(listOf(root)) {
            override fun walk(): Sequence<File> = super.walk().filterNot { it.parentFile!!.name == "Flaky" }
        }
        runBlocking { MediaIndex(db, source, blind, testScope).fullScan() }
        assertNotNull(row(kept))
        assertNull(row(gone))
    }

    // Round 2 (H): shared storage ignores case, so the old name still "exists"; only an exact name in the listing keeps a row.
    @Test fun aCaseOnlyRenameOutsideTheAppLeavesOneRow() {
        val upper = file("DCIM/Camera/IMG.JPG")
        runBlocking { index.fullScan() }
        val lower = File(upper.parentFile, "img.jpg")
        assertTrue(upper.renameTo(lower))
        runBlocking { index.fullScan() }
        assertEquals(setOf(lower.path), paths())
    }

    // Deferred Task 3 minor: the final hidden pass re-reads .nomedia instead of the walk's start-of-walk cache.
    @Test fun aNomediaAddedDuringTheWalkHidesTheFolderInTheSameScan() {
        val fresh = file("Pictures/Memes/new.jpg")
        val walker = object : FsWalker(listOf(root)) {
            override fun walk(): Sequence<File> {
                val files = super.walk()
                return sequence {
                    for (f in files) {
                        yield(f)
                        if (f == fresh) File(fresh.parentFile, ".nomedia").writeText("")
                    }
                }
            }
        }
        runBlocking { MediaIndex(db, source, walker, testScope).fullScan() }
        assertTrue(row(fresh)!!.hidden)
    }

    @Test fun androidDataNeverWalked() {
        file("Android/data/org.telegram.messenger/files/Telegram/Telegram Images/t.jpg")
        file("Android/obb/com.game/clip.mp4")
        file("${StorageLayout.BIN_DIR_NAME}/0b6f.jpg")
        val whatsapp = file("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/w.jpg")
        sync()
        assertEquals(setOf(whatsapp.path), paths())
    }

    @Test fun binFilesNeverIndexedEvenIfMediaStoreListsThem() {
        val binned = file("${StorageLayout.BIN_DIR_NAME}/0b6f.jpg")
        source.put(storeRow(binned, id = 40))
        sync()
        assertEquals(emptySet<String>(), paths())
    }

    // A copy in flight (or recovered later under its own name) never shows as itself.
    @Test fun theAppsTempFilesNeverIndexedEvenIfMediaStoreListsThem() {
        val temp = file("Pictures/Trip/${StorageLayout.TEMP_PREFIX}1a2b3c4d.jpg")
        source.put(storeRow(temp, id = 41))
        sync()
        assertEquals(emptySet<String>(), paths())
    }

    @Test fun secondSyncOnlyReadsDelta() {
        source.put(storeRow(file("DCIM/Camera/1.jpg"), id = 1))
        source.put(storeRow(file("DCIM/Camera/2.jpg"), id = 2))
        sync()
        assertEquals(listOf("external_primary" to 0L), source.changedSinceCalls)
        val stored = runBlocking { db.metaDao().get(MediaIndex.generationKey("external_primary")) }
        assertEquals("2", stored)

        val third = file("DCIM/Camera/3.jpg")
        source.put(storeRow(third, id = 3))
        sync()
        assertEquals(listOf("external_primary" to 0L, "external_primary" to 2L), source.changedSinceCalls)
        assertNotNull(row(third))
    }

    @Test fun mediaStoreVersionChangeRereadsEverything() {
        source.put(storeRow(file("DCIM/Camera/1.jpg"), id = 1))
        sync()
        source.version = "v2" // MediaStore rebuilt its database: generations restarted
        sync()
        assertEquals(listOf("external_primary" to 0L, "external_primary" to 0L), source.changedSinceCalls)
    }

    @Test fun reattachedVolumeIsReadInFull() {
        val sdPhoto = File(tmp.root, "storage/1234-ABCD/DCIM/sd.jpg")
        source.attached = listOf("external_primary", "1234-abcd")
        source.put(storeRow(sdPhoto, id = 30, volume = "1234-abcd"))
        sync()
        assertNotNull(row(sdPhoto))

        source.attached = listOf("external_primary") // card out: its ids leave MediaStore's live set
        sync()
        assertNull(row(sdPhoto))

        source.attached = listOf("external_primary", "1234-abcd") // card back, rows unchanged
        sync()
        assertEquals("1234-abcd" to 0L, source.changedSinceCalls.last())
        assertNotNull(row(sdPhoto))
    }

    @Test fun walkerRowBecomesMediaStoreRowWhenMediaStoreLearnsIt() {
        val late = file("Download/late.jpg")
        sync()
        assertNull(row(late)!!.mediaStoreId)

        source.put(storeRow(late, id = 21))
        sync()
        assertEquals(21L, row(late)!!.mediaStoreId)
        assertEquals(setOf(late.path), paths())
    }

    @Test fun renamedMediaStoreRowLeavesNoDuplicate() {
        val before = file("DCIM/Camera/a.jpg")
        source.put(storeRow(before, id = 5))
        sync()

        val after = File(root, "DCIM/Camera/b.jpg")
        assertTrue(before.renameTo(after))
        source.put(storeRow(after, id = 5)) // same id, new path
        sync()
        assertEquals(setOf(after.path), paths())
        assertEquals(5L, row(after)!!.mediaStoreId)
    }

    @Test fun largeLibraryIsWrittenAndRemovedAcrossBatches() {
        val n = 1_203
        (1..n).forEach { source.put(storeRow(File(root, "DCIM/Bulk/$it.jpg"), id = it.toLong())) }
        sync()
        assertEquals(n, visible())

        (1..n).forEach { source.remove(it.toLong()) }
        sync()
        assertEquals(0, visible())
    }

    @Test fun syncingIsTrueOnlyWhileSyncing() {
        var seen: Boolean? = null
        source.onRead = { seen = index.syncing.value }
        assertFalse(index.syncing.value)
        sync()
        assertEquals(true, seen)
        assertFalse(index.syncing.value)
    }

    // Final review I3: the All view says "No photos yet" only once the cold start's read and walk have both ended.
    @Test fun firstSyncLastsFromStartUntilTheColdStartsReadAndWalkHaveEnded() = runBlocking {
        val walking = java.util.concurrent.CountDownLatch(1)
        val walker = object : FsWalker(listOf(root)) {
            override fun walk(): Sequence<File> {
                walking.await()
                return super.walk()
            }
        }
        val index = MediaIndex(db, source, walker, testScope)
        assertFalse("not started", index.firstSync.value)
        runBlocking { index.syncMediaStore() }
        assertFalse("a sync before start is no first sync", index.firstSync.value)
        index.start()
        assertTrue(index.firstSync.value)
        waitFor { source.changedSinceCalls.size == 2 }
        assertTrue("the read alone isn't enough", index.firstSync.value)
        walking.countDown()
        waitFor { !index.firstSync.value }
        index.start()
        assertFalse("never again", index.firstSync.value)
    }

    @Test fun mediaStoreSyncNeverWalks() {
        val walker = CountingWalker(listOf(root))
        val index = MediaIndex(db, source, walker, testScope)
        val photo = file("DCIM/Camera/1.jpg")
        source.put(storeRow(photo, id = 1))
        runBlocking { index.syncMediaStore() }
        assertEquals(0, walker.walks.get())
        assertEquals(listOf("external_primary" to 0L), source.changedSinceCalls)
        assertEquals(1L, row(photo)!!.mediaStoreId)
    }

    @Test fun observerChangeSyncsMediaStoreButDoesNotWalk() = runBlocking {
        val walker = CountingWalker(listOf(root))
        val index = MediaIndex(db, source, walker, testScope)
        index.start()
        waitFor { walker.walks.get() == 1 && !index.syncing.value && source.changes.subscriptionCount.value > 0 }
        assertEquals(1, source.changedSinceCalls.size) // cold start: MediaStore, then one walk

        val photo = file("DCIM/Camera/new.jpg")
        source.put(storeRow(photo, id = 1))
        assertTrue(source.changes.tryEmit(Unit))
        waitFor { row(photo) != null && !index.syncing.value }
        assertEquals(2, source.changedSinceCalls.size)
        assertEquals(1, walker.walks.get())
    }

    @Test fun fullScanAloneFindsAWalkerOnlyFile() {
        val meme = file("Download/meme.png")
        runBlocking { index.fullScan() }
        assertNull(row(meme)!!.mediaStoreId)
        assertEquals(emptyList<Pair<String, Long>>(), source.changedSinceCalls)
    }

    @Test fun walkerRowWithChangedLengthIsRefreshed() {
        val f = file("Download/grows.jpg").apply { setLastModified(oct1) }
        runBlocking { index.fullScan() }
        assertEquals(3L, row(f)!!.size)

        f.appendBytes(ByteArray(10))
        f.setLastModified(oct1 + 60_000)
        runBlocking { index.fullScan() }
        val r = row(f)!!
        assertEquals(13L, r.size)
        assertEquals(oct1 + 60_000, r.dateModified)
    }

    @Test fun walkerRowsLeaveWhenTheWalkNoLongerSeesThem() {
        val gone = file("Download/gone.jpg")
        val kept = file("Download/kept.jpg")
        runBlocking { index.fullScan() }
        gone.delete()
        runBlocking { index.fullScan() }
        assertEquals(setOf(kept.path), paths())
    }

    @Test fun generationIsCommittedEvenIfTheFullScanThenFails() {
        val broken = object : FsWalker(listOf(root)) {
            override fun walk(): Sequence<File> = throw IllegalStateException("storage unreadable")
        }
        val index = MediaIndex(db, source, broken, testScope)
        source.put(storeRow(file("DCIM/Camera/1.jpg"), id = 1))
        assertThrows(IllegalStateException::class.java) { runBlocking { index.sync() } }
        assertEquals("1", storedGeneration("external_primary"))
    }

    @Test fun allIdsFailureKeepsTheStoredGenerationForTheNextSync() {
        source.put(storeRow(file("DCIM/Camera/1.jpg"), id = 1))
        source.put(storeRow(file("DCIM/Camera/2.jpg"), id = 2))
        runBlocking { index.syncMediaStore() }
        assertEquals("2", storedGeneration("external_primary"))

        source.put(storeRow(file("DCIM/Camera/3.jpg"), id = 3))
        source.failAllIds = true
        assertThrows(IllegalStateException::class.java) { runBlocking { index.syncMediaStore() } }
        assertEquals("2", storedGeneration("external_primary"))

        source.failAllIds = false
        runBlocking { index.syncMediaStore() }
        assertEquals("external_primary" to 2L, source.changedSinceCalls.last())
        assertEquals("3", storedGeneration("external_primary"))
    }

    @Test fun cardPulledMidSyncIsReadInFullWhenItReturns() {
        val sd = "1234-abcd"
        source.attached = listOf("external_primary", sd)
        val a = File(tmp.root, "storage/1234-ABCD/DCIM/a.jpg")
        source.put(storeRow(a, id = 30, volume = sd))
        runBlocking { index.syncMediaStore() }

        val b = File(tmp.root, "storage/1234-ABCD/DCIM/b.jpg")
        source.put(storeRow(b, id = 31, volume = sd))
        source.beforeAllIds = { source.attached = listOf("external_primary"); source.beforeAllIds = {} }
        runBlocking { index.syncMediaStore() } // snapshot saw the card; it was gone by commit time
        assertNull(storedGeneration(sd))
        assertNull(row(a))

        source.attached = listOf("external_primary", sd) // back, rows unchanged
        runBlocking { index.syncMediaStore() }
        assertEquals(sd to 0L, source.changedSinceCalls.last())
        assertNotNull(row(a))
        assertNotNull(row(b))
    }

    @Test fun nomediaAtAnSdCardRootDoesNotHideTheCard() {
        val sdRoot = tmp.newFolder("storage", "1234-ABCD")
        val index = MediaIndex(db, source, FsWalker(listOf(root, sdRoot)), testScope)
        File(sdRoot, ".nomedia").writeText("")
        val walked = File(sdRoot, "DCIM/w.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val stored = File(sdRoot, "DCIM/Camera/s.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        source.attached = listOf("external_primary", "1234-abcd")
        source.put(storeRow(stored, id = 60, volume = "1234-abcd"))
        File(sdRoot, "Stickers/.nomedia").apply { parentFile!!.mkdirs(); writeText("") }
        val sticker = File(sdRoot, "Stickers/st.webp").apply { writeBytes(byteArrayOf(1)) }
        runBlocking { index.sync() }

        assertFalse(row(walked)!!.hidden)
        assertFalse(row(stored)!!.hidden)
        assertTrue(row(sticker)!!.hidden)
        assertEquals("1234-abcd", row(walked)!!.volume)
    }

    @Test fun photoTakenMidWalkIsNotBlockedAndKeepsItsId() {
        val photo = file("DCIM/Camera/new.jpg")
        lateinit var index: MediaIndex
        val walker = object : FsWalker(listOf(root)) {
            override fun walk(): Sequence<File> = sequence {
                // The camera saves while the walk runs: the MediaStore sync must not wait for it.
                // Bounded, so a shared lock fails this test instead of deadlocking the run.
                source.put(storeRow(photo, id = 50))
                runBlocking { withTimeout(5_000) { index.syncMediaStore() } }
                yieldAll(super.walk())
            }
        }
        index = MediaIndex(db, source, walker, testScope)
        runBlocking { index.fullScan() }
        assertEquals(50L, row(photo)!!.mediaStoreId) // the walk's stale snapshot didn't overwrite it
    }

    private suspend fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            delay(20)
        }
    }
}
