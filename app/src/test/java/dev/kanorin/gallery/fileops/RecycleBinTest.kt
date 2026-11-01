package dev.kanorin.gallery.fileops

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.allMediaPaths
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FakeMediaStoreSource
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.index.MediaIndex
import dev.kanorin.gallery.index.StorageLayout
import dev.kanorin.gallery.logic.BinPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.IOException
import org.json.JSONObject
import java.util.Collections

/** Spec 5.5 on a real temp dir (internal storage + an SD card) and Room in memory. Data safety first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecycleBinTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var sd: File
    private lateinit var db: GalleryDb
    private val scanned: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_790_000_000_000L
    private var mounted: () -> List<File> = { listOf(root, sd) }

    private val day = BinPolicy.DAY_MS
    private val camera get() = File(root, "DCIM/Camera")
    private val binDir get() = File(root, StorageLayout.BIN_DIR_NAME)

    @Before fun setUp() {
        root = tmp.newFolder("storage", "emulated", "0")
        sd = tmp.newFolder("storage", "1A2B-3C4D")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun ops() = FileOps(db, { scanned += it }, roots = { mounted() }, storageRoot = root.path)

    private fun bin(deleter: (File) -> Boolean = File::delete) = RecycleBin(db, ops(), clock = { now }, deleter = deleter)

    private fun file(dir: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        File(dir, name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun row(f: File, video: Boolean = false, id: Long? = 5) = MediaEntity(
        path = f.path, folderPath = f.parent!!, name = f.name, mimeType = if (video) "video/mp4" else "image/jpeg",
        isVideo = video, dateTaken = 1_700_000_000_000L, dateModified = f.lastModified(), size = f.length(),
        width = 4000, height = 3000, durationMs = if (video) 61_000 else 0, orientation = 0, mediaStoreId = id,
        hidden = false, volume = "external_primary",
    )

    private fun indexed(dir: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3), video: Boolean = false): File =
        file(dir, name, bytes).also { runBlocking { db.mediaDao().upsert(listOf(row(it, video))) } }

    private fun sql(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun strings(sql: String, vararg args: Any?): List<String> =
        db.openHelper.readableDatabase.query(sql, args).use { c -> List(c.count) { c.moveToNext(); c.getString(0) } }

    private fun favourite(path: String) = sql("INSERT INTO favorites(path, addedAt) VALUES (?, 77)", path)
    private fun progress(path: String) = sql("INSERT INTO video_progress(path, positionMs, updatedAt) VALUES (?, 120000, 3)", path)
    private fun favourites() = strings("SELECT path FROM favorites")
    private fun progresses() = strings("SELECT path FROM video_progress")
    private fun binRows(): List<BinEntity> = runBlocking { db.binDao().all() }
    private fun shown(bin: RecycleBin): List<String> = runBlocking { bin.items().first() }.map { it.binPath }
    private fun sidecarOf(binPath: String) = File(File(binPath).parentFile, File(binPath).nameWithoutExtension + ".json")
    private fun ghostRow(name: String, original: File, deletedAt: Long = now): String {
        binDir.mkdirs()
        val path = File(binDir, name).path
        runBlocking { db.binDao().insert(BinEntity(path, original.path, deletedAt, 3, "image/jpeg", false, 0)) }
        return path
    }
    private fun mediaRow(path: String): MediaEntity? = runBlocking { db.mediaDao().byPath(path) }
    private fun allPaths(): Set<String> = db.allMediaPaths().toSet()

    // ---- Into the bin

    @Test fun binnedFileLeavesFolderAndIndex() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(9, 8, 7))
        val clip = indexed(camera, "clip.mp4", video = true)
        favourite(a.path)
        progress(clip.path)

        val result = bin().moveToBin(listOf(a.path, clip.path))

        assertEquals(listOf(a.path, clip.path), result.done)
        assertTrue(result.failed.isEmpty())
        assertFalse(a.exists())
        assertFalse(clip.exists())
        assertTrue(allPaths().isEmpty())
        assertTrue("the bin hides itself from other apps", File(binDir, ".nomedia").isFile)
        val (aBin, clipBin) = result.landed.map(::File)
        assertEquals(binDir, aBin.parentFile)
        assertTrue(aBin.name, aBin.name.matches(Regex("[0-9a-f-]{36}\\.jpg")))
        assertTrue(clipBin.name, clipBin.name.matches(Regex("[0-9a-f-]{36}\\.mp4")))
        assertArrayEquals(byteArrayOf(9, 8, 7), aBin.readBytes())
        assertEquals(
            setOf(
                BinEntity(aBin.path, a.path, now, 3, "image/jpeg", isVideo = false, durationMs = 0),
                BinEntity(clipBin.path, clip.path, now, 3, "video/mp4", isVideo = true, durationMs = 61_000),
            ),
            binRows().toSet(),
        )
        // Kept under the bin path, so a restore brings them back.
        assertEquals(listOf(aBin.path), favourites())
        assertEquals(listOf(clipBin.path), progresses())
        assertTrue(scanned.containsAll(listOf(a.path, aBin.path, clip.path, clipBin.path)))
    }

    @Test fun anSdCardFileGoesToTheCardsOwnBin() = runBlocking {
        val s = indexed(File(sd, "DCIM"), "s.jpg")
        val result = bin().moveToBin(listOf(s.path))
        assertEquals(listOf(s.path), result.done)
        assertEquals(File(sd, StorageLayout.BIN_DIR_NAME), File(result.landed.single()).parentFile)
        assertTrue(File(sd, "${StorageLayout.BIN_DIR_NAME}/.nomedia").isFile)
        assertFalse("nothing went to internal storage", binDir.exists())
    }

    @Test fun aFileTheIndexDoesntKnowIsBinnedWithFactsFromItsName() = runBlocking {
        val v = file(camera, "walk.MKV")
        bin().moveToBin(listOf(v.path))
        val entry = binRows().single()
        assertEquals("video/x-matroska", entry.mimeType)
        assertTrue(entry.isVideo)
        assertTrue(entry.binPath.endsWith(".MKV"))
    }

    @Test fun missingBlockedFoldersAndBinnedFilesAreRefusedOneByOne() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val blocked = file(File(root, "Android/data/com.x"), "b.jpg")
        val dir = File(camera, "sub.jpg").apply { mkdirs() }
        val first = bin().moveToBin(listOf(a.path))
        val inside = first.landed.single()

        val result = bin().moveToBin(listOf(File(camera, "gone.jpg").path, blocked.path, dir.path, inside))

        assertTrue(result.done.isEmpty())
        assertEquals(4, result.failed.size)
        assertTrue(blocked.isFile)
        assertTrue(dir.isDirectory)
        assertTrue(File(inside).isFile)
        assertEquals(1, binRows().size)
    }

    @Test fun aFailedIndexWriteLeavesTheFileWhereItWas() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val bin = bin().apply { beforeCommit = { throw IOException("database or disk is full") } }

        val result = bin.moveToBin(listOf(a.path))

        assertEquals(listOf(a.path), result.failed.map { it.first })
        assertTrue(a.isFile)
        assertFalse(binDir.exists() && binDir.list()!!.any { it != ".nomedia" })
        assertTrue(binRows().isEmpty())
        assertNotNull(mediaRow(a.path))
        assertEquals(listOf(a.path), favourites())
    }

    @Test fun aFailedRenameTakesTheRowsBack() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val bin = bin().apply { renamer = { _, _ -> false } }

        val result = bin.moveToBin(listOf(a.path))

        assertEquals(listOf(a.path), result.failed.map { it.first })
        assertTrue(a.isFile)
        assertTrue(binRows().isEmpty())
        assertEquals(row(a), mediaRow(a.path))
        assertEquals(listOf(a.path), favourites())
    }

    @Test fun theRowIsInBeforeTheFileMoves() = runBlocking {
        val a = indexed(camera, "a.jpg")
        var rowsAtRename = -1
        val bin = bin().apply {
            renamer = { from, to -> rowsAtRename = binRows().size; !to.exists() && from.renameTo(to) }
        }
        bin.moveToBin(listOf(a.path))
        assertEquals("a crash mid-move can't leave a binned file without its row", 1, rowsAtRename)
    }

    @Test fun progressCountsEachFile() = runBlocking {
        val files = List(3) { indexed(camera, "f$it.jpg") }
        val seen = mutableListOf<Pair<Int, Int>>()
        bin().moveToBin(files.map { it.path }) { i, n -> seen += i to n }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    // ---- Back out

    @Test fun restoreReturnsToOriginalPath() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(9, 8, 7))
        val clip = indexed(camera, "clip.mp4", video = true)
        favourite(a.path)
        progress(clip.path)
        val bin = bin()
        val landed = bin.moveToBin(listOf(a.path, clip.path)).landed

        val result = bin.restore(landed)

        assertEquals(landed, result.done)
        assertEquals(listOf(a.path, clip.path), result.landed)
        assertArrayEquals(byteArrayOf(9, 8, 7), a.readBytes())
        assertTrue(clip.isFile)
        landed.forEach { assertFalse(File(it).exists()) }
        assertTrue(binRows().isEmpty())
        assertEquals(listOf(a.path), favourites())
        assertEquals(listOf(clip.path), progresses())
        // Back in the grid at once, as the walk would have it.
        val back = mediaRow(a.path)!!
        assertEquals(camera.path, back.folderPath)
        assertNull(back.mediaStoreId)
        assertEquals(a.lastModified(), back.dateModified)
        assertEquals(3L, back.size)
        assertTrue(mediaRow(clip.path)!!.isVideo)
        assertTrue(scanned.containsAll(landed + listOf(a.path, clip.path)))
    }

    @Test fun restoreRecreatesMissingParent() = runBlocking {
        val trip = File(root, "Pictures/Trip 旅行 🌸")
        val x = indexed(trip, "x.jpg")
        val bin = bin()
        val landed = bin.moveToBin(listOf(x.path)).landed
        assertTrue(trip.delete())
        assertTrue(File(root, "Pictures").delete())

        val result = bin.restore(landed)

        assertEquals(landed, result.done)
        assertTrue(x.isFile)
        assertEquals(trip.path, mediaRow(x.path)!!.folderPath)
    }

    @Test fun restoreIntoATakenNameGetsASuffixAndLeavesTheNewFileAlone() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1))
        favourite(a.path)
        val bin = bin()
        val landed = bin.moveToBin(listOf(a.path)).landed
        val newer = indexed(camera, "a.jpg", byteArrayOf(2, 2))
        // A stale row counts as taken too, as in a move.
        sql("INSERT INTO media SELECT replace(path, 'a.jpg', 'a (1).jpg'), folderPath, 'a (1).jpg', mimeType, isVideo, dateTaken, " +
            "dateModified, size, width, height, durationMs, orientation, NULL, hidden, volume FROM media WHERE path = ?", newer.path)

        val result = bin.restore(landed)

        val restored = File(camera, "a (2).jpg")
        assertEquals(listOf(restored.path), result.landed)
        assertArrayEquals(byteArrayOf(1), restored.readBytes())
        assertArrayEquals(byteArrayOf(2, 2), newer.readBytes())
        assertEquals(listOf(restored.path), favourites())
    }

    @Test fun restoreFromAnSdCardThatIsOutFailsAndKeepsEverything() = runBlocking {
        val s = indexed(File(sd, "DCIM"), "s.jpg")
        val bin = bin()
        val landed = bin.moveToBin(listOf(s.path)).landed
        mounted = { listOf(root) }

        val result = bin.restore(landed)

        assertEquals(listOf(landed.single() to "The SD card isn't available"), result.failed)
        assertEquals(1, binRows().size)
        assertTrue(File(landed.single()).isFile)
        assertFalse(s.exists())
    }

    @Test fun aRestoreWhoseIndexWriteFailsPutsTheFileBackInTheBin() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val bin = bin()
        val landed = bin.moveToBin(listOf(a.path)).landed
        bin.beforeCommit = { throw IOException("database or disk is full") }

        val result = bin.restore(landed)

        assertEquals(landed, result.failed.map { it.first })
        assertFalse(a.exists())
        assertTrue(File(landed.single()).isFile)
        assertEquals(1, binRows().size)
        assertEquals(landed, favourites())
    }

    @Test fun restoreOfSomethingNotInTheBinMovesNothing() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val result = bin().restore(listOf(a.path))
        assertEquals(listOf(a.path), result.failed.map { it.first })
        assertTrue(a.isFile)
        assertNotNull(mediaRow(a.path))
    }

    // ---- Gone for good

    @Test fun deleteForeverRemovesTheFileAndEveryRow() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val b = indexed(camera, "b.jpg")
        favourite(a.path)
        val bin = bin()
        val landed = bin.moveToBin(listOf(a.path, b.path)).landed

        val result = bin.deleteForever(listOf(landed[0]))

        assertEquals(listOf(landed[0]), result.done)
        assertFalse(File(landed[0]).exists())
        assertTrue(File(landed[1]).isFile)
        assertEquals(listOf(landed[1]), binRows().map { it.binPath })
        assertTrue(favourites().isEmpty())
        assertTrue(landed[0] in scanned)
    }

    @Test fun deleteForeverOnlyDeletesFilesTheBinHolds() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val stray = file(binDir, "stray.jpg")
        val result = bin().deleteForever(listOf(a.path, stray.path))
        assertTrue(result.done.isEmpty())
        assertTrue(a.isFile)
        assertTrue(stray.isFile)
    }

    @Test fun deleteForeverKeepsTheRowOfACardThatIsOut() = runBlocking {
        val s = indexed(File(sd, "DCIM"), "s.jpg")
        val bin = bin()
        val landed = bin.moveToBin(listOf(s.path)).landed
        mounted = { listOf(root) }
        val result = bin.deleteForever(landed)
        assertEquals("The SD card isn't available", result.failed.single().second)
        assertEquals(1, binRows().size)
    }

    @Test fun deleteForeverOnBothVolumesTakesTheSidecarsToo() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val s = indexed(File(sd, "DCIM"), "s.jpg")
        val keep = indexed(camera, "keep.jpg")
        val bin = bin()
        val landed = bin.moveToBin(listOf(a.path, s.path)).landed

        val result = bin.deleteForever(landed)

        assertEquals(landed, result.done)
        landed.forEach {
            assertFalse(File(it).exists())
            assertFalse(sidecarOf(it).exists())
        }
        assertTrue(binRows().isEmpty())
        assertTrue(keep.isFile)
    }

    // ---- Ruling AB: a row whose file is gone is never a broken tile, and deleting it keeps the favourite

    @Test fun aGhostRowIsNotShownButARowOnACardThatIsOutIs() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val s = indexed(File(sd, "DCIM"), "s.jpg")
        val bin = bin()
        val (aBin, sBin) = bin.moveToBin(listOf(a.path, s.path)).landed
        val ghost = ghostRow("33333333-3333-3333-3333-333333333333.jpg", file(camera, "back.jpg"))
        assertEquals(setOf(aBin, sBin), shown(bin).toSet())

        // The card goes out: its file can't be seen, but the row stays so a restore can say why.
        mounted = { listOf(root) }
        assertTrue(File(sd, "DCIM").renameTo(File(sd, "DCIM-away")))
        assertTrue(File(sd, StorageLayout.BIN_DIR_NAME).renameTo(File(sd, "bin-away")))
        assertEquals(setOf(aBin, sBin), shown(bin).toSet())
        assertTrue(ghost in binRows().map { it.binPath })
    }

    @Test fun deleteForeverOnAGhostSettlesItSoTheFavouriteStaysWithTheFile() = runBlocking {
        val back = file(camera, "back.jpg")
        val ghost = ghostRow("44444444-4444-4444-4444-444444444444.jpg", back)
        favourite(ghost)
        progress(ghost)

        val result = bin().deleteForever(listOf(ghost))

        assertEquals(listOf(ghost), result.done)
        assertTrue(back.isFile)
        assertTrue(binRows().isEmpty())
        assertEquals(listOf(back.path), favourites())
        assertEquals(listOf(back.path), progresses())
    }

    @Test fun settleGhostsSettlesWithoutPurging() = runBlocking {
        val old = indexed(camera, "old.jpg")
        val bin = bin()
        val oldBin = bin.moveToBin(listOf(old.path)).landed.single()
        val back = file(camera, "back.jpg")
        val ghost = ghostRow("55555555-5555-5555-5555-555555555555.jpg", back)
        favourite(ghost)
        now += 31 * day

        bin.settleGhosts()

        assertEquals(listOf(oldBin), binRows().map { it.binPath })
        assertTrue("expired, but this pass only settles", File(oldBin).isFile)
        assertEquals(listOf(back.path), favourites())
    }

    // ---- Minor 4: only a file directly inside a mounted root's bin is ever deleted as a bin file

    @Test fun rowsThatPointOutsideABinNeverDeleteAnything() = runBlocking {
        val a = file(camera, "a.jpg")
        val b = file(File(root, "DCIM/${StorageLayout.BIN_DIR_NAME}"), "b.jpg")
        val dotted = File(root, "${StorageLayout.BIN_DIR_NAME}/../DCIM/Camera/a.jpg").path
        runBlocking {
            db.binDao().insert(BinEntity(dotted, File(camera, "x.jpg").path, now - 40 * day, 3, "image/jpeg", false, 0))
            db.binDao().insert(BinEntity(b.path, File(camera, "y.jpg").path, now - 40 * day, 3, "image/jpeg", false, 0))
        }
        val bin = bin()

        val result = bin.deleteForever(listOf(dotted, b.path))
        bin.purgeExpired(now)
        bin.restore(listOf(dotted, b.path))

        assertTrue(result.done.isEmpty())
        assertTrue(a.isFile)
        assertTrue(b.isFile)
        assertFalse(File(camera, "x.jpg").exists())
        assertFalse(File(camera, "y.jpg").exists())
    }

    // ---- Minor 8: a rename that reports false but happened is a rename

    @Test fun aRenameThatSaysNoButMovedTheFileCountsAsMoved() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val bin = bin().apply { renamer = { from, to -> from.renameTo(to); false } }

        val result = bin.moveToBin(listOf(a.path))

        assertEquals(listOf(a.path), result.done)
        val binned = result.landed.single()
        assertTrue(File(binned).isFile)
        assertEquals(listOf(binned), binRows().map { it.binPath })
        assertEquals(listOf(binned), favourites())
        assertNull(mediaRow(a.path))
    }

    // ---- Ruling AD: a remembered "Delete permanently" goes through the bin, hidden, until its UNDO expires

    @Test fun aPendingEraseIsHiddenAndTheNextPurgeFinishesItWhateverItsAge() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val bin = bin()
        val result = bin.moveToBin(listOf(a.path), pendingErase = true)
        val binned = result.landed.single()
        assertFalse(a.exists())
        assertTrue(binRows().single().pendingErase)
        assertTrue("not in the bin screen", shown(bin).isEmpty())

        // A crash before the toast ran out: the next purge erases it, though it's minutes old.
        bin.purgeExpired(now + 60_000)

        assertFalse(File(binned).exists())
        assertFalse(sidecarOf(binned).exists())
        assertTrue(binRows().isEmpty())
        assertTrue(favourites().isEmpty())
    }

    // Final review M1: a resume inside the UNDO toast's 3.5 s runs the daily purge; only a cold start (process death) erases.
    @Test fun aResumePurgeLeavesAHeldDeleteForItsUndo() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val bin = bin()
        val held = bin.moveToBin(listOf(a.path), pendingErase = true).landed.single()
        now += 2_000
        bin.scheduledPurge(coldStart = false) // never purged: due
        assertEquals("the daily purge ran", now.toString(), runBlocking { db.metaDao().get("bin.lastPurge") })
        assertTrue(File(held).isFile)
        assertEquals(listOf(held), binRows().map { it.binPath })
        assertEquals("UNDO still restores it", listOf(held), bin.restore(listOf(held)).done)
        assertTrue(a.isFile)

        val b = indexed(camera, "b.jpg")
        val heldB = bin.moveToBin(listOf(b.path), pendingErase = true).landed.single()
        bin.scheduledPurge(coldStart = true)
        assertFalse(File(heldB).exists())
        assertTrue(binRows().isEmpty())
    }

    @Test fun unholdTurnsAHeldDeleteIntoAnOrdinaryBinItemWithAFreshThirtyDays() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val b = indexed(camera, "b.jpg")
        val bin = bin()
        val held = bin.moveToBin(listOf(a.path), pendingErase = true).landed
        val ordinary = bin.moveToBin(listOf(b.path)).landed.single()
        val binnedAt = now
        now += 5 * day

        bin.unhold(held + ordinary)

        val rows = binRows().associateBy { it.binPath }
        assertFalse(rows.getValue(held.single()).pendingErase)
        assertEquals(now, rows.getValue(held.single()).deletedAt)
        assertEquals("an ordinary item keeps its own time", binnedAt, rows.getValue(ordinary).deletedAt)
        assertEquals(setOf(held.single(), ordinary), shown(bin).toSet())
        bin.purgeExpired(now)
        assertTrue(File(held.single()).isFile)
    }

    @Test fun aPendingEraseCanStillBeRestored() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val bin = bin()
        val landed = bin.moveToBin(listOf(a.path), pendingErase = true).landed
        assertEquals(landed, bin.restore(landed).done)
        assertTrue(a.isFile)
        assertTrue(binRows().isEmpty())
    }

    @Test fun deletePermanentlySkipsTheBin() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val clip = indexed(camera, "clip.mp4", video = true)
        favourite(a.path)
        progress(clip.path)

        val result = bin().deletePermanently(listOf(a.path, clip.path))

        assertEquals(listOf(a.path, clip.path), result.done)
        assertFalse(a.exists())
        assertFalse(clip.exists())
        assertFalse(binDir.exists())
        assertTrue(binRows().isEmpty())
        assertTrue(allPaths().isEmpty())
        assertTrue(favourites().isEmpty())
        assertTrue(progresses().isEmpty())
        assertTrue(scanned.containsAll(listOf(a.path, clip.path)))
    }

    @Test fun deletePermanentlyNeverTouchesAFolderOrAndroidData() = runBlocking {
        val dir = File(camera, "sub.jpg").apply { mkdirs() }
        File(dir, "inner.jpg").writeBytes(byteArrayOf(1))
        val blocked = file(File(root, "Android/data/com.x"), "b.jpg")
        val result = bin().deletePermanently(listOf(dir.path, blocked.path))
        assertEquals(2, result.failed.size)
        assertTrue(File(dir, "inner.jpg").isFile)
        assertTrue(blocked.isFile)
    }

    @Test fun aFileThatWontDeleteKeepsItsRows() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val result = bin(deleter = { false }).deletePermanently(listOf(a.path))
        assertEquals(listOf(a.path), result.failed.map { it.first })
        assertTrue(a.isFile)
        assertNotNull(mediaRow(a.path))
        assertEquals(listOf(a.path), favourites())
    }

    // ---- Emptying itself

    @Test fun purgeDeletesOnlyExpired() = runBlocking {
        val old = indexed(camera, "old.jpg")
        val young = indexed(camera, "young.jpg")
        favourite(old.path)
        favourite(young.path)
        val bin = bin()
        val oldBin = bin.moveToBin(listOf(old.path)).landed.single()
        now += 2 * day
        val youngBin = bin.moveToBin(listOf(young.path)).landed.single()

        bin.purgeExpired(now + 27 * day)
        assertEquals("29 days after the first: both kept", 2, binRows().size)

        bin.purgeExpired(now + 28 * day + 1)
        assertFalse(File(oldBin).exists())
        assertTrue(File(youngBin).isFile)
        assertEquals(listOf(youngBin), binRows().map { it.binPath })
        assertEquals(listOf(youngBin), favourites())
    }

    @Test fun purgeLeavesAnExpiredFileOnACardThatIsOut() = runBlocking {
        val s = indexed(File(sd, "DCIM"), "s.jpg")
        val bin = bin()
        val sBin = bin.moveToBin(listOf(s.path)).landed.single()
        mounted = { listOf(root) }
        bin.purgeExpired(now + 31 * day)
        assertEquals(1, binRows().size)

        mounted = { listOf(root, sd) }
        bin.purgeExpired(now + 31 * day)
        assertTrue(binRows().isEmpty())
        assertFalse(File(sBin).exists())
    }

    // ---- Ruling AC: the bin outlives the app's database (spec 5.5: uninstalling doesn't destroy it)

    @Test fun eachBinnedFileCarriesASidecarUntilItLeaves() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val bin = bin()
        val binned = bin.moveToBin(listOf(a.path)).landed.single()
        val note = JSONObject(sidecarOf(binned).readText())
        assertEquals(a.path, note.getString("originalPath"))
        assertEquals(now, note.getLong("deletedAt"))

        bin.restore(listOf(binned))
        assertFalse(sidecarOf(binned).exists())
    }

    @Test fun theSidecarIsWrittenBeforeTheFileMoves() = runBlocking {
        val a = indexed(camera, "a.jpg")
        var sidecarAtRename = false
        val bin = bin().apply { renamer = { from, to -> sidecarAtRename = sidecarOf(to.path).isFile; !to.exists() && from.renameTo(to) } }
        bin.moveToBin(listOf(a.path))
        assertTrue(sidecarAtRename)
    }

    @Test fun aFailedBinLeavesNoSidecar() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val bin = bin().apply { renamer = { _, _ -> false } }
        bin.moveToBin(listOf(a.path))
        assertEquals(listOf(".nomedia"), binDir.list()!!.toList())
    }

    @Test fun aBinFileWhoseRowWasLostIsAdoptedFromItsSidecarAndRestoresToItsFolder() = runBlocking {
        val trip = File(root, "Pictures/Trip")
        val a = indexed(trip, "a.jpg", byteArrayOf(9, 9))
        val bin = bin()
        val binned = bin.moveToBin(listOf(a.path)).landed.single()
        val binnedAt = now
        // The app's data was wiped (a reinstall): the bin folder is all that's left.
        sql("DELETE FROM bin")
        now += 3 * day

        bin.purgeExpired(now)

        val adopted = binRows().single()
        assertEquals(binned, adopted.binPath)
        assertEquals(a.path, adopted.originalPath)
        assertEquals(binnedAt, adopted.deletedAt)
        assertEquals(listOf(binned), shown(bin))
        assertTrue("its folder is gone too", trip.delete())
        assertEquals(listOf(binned), bin.restore(listOf(binned)).done)
        assertArrayEquals(byteArrayOf(9, 9), a.readBytes())
    }

    @Test fun aBinFileWithNoRowAndNoSidecarIsAdoptedIntoRestoredWithAFreshThirtyDays() = runBlocking {
        val name = "0f0e0d0c-0000-0000-0000-000000000000.jpg"
        // A rename keeps the photo's own mtime: years old, so it must not count.
        val orphan = file(binDir, name).apply { setLastModified(0) }
        val bin = bin()

        bin.purgeExpired(now)

        val adopted = binRows().single()
        assertEquals(orphan.path, adopted.binPath)
        assertEquals(File(root, "Pictures/Restored/$name").path, adopted.originalPath)
        assertEquals(now, adopted.deletedAt)
        assertEquals("30 days left", BinPolicy.daysLeftLabel(adopted.deletedAt, now))
        assertTrue(orphan.isFile)
        assertTrue("a sidecar now, so a second wipe keeps these values", sidecarOf(orphan.path).isFile)

        bin.purgeExpired(now + 29 * day)
        assertTrue(orphan.isFile)
        assertEquals(listOf(orphan.path), bin.restore(listOf(orphan.path)).done)
        assertTrue(File(root, "Pictures/Restored/$name").isFile)
    }

    @Test fun aSidecarWhoseFileIsGoneIsDeleted() = runBlocking {
        binDir.mkdirs()
        val nomedia = file(binDir, ".nomedia", ByteArray(0))
        val lone = File(binDir, "66666666-6666-6666-6666-666666666666.json").apply { writeText("{\"originalPath\":\"x\",\"deletedAt\":1}") }
        bin().purgeExpired(now)
        assertFalse(lone.exists())
        assertTrue(nomedia.exists())
        assertTrue(binRows().isEmpty())
    }

    @Test fun purgeSettlesARowWhoseFileNeverReachedTheBin() = runBlocking {
        // A crash between the row and the rename: the file is still where it was, its favourite under the bin path.
        val a = file(camera, "a.jpg")
        binDir.mkdirs()
        val ghost = File(binDir, "11111111-1111-1111-1111-111111111111.jpg").path
        runBlocking { db.binDao().insert(BinEntity(ghost, a.path, now, 3, "image/jpeg", false, 0)) }
        favourite(ghost)
        // One whose original is gone too: nothing left to keep.
        val lost = File(binDir, "22222222-2222-2222-2222-222222222222.jpg").path
        runBlocking { db.binDao().insert(BinEntity(lost, File(camera, "lost.jpg").path, now, 3, "image/jpeg", false, 0)) }
        progress(lost)

        bin().purgeExpired(now + day)

        assertTrue(binRows().isEmpty())
        assertTrue(a.isFile)
        assertEquals(listOf(a.path), favourites())
        assertTrue(progresses().isEmpty())
    }

    @Test fun aScheduledPurgeRunsOnEveryColdStartButOnlyDailyOnResume() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val bin = bin()
        val aBin = bin.moveToBin(listOf(a.path)).landed.single()
        now += 31 * day
        bin.scheduledPurge(coldStart = false) // never purged: due
        assertTrue(binRows().isEmpty())

        val b = indexed(camera, "b.jpg")
        val bBin = bin.moveToBin(listOf(b.path)).landed.single()
        now += 31 * day
        sql("INSERT OR REPLACE INTO meta(`key`, `value`) VALUES ('bin.lastPurge', ?)", (now - BinPolicy.PURGE_GAP_MS + 1).toString())
        bin.scheduledPurge(coldStart = false)
        assertTrue("purged less than a day ago", File(bBin).isFile)
        bin.scheduledPurge(coldStart = true)
        assertFalse(File(bBin).exists())
        assertFalse(File(aBin).exists())
    }

    // ---- Nobody else looks inside

    @Test fun binDirIsSkippedByWalker() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val keep = file(camera, "keep.jpg")
        val landed = bin().moveToBin(listOf(a.path)).landed.single()
        assertTrue(File(landed).isFile)
        assertEquals(listOf(keep.path), FsWalker(listOf(root)).walk().map { it.path }.toList())
        // Nor does a full index pass bring it back.
        MediaIndex(db, FakeMediaStoreSource(), FsWalker(listOf(root)), scope).sync()
        assertEquals(setOf(keep.path), allPaths())
    }
}
