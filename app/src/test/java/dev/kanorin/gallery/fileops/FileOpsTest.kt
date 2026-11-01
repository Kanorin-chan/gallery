package dev.kanorin.gallery.fileops

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.data.allMediaPaths
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.index.FakeMediaStoreSource
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.index.MediaIndex
import dev.kanorin.gallery.logic.NameError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.Collections

/** Spec 5.2 / 5.3 file ops on a real temp dir + Room in memory. Data safety first (Review Focus 6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var sd: File
    private lateinit var db: GalleryDb
    private val scanned: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Free space is faked by default so a nearly full test disk can't fail unrelated tests.
    private fun ops(
        copier: (File, File) -> Unit = ::copyBytes,
        deleter: (File) -> Boolean = File::delete,
        free: (File) -> Long? = { 1L shl 40 },
    ) = FileOps(
        db, { scanned += it }, roots = { listOf(root, sd) }, storageRoot = root.path,
        copier = copier, deleteSource = deleter, freeSpace = free,
    )

    private val camera get() = File(root, "DCIM/Camera")
    private val pictures get() = File(root, "Pictures")

    private fun dir(parent: File, name: String) = File(parent, name).apply { mkdirs() }

    private fun file(dir: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        File(dir, name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun row(f: File, id: Long? = 5, video: Boolean = false) = MediaEntity(
        path = f.path, folderPath = f.parent!!, name = f.name, mimeType = if (video) "video/mp4" else "image/jpeg",
        isVideo = video, dateTaken = 1_790_000_000_000L, dateModified = f.lastModified(), size = f.length(),
        width = 4000, height = 3000, durationMs = if (video) 61_000 else 0, orientation = 90, mediaStoreId = id,
        hidden = false, volume = "external_primary",
    )

    /** A file on disk with its index row. */
    private fun indexed(dir: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3), id: Long? = 5, video: Boolean = false): File =
        file(dir, name, bytes).also { runBlocking { db.mediaDao().upsert(listOf(row(it, id, video))) } }

    private fun sql(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, args)

    private fun strings(sql: String, vararg args: Any?): List<String> =
        db.openHelper.readableDatabase.query(sql, args).use { c -> List(c.count) { c.moveToNext(); c.getString(0) } }

    private fun longs(sql: String, vararg args: Any?): List<Long> =
        db.openHelper.readableDatabase.query(sql, args).use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } }

    private fun mediaRow(path: String): MediaEntity? = runBlocking { db.mediaDao().byPath(path) }
    private fun allPaths(): Set<String> = db.allMediaPaths().toSet()

    private fun favourite(path: String, at: Long = 77) = sql("INSERT INTO favorites(path, addedAt) VALUES (?, ?)", path, at)

    // ---- New folder

    @Test fun createFolderMakesRealDirAndRow() = runBlocking {
        val made = ops().createFolder(pictures, "  Trip KL ").getOrThrow()
        assertEquals(File(pictures, "Trip KL"), made)
        assertTrue(made.isDirectory)
        assertEquals(listOf(made.path), db.mediaDao().createdFolders().first())
        assertTrue(scanned.isEmpty())
    }

    @Test fun createFolderRefusesNamesTakenOnDiskOrByAnEarlierCreatedFolder() = runBlocking {
        dir(pictures, "Trip")
        file(pictures, "notes")
        ops().createFolder(pictures, "Mine").getOrThrow()
        for (name in listOf("trip", "Notes", "MINE")) {
            val e = ops().createFolder(pictures, name).exceptionOrNull()
            assertEquals(name, NameError.DUPLICATE, (e as FileOps.NameException).error)
        }
        assertEquals(listOf(File(pictures, "Mine").path), db.mediaDao().createdFolders().first())
    }

    // Final review I1: a folder this app made, then deleted outside it, must not block its own name for ever.
    @Test fun aCreatedFolderDeletedOnDiskCanBeCreatedAgain() = runBlocking {
        val first = ops().createFolder(pictures, "Trip").getOrThrow()
        assertTrue(first.delete())
        val again = ops().createFolder(pictures, "trip").getOrThrow()
        assertTrue(again.isDirectory)
        assertEquals(listOf(again.path), db.mediaDao().createdFolders().first())
    }

    @Test fun createFolderRefusesIllegalNamesAndMakesNothing() = runBlocking {
        pictures.mkdirs()
        assertEquals(NameError.ILLEGAL, (ops().createFolder(pictures, "a/b").exceptionOrNull() as FileOps.NameException).error)
        assertEquals(NameError.ILLEGAL, (ops().createFolder(pictures, "..").exceptionOrNull() as FileOps.NameException).error)
        assertEquals(NameError.EMPTY, (ops().createFolder(pictures, " ").exceptionOrNull() as FileOps.NameException).error)
        assertTrue(pictures.list()!!.isEmpty())
        assertTrue(db.mediaDao().createdFolders().first().isEmpty())
    }

    // Round 2 (G): a prune landing between the folder's rename on disk and the index rewrite can't lose its rows.
    @Test fun aRenamedEmptyCreatedFolderKeepsItsRowsEvenIfAPruneLandsMidRename() = runBlocking {
        val ops = ops()
        val trip = ops.createFolder(pictures, "Trip").getOrThrow()
        val day = dir(trip, "Day 1")
        sql("INSERT INTO created_folders(path) VALUES (?)", day.path)
        ops.dirRenamer = { from, to -> from.renameTo(to).also { runBlocking { ops.siblingNames(pictures) } } }
        val holiday = ops.renameFolder(trip.path, "Holiday").getOrThrow()
        assertEquals(setOf(holiday.path, File(holiday, "Day 1").path), strings("SELECT path FROM created_folders").toSet())
    }

    @Test fun siblingNamesAreDiskPlusCreatedFoldersOfThatParentOnly() = runBlocking {
        dir(pictures, "Trip")
        file(pictures, "a.jpg")
        dir(camera, "Elsewhere")
        sql("INSERT INTO created_folders(path) VALUES (?)", File(pictures, "Trip").path)
        sql("INSERT INTO created_folders(path) VALUES (?)", File(camera, "Elsewhere").path)
        assertEquals(setOf("Trip", "a.jpg"), ops().siblingNames(pictures))
        assertEquals(setOf("a.jpg"), ops().siblingNames(pictures, except = File(pictures, "Trip")))
    }

    @Test fun siblingNamesForgetCreatedFoldersGoneFromDisk() = runBlocking {
        dir(pictures, "Kept")
        sql("INSERT INTO created_folders(path) VALUES (?)", File(pictures, "Kept").path)
        sql("INSERT INTO created_folders(path) VALUES (?)", File(pictures, "Ghost").path)
        sql("INSERT INTO created_folders(path) VALUES (?)", File(camera, "Elsewhere").path)
        assertEquals(setOf("Kept"), ops().siblingNames(pictures))
        assertEquals(listOf(File(pictures, "Kept").path), strings("SELECT path FROM created_folders"))
    }

    // Final review: every mounted root is refused, not just internal storage's.
    @Test fun anSdCardRootCannotBeRenamed() = runBlocking {
        indexed(sd, "top.jpg")
        val e = ops().renameFolder(sd.path, "Cards").exceptionOrNull()
        assertEquals("This folder can't be renamed", e?.message)
        assertTrue(sd.isDirectory)
    }

    // ---- Move

    @Test fun moveUpdatesFavoritesAndMediaRows() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(9, 8, 7))
        val clip = indexed(camera, "clip.mp4", id = 6, video = true)
        favourite(a.path)
        sql("INSERT INTO video_progress(path, positionMs, updatedAt) VALUES (?, 120000, 3)", clip.path)
        val trip = dir(pictures, "Trip")

        val result = ops().move(listOf(a.path, clip.path), trip)

        assertEquals(listOf(a.path, clip.path), result.done)
        assertTrue(result.failed.isEmpty())
        val movedA = File(trip, "a.jpg")
        assertFalse(a.exists())
        assertArrayEquals(byteArrayOf(9, 8, 7), movedA.readBytes())
        assertEquals(setOf(movedA.path, File(trip, "clip.mp4").path), allPaths())
        val r = mediaRow(movedA.path)!!
        assertEquals(trip.path, r.folderPath)
        assertEquals("a.jpg", r.name)
        assertNull("MediaStore re-learns the moved file on its own", r.mediaStoreId)
        assertEquals(1_790_000_000_000L, r.dateTaken)
        assertEquals(90, r.orientation)
        assertEquals(movedA.lastModified(), r.dateModified)
        assertEquals(3L, r.size)
        assertEquals(listOf(movedA.path), strings("SELECT path FROM favorites"))
        assertEquals(listOf(77L), longs("SELECT addedAt FROM favorites"))
        assertEquals(listOf(File(trip, "clip.mp4").path), strings("SELECT path FROM video_progress"))
        assertTrue(scanned.containsAll(listOf(a.path, movedA.path, clip.path, File(trip, "clip.mp4").path)))
    }

    @Test fun moveNameClashGetsSuffix() = runBlocking {
        val trip = dir(pictures, "Trip")
        val existing = indexed(trip, "a.jpg", byteArrayOf(1), id = 1)
        val first = indexed(camera, "a.jpg", byteArrayOf(2), id = 2)
        val second = indexed(File(root, "Download"), "a.jpg", byteArrayOf(3), id = 3)

        val result = ops().move(listOf(first.path, second.path), trip)

        assertEquals(2, result.done.size)
        assertArrayEquals("never overwritten", byteArrayOf(1), existing.readBytes())
        assertArrayEquals(byteArrayOf(2), File(trip, "a (1).jpg").readBytes())
        assertArrayEquals(byteArrayOf(3), File(trip, "a (2).jpg").readBytes())
        assertEquals(setOf(existing.path, File(trip, "a (1).jpg").path, File(trip, "a (2).jpg").path), allPaths())
        assertEquals("a (2).jpg", mediaRow(File(trip, "a (2).jpg").path)!!.name)
    }

    @Test fun aStaleRowInTheDestinationCountsAsTaken() = runBlocking {
        val trip = dir(pictures, "Trip")
        // Indexed, then deleted by another app; the index hasn't caught up.
        val ghost = File(trip, "a.jpg")
        runBlocking { db.mediaDao().upsert(listOf(row(ghost, id = 1).copy(size = 1))) }
        favourite(ghost.path, at = 1)
        val a = indexed(camera, "a.jpg", id = 2)
        favourite(a.path, at = 2)

        ops().move(listOf(a.path), trip)

        assertTrue(File(trip, "a (1).jpg").exists())
        assertEquals(setOf(ghost.path, File(trip, "a (1).jpg").path), strings("SELECT path FROM favorites").toSet())
    }

    @Test fun crossVolumeMoveDeletesSourceOnlyAfterVerifiedCopy() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3, 4, 5))
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        // A copy that comes up short: the disk filled, or the card was pulled.
        val short: (File, File) -> Unit = { _, to -> to.writeBytes(byteArrayOf(1, 2)) }

        val result = ops(short).move(listOf(a.path), dest)

        assertTrue(result.done.isEmpty())
        assertEquals(a.path, result.failed.single().first)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), a.readBytes())
        assertTrue("no copy and no temp file left", dest.list()!!.isEmpty())
        assertEquals(setOf(a.path), allPaths())
        assertEquals(5L, mediaRow(a.path)!!.mediaStoreId)
        assertEquals(listOf(a.path), strings("SELECT path FROM favorites"))
    }

    @Test fun aCopierThatThrowsLeavesTheSourceAndNoTempFile() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3, 4, 5))
        val dest = dir(sd, "Pictures")
        val full: (File, File) -> Unit = { _, to -> to.writeBytes(byteArrayOf(1)); throw IOException("ENOSPC (No space left on device)") }

        val result = ops(full).move(listOf(a.path), dest)

        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.exists())
        assertTrue(dest.list()!!.isEmpty())
    }

    @Test fun crossVolumeMoveCopiesVerifiesThenDeletesTheSource() = runBlocking {
        val a = indexed(camera, "a.jpg", ByteArray(200_000) { it.toByte() })
        a.setLastModified(1_700_000_000_000L)
        val dest = dir(sd, "Pictures")
        var copies = 0

        val result = ops(copier = { from, to -> copies++; copyBytes(from, to) }).move(listOf(a.path), dest)

        assertEquals(listOf(a.path), result.done)
        assertEquals("across volumes it's a copy, never a rename", 1, copies)
        assertFalse(a.exists())
        val moved = File(dest, "a.jpg")
        assertEquals(200_000L, moved.length())
        assertEquals("the photo keeps its date", 1_700_000_000_000L, moved.lastModified())
        assertEquals(listOf("a.jpg"), dest.list()!!.toList())
        val r = mediaRow(moved.path)!!
        assertEquals("1a2b-3c4d", r.volume)
        assertEquals(dest.path, r.folderPath)
    }

    // Ruling Z: a stale temp may be the only copy (the dir fsync is best effort), so it becomes visible, never deleted.
    @Test fun staleTempsBecomeVisibleRecoveredFiles() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        val dest = dir(sd, "Pictures")
        val hourAgo = System.currentTimeMillis() - 2 * 3_600_000L
        file(dest, ".gallery-tmp-0badf00d.jpg", byteArrayOf(9, 9)).setLastModified(hourAgo)
        file(dest, "Recovered-feedf00d.mp4", byteArrayOf(5))
        file(dest, ".gallery-tmp-feedf00d.mp4", byteArrayOf(6)).setLastModified(hourAgo)
        val fresh = file(dest, ".gallery-tmp-12345678.jpg", byteArrayOf(42))
        // A case-only rename's temp is a folder holding the user's files: never touched here.
        val folder = dir(dest, ".gallery-tmp-cafecafe").apply { setLastModified(hourAgo) }

        ops().move(listOf(a.path), dest)

        assertArrayEquals(byteArrayOf(9, 9), File(dest, "Recovered-0badf00d.jpg").readBytes())
        assertArrayEquals("never over an existing file", byteArrayOf(5), File(dest, "Recovered-feedf00d.mp4").readBytes())
        assertArrayEquals(byteArrayOf(6), File(dest, "Recovered-feedf00d (1).mp4").readBytes())
        assertArrayEquals(byteArrayOf(42), fresh.readBytes())
        assertTrue(folder.isDirectory)
        assertEquals(
            setOf("Recovered-0badf00d.jpg", "Recovered-feedf00d.mp4", "Recovered-feedf00d (1).mp4", ".gallery-tmp-12345678.jpg", ".gallery-tmp-cafecafe", "a.jpg"),
            dest.list()!!.toSet(),
        )
        assertTrue(File(dest, "Recovered-0badf00d.jpg").path in scanned)
    }

    @Test fun theTempKeepsTheExtension() = runBlocking {
        val a = indexed(camera, "a.HEIC", byteArrayOf(1))
        val names = mutableListOf<String>()
        ops(copier = { from, to -> names += to.name; copyBytes(from, to) }).move(listOf(a.path), dir(sd, "Pictures"))
        assertTrue(names.single(), Regex("""\.gallery-tmp-[0-9a-f]{8}\.HEIC""").matches(names.single()))
    }

    @Test fun aFavouriteThatCantFollowAfterTheSourceVanishedKeepsTheCopy() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        val ops = ops(copier = { from, to -> copyBytes(from, to); from.delete() }).apply {
            beforeFileCommit = { throw IOException("database or disk is full") }
        }

        val result = ops.move(listOf(a.path), dest)

        assertEquals(listOf(a.path), result.done)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dest, "a.jpg").readBytes())
        // The batch's own favourite update is the safety net.
        assertEquals(listOf(File(dest, "a.jpg").path), strings("SELECT path FROM favorites"))
    }

    // Review r3: the source vanished mid-copy and the final rename failed; the temp is then the only complete copy.
    @Test fun aTempWhoseSourceVanishedIsKeptWhenTheCopyCantBeNamed() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        val dest = dir(sd, "Pictures")
        val intruder: (File, File) -> Unit = { from, to ->
            copyBytes(from, to)
            from.delete()
            File(to.parentFile, "a.jpg").writeBytes(byteArrayOf(7))
        }

        val result = ops(copier = intruder).move(listOf(a.path), dest)

        val (path, why) = result.failed.single()
        assertEquals(a.path, path)
        val temp = dest.listFiles()!!.single { it.name.startsWith(".gallery-tmp-") }
        assertEquals("Couldn't finish — a copy was kept as ${temp.name}", why)
        assertArrayEquals(byteArrayOf(1, 2, 3), temp.readBytes())
        assertArrayEquals("the other file is untouched", byteArrayOf(7), File(dest, "a.jpg").readBytes())

        // Ruling Z later makes it a visible file.
        temp.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L)
        ops().move(emptyList(), dest)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dest, "Recovered-" + temp.name.removePrefix(".gallery-tmp-")).readBytes())
    }

    @Test fun aTempIsStillDeletedWhenItsSourceIsIntact() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        val dest = dir(sd, "Pictures")
        val clash: (File, File) -> Unit = { from, to -> copyBytes(from, to); File(to.parentFile, "a.jpg").writeBytes(byteArrayOf(7)) }

        val result = ops(copier = clash).move(listOf(a.path), dest)

        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.isFile)
        assertEquals(listOf("a.jpg"), dest.list()!!.toList())
    }

    @Test fun aFavouriteThatCantGoBackToAnIntactSourceIsCountedAsLag() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        var carries = 0
        val ops = FileOps(
            db, { scanned += it }, roots = { listOf(root, sd) }, storageRoot = root.path, freeSpace = { 1L shl 40 },
            syncDir = { File(it, "a.jpg").writeBytes(byteArrayOf(1)) },
        ).apply { beforeFileCommit = { if (++carries == 2) throw IOException("database or disk is full") } }

        val result = ops.move(listOf(a.path), dest)

        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.isFile)
        assertEquals(1, result.indexLag)
    }

    @Test fun aRenameThatCantBeUndoneLeavesTheIndexWhereTheFolderIs() = runBlocking {
        val trip = dir(pictures, "Trip")
        val a = indexed(trip, "a.jpg")
        favourite(a.path)
        var writes = 0
        val ops = ops().apply {
            // The rename's index write fails once; renaming back to "Trip" fails too.
            beforeBatchCommit = { if (++writes == 1) throw IOException("database or disk is full") }
            dirRenamer = { from, to -> to.name != "Trip" && from.renameTo(to) }
        }

        val e = ops.renameFolder(trip.path, "Holiday").exceptionOrNull()

        val holiday = File(pictures, "Holiday")
        assertEquals(holiday, (e as FileOps.RenameStuck).at)
        assertEquals("Rename got stuck — the folder is at Holiday", e.message)
        assertTrue(File(holiday, "a.jpg").isFile)
        assertEquals(setOf(File(holiday, "a.jpg").path), allPaths())
        assertEquals(listOf(File(holiday, "a.jpg").path), strings("SELECT path FROM favorites"))
    }

    @Test fun aCopyThatChangesBeforeTheSourceGoesKeepsTheSource() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        val ops = FileOps(
            db, { scanned += it }, roots = { listOf(root, sd) }, storageRoot = root.path, freeSpace = { 1L shl 40 },
            syncDir = { File(it, "a.jpg").writeBytes(byteArrayOf(1)) },
        )

        val result = ops.move(listOf(a.path), dest)

        assertEquals(a.path, result.failed.single().first)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.readBytes())
        assertEquals("the favourite went back with the file that's intact", listOf(a.path), strings("SELECT path FROM favorites"))
    }

    @Test fun aSourceThatVanishesWhileItsFavouriteGoesBackKeepsTheCopy() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        var carries = 0
        val ops = ops(deleter = { false }).apply {
            // 1: favourite to the copy; 2: back to the source, which another app deletes right then.
            beforeFileCommit = { if (++carries == 2) a.delete() }
        }

        val result = ops.move(listOf(a.path), dest)

        assertEquals(listOf(a.path), result.done)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dest, "a.jpg").readBytes())
        assertEquals(listOf(File(dest, "a.jpg").path), strings("SELECT path FROM favorites"))
    }

    @Test fun aFailedFavouriteLookupStillMovesAndFlagsTheLag() = runBlocking {
        val a = indexed(camera, "a.jpg", id = 1)
        favourite(a.path)
        val trip = dir(pictures, "Trip")
        val ops = ops().apply { beforeQuery = { if (it == "trackedPaths") throw IOException("database disk image is malformed") } }

        val result = ops.move(listOf(a.path), trip)

        assertEquals(listOf(a.path), result.done)
        assertEquals(1, result.indexLag)
        assertTrue(File(trip, "a.jpg").isFile)
    }

    @Test fun aFailedStartReportsEveryFileAndTouchesNothing() = runBlocking {
        val a = indexed(camera, "a.jpg", id = 1)
        val b = indexed(camera, "b.jpg", id = 2)
        val trip = dir(pictures, "Trip")
        val ops = ops().apply { beforeQuery = { if (it == "mediaPathsIn") throw IOException("database disk image is malformed") } }

        val result = ops.move(listOf(a.path, b.path), trip)

        assertEquals(listOf(a.path to "Couldn't start the move", b.path to "Couldn't start the move"), result.failed)
        assertTrue(a.isFile && b.isFile)
        assertTrue(trip.list()!!.isEmpty())
    }

    @Test fun anUnreadableVolumeDoesntBlockTheCopyButAFullOneDoes() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        val b = indexed(camera, "b.jpg", byteArrayOf(4))
        val dest = dir(sd, "Pictures")
        assertEquals(listOf(a.path), ops(free = { null }).move(listOf(a.path), dest).done)
        assertEquals(listOf(b.path to "Not enough space"), ops(free = { 0L }).move(listOf(b.path), dest).failed)
    }

    @Test fun freeBytesIsUnknownOnlyWhenTheVolumeReportsNothing() {
        assertNull("0 free of 0 total: can't be read", freeBytes(File(tmp.root, "no/such/dir")))
        assertTrue(freeBytes(root)!! > 0)
    }

    @Test fun aStuckCaseOnlyRenameLeavesAVisibleFolderAndSaysWhere() = runBlocking {
        val trip = dir(pictures, "Trip")
        val a = indexed(trip, "a.jpg")
        favourite(a.path)
        // Into the temp works; out of it, to either name, doesn't.
        val ops = ops().apply { dirRenamer = { from, to -> to.name != "TRIP" && to.name != "Trip" && from.renameTo(to) } }

        val e = ops.renameFolder(trip.path, "TRIP").exceptionOrNull()

        val stuck = (e as FileOps.RenameStuck).at
        assertEquals("Trip (recovering)", stuck.name)
        assertTrue(File(stuck, "a.jpg").isFile)
        assertEquals(listOf("Trip (recovering)"), pictures.list()!!.toList())
        assertEquals("the index follows it there", setOf(File(stuck, "a.jpg").path), allPaths())
        assertEquals(listOf(File(stuck, "a.jpg").path), strings("SELECT path FROM favorites"))
    }

    @Test fun theTempNameIsShortSoANameNearTheLimitStillMoves() = runBlocking {
        val long = "x".repeat(246) + ".jpg"
        val a = indexed(camera, long, byteArrayOf(5, 6))
        val dest = dir(sd, "Pictures")

        val result = ops().move(listOf(a.path), dest)

        assertEquals(listOf(a.path), result.done)
        assertArrayEquals(byteArrayOf(5, 6), File(dest, long).readBytes())
    }

    @Test fun aSourceAnotherAppDeletedMidCopyKeepsTheCopy() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        val dest = dir(sd, "Pictures")
        // The open fd kept reading; the copy is now the only one.
        val result = ops(copier = { from, to -> copyBytes(from, to); from.delete() }).move(listOf(a.path), dest)

        assertEquals(listOf(a.path), result.done)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dest, "a.jpg").readBytes())
        assertEquals(setOf(File(dest, "a.jpg").path), allPaths())
    }

    @Test fun aSourceThatWontGoDropsTheCopyAndKeepsItsFavourite() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        favourite(a.path)
        val dest = dir(sd, "Pictures")

        val result = ops(deleter = { false }).move(listOf(a.path), dest)

        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.isFile)
        assertTrue(dest.list()!!.isEmpty())
        assertEquals(listOf(a.path), strings("SELECT path FROM favorites"))
        assertEquals(setOf(a.path), allPaths())
    }

    @Test fun aCopyThatWouldLeaveUnder200MbFreeIsRefused() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(1, 2, 3))
        val dest = dir(sd, "Pictures")

        val refused = ops(free = { FileOps.FREE_SPACE_MARGIN + 2 }).move(listOf(a.path), dest)

        assertEquals(listOf(a.path to "Not enough space"), refused.failed)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.readBytes())
        assertTrue(dest.list()!!.isEmpty())
        assertEquals(listOf(a.path), ops(free = { FileOps.FREE_SPACE_MARGIN + 3 }).move(listOf(a.path), dest).done)
    }

    @Test fun aFailedCommitKeepsFavouritesWithTheirFilesAndSaysSo() = runBlocking {
        val a = indexed(camera, "a.jpg", id = 1)
        val b = indexed(camera, "b.jpg", id = 2)
        val c = indexed(camera, "c.mp4", id = 3, video = true)
        favourite(a.path)
        sql("INSERT INTO video_progress(path, positionMs, updatedAt) VALUES (?, 9, 9)", c.path)
        val trip = dir(pictures, "Trip")
        val ops = ops().apply { beforeBatchCommit = { throw IOException("database or disk is full") } }

        val result = ops.move(listOf(a.path, b.path, c.path), trip)

        // a and c: the file and its favourite / progress moved together; only the index row lags.
        assertEquals(listOf(a.path, c.path), result.done)
        assertEquals(2, result.indexLag)
        assertEquals(listOf(File(trip, "a.jpg").path), strings("SELECT path FROM favorites"))
        assertEquals(listOf(File(trip, "c.mp4").path), strings("SELECT path FROM video_progress"))
        // b had nothing to keep: back where its row says it is.
        assertEquals(b.path, result.failed.single().first)
        assertTrue(b.isFile)
        assertFalse(File(trip, "b.jpg").exists())
    }

    @Test fun aFailedCommitAcrossVolumesKeepsTheFavouriteWithTheCopy() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        val ops = ops().apply { beforeBatchCommit = { throw IOException("database or disk is full") } }

        val result = ops.move(listOf(a.path), dest)

        assertEquals(listOf(a.path), result.done)
        assertEquals(1, result.indexLag)
        assertFalse(a.exists())
        assertEquals(listOf(File(dest, "a.jpg").path), strings("SELECT path FROM favorites"))
    }

    @Test fun aFavouriteThatCantFollowKeepsTheSourceAcrossVolumes() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val dest = dir(sd, "Pictures")
        val ops = ops().apply { beforeFileCommit = { throw IOException("database or disk is full") } }

        val result = ops.move(listOf(a.path), dest)

        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.isFile)
        assertTrue(dest.list()!!.isEmpty())
        assertEquals(listOf(a.path), strings("SELECT path FROM favorites"))
    }

    @Test fun aFavouriteThatCantFollowPutsARenamedFileBack() = runBlocking {
        val a = indexed(camera, "a.jpg")
        favourite(a.path)
        val trip = dir(pictures, "Trip")
        val ops = ops().apply { beforeFileCommit = { throw IOException("database or disk is full") } }

        val result = ops.move(listOf(a.path), trip)

        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.isFile)
        assertTrue(trip.list()!!.isEmpty())
        assertEquals(listOf(a.path), strings("SELECT path FROM favorites"))
    }

    @Test fun favouritesFollowEachFileBeforeTheBatchCommits() = runBlocking {
        val files = List(3) { indexed(camera, "$it.jpg", id = it + 1L).also { f -> favourite(f.path, at = it.toLong()) } }
        val trip = dir(pictures, "Trip")
        val seen = mutableListOf<List<String>>()
        val ops = ops().apply { beforeBatchCommit = { seen += strings("SELECT path FROM favorites ORDER BY addedAt") } }

        ops.move(files.map { it.path }, trip)

        // When the batch commits, every favourite already points at the moved file.
        assertEquals(listOf(files.map { File(trip, it.name).path }), seen)
    }

    @Test fun movedRowsTakeTheDestinationsHiddenFlag() = runBlocking {
        val a = indexed(camera, "a.jpg", id = 1)
        val b = indexed(camera, "b.jpg", id = 2)
        val secret = dir(pictures, "Secret").also { file(it, ".nomedia") }
        val shown = dir(pictures, "Shown").also { file(it, ".nomedia") }
        db.mediaDao().setHiddenOverride(shown.path, false)

        ops().move(listOf(a.path), secret)
        ops().move(listOf(b.path), shown)

        assertTrue(mediaRow(File(secret, "a.jpg").path)!!.hidden)
        assertFalse("the user's Unhide wins over .nomedia", mediaRow(File(shown, "b.jpg").path)!!.hidden)
    }

    @Test fun moveIntoTheSameFolderChangesNothing() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val result = ops().move(listOf(a.path), camera)
        assertEquals(listOf(a.path), result.done)
        assertEquals(listOf("a.jpg"), camera.list()!!.toList())
        assertEquals(5L, mediaRow(a.path)!!.mediaStoreId)
    }

    @Test fun aMissingSourceFailsAndTheRestStillMove() = runBlocking {
        val gone = File(camera, "gone.jpg")
        runBlocking { db.mediaDao().upsert(listOf(row(gone, id = 1).copy(size = 3))) }
        val b = indexed(camera, "b.jpg", id = 2)
        val trip = dir(pictures, "Trip")

        val result = ops().move(listOf(gone.path, b.path), trip)

        assertEquals(listOf(b.path), result.done)
        assertEquals(gone.path, result.failed.single().first)
        assertTrue(File(trip, "b.jpg").exists())
    }

    @Test fun androidDataIsNeverWritten() = runBlocking {
        val a = indexed(camera, "a.jpg")
        val blocked = dir(root, "Android/data/org.telegram.messenger")
        val result = ops().move(listOf(a.path), blocked)
        assertEquals(a.path, result.failed.single().first)
        assertTrue(a.exists())
        assertTrue(blocked.list()!!.isEmpty())
        assertTrue(ops().createFolder(File(root, "Android/obb"), "x").isFailure)
        assertFalse(File(root, "Android/obb/x").exists())
    }

    @Test fun progressIsReportedPerFile() = runBlocking {
        val files = List(3) { indexed(camera, "$it.jpg", id = it + 1L) }
        val seen = mutableListOf<Pair<Int, Int>>()
        ops().move(files.map { it.path }, dir(pictures, "Trip")) { i, n -> seen += i to n }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test fun aCancelledMoveStillRecordsTheFilesItAlreadyMoved() = runBlocking {
        val a = indexed(camera, "a.jpg", id = 1)
        val b = indexed(camera, "b.jpg", id = 2)
        favourite(a.path)
        val trip = dir(pictures, "Trip")
        lateinit var job: Job
        job = scope.launch { ops().move(listOf(a.path, b.path), trip) { i, _ -> if (i == 2) job.cancel() } }
        job.join()

        assertTrue(File(trip, "a.jpg").exists())
        assertTrue(b.exists())
        assertEquals(setOf(File(trip, "a.jpg").path, b.path), allPaths())
        assertEquals(listOf(File(trip, "a.jpg").path), strings("SELECT path FROM favorites"))
    }

    // ---- Copy

    @Test fun copyKeepsTheSourceAndAddsARowForTheCopy() = runBlocking {
        val a = indexed(camera, "a.jpg", byteArrayOf(4, 5, 6))
        favourite(a.path)
        val trip = dir(pictures, "Trip")

        val result = ops().copy(listOf(a.path), trip)

        assertEquals(listOf(a.path), result.done)
        val copy = File(trip, "a.jpg")
        assertArrayEquals(byteArrayOf(4, 5, 6), a.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), copy.readBytes())
        assertEquals(5L, mediaRow(a.path)!!.mediaStoreId)
        val r = mediaRow(copy.path)!!
        assertNull(r.mediaStoreId)
        assertEquals(trip.path, r.folderPath)
        assertEquals("a copy is a new file, not a favourite", listOf(a.path), strings("SELECT path FROM favorites"))
        assertTrue(copy.path in scanned)
    }

    @Test fun copyIntoTheSameFolderMakesASuffixedTwin() = runBlocking {
        val a = indexed(camera, "a.jpg")
        ops().copy(listOf(a.path), camera)
        assertEquals(setOf("a.jpg", "a (1).jpg"), camera.list()!!.toSet())
        assertEquals(setOf(a.path, File(camera, "a (1).jpg").path), allPaths())
    }

    // ---- Rename

    @Test fun renameFolderUpdatesChildRows() = runBlocking {
        val trip = dir(pictures, "Trip")
        val tripKl = dir(pictures, "Trip KL")
        val a = indexed(trip, "a.jpg", id = 1)
        val b = indexed(File(trip, "Day 1"), "b.mp4", id = 2, video = true)
        val other = indexed(tripKl, "c.jpg", id = 3)
        favourite(a.path)
        favourite(other.path, at = 8)
        sql("INSERT INTO video_progress(path, positionMs, updatedAt) VALUES (?, 1, 1)", b.path)
        sql("INSERT INTO folder_prefs(path, pinned, gridColumns) VALUES (?, 1, 2)", trip.path)
        sql("INSERT INTO folder_prefs(path, pinned) VALUES (?, 1)", tripKl.path)
        sql("INSERT INTO created_folders(path) VALUES (?)", trip.path)
        sql(
            "INSERT INTO bin(binPath, originalPath, deletedAt, size, mimeType, isVideo, durationMs) VALUES (?, ?, 1, 1, 'image/jpeg', 0, 0)",
            File(root, ".gallery-bin/u.jpg").path, File(trip, "old.jpg").path,
        )

        val renamed = ops().renameFolder(trip.path, "Holiday").getOrThrow()

        val holiday = File(pictures, "Holiday")
        assertEquals(holiday, renamed)
        assertFalse(trip.exists())
        assertTrue(File(holiday, "Day 1/b.mp4").isFile)
        val newA = File(holiday, "a.jpg").path
        val newB = File(holiday, "Day 1/b.mp4").path
        assertEquals(setOf(newA, newB, other.path), allPaths())
        assertEquals(holiday.path, mediaRow(newA)!!.folderPath)
        assertEquals(File(holiday, "Day 1").path, mediaRow(newB)!!.folderPath)
        assertNull(mediaRow(newA)!!.mediaStoreId)
        assertEquals("a folder sharing the prefix is untouched", 3L, mediaRow(other.path)!!.mediaStoreId)
        assertEquals(setOf(newA, other.path), strings("SELECT path FROM favorites").toSet())
        assertEquals(listOf(newB), strings("SELECT path FROM video_progress"))
        assertEquals(setOf(holiday.path, tripKl.path), strings("SELECT path FROM folder_prefs").toSet())
        assertEquals(listOf(2L), longs("SELECT gridColumns FROM folder_prefs WHERE path = ?", holiday.path))
        assertEquals(listOf(holiday.path), strings("SELECT path FROM created_folders"))
        assertEquals(listOf(File(holiday, "old.jpg").path), strings("SELECT originalPath FROM bin"))
        assertTrue(scanned.containsAll(listOf(trip.path, holiday.path)))
    }

    // LIKE would read `_` as "any character" (and ignore case): axb's rows must not move with a_b's.
    @Test fun renameMatchesThePathExactlyNotAsAPattern() = runBlocking {
        val underscored = dir(pictures, "a_b")
        val lookalike = dir(pictures, "axb")
        indexed(underscored, "1.jpg", id = 1)
        val other = indexed(File(lookalike, "sub"), "2.jpg", id = 2)
        favourite(other.path)
        sql("INSERT INTO folder_prefs(path, pinned) VALUES (?, 1)", lookalike.path)

        val renamed = ops().renameFolder(underscored.path, "c").getOrThrow()

        assertEquals(setOf(File(renamed, "1.jpg").path, other.path), allPaths())
        assertEquals(listOf(other.path), strings("SELECT path FROM favorites"))
        assertEquals(listOf(lookalike.path), strings("SELECT path FROM folder_prefs"))
    }

    @Test fun renameRefusesFoldersOtherAppsWriteInto() = runBlocking {
        val a = indexed(camera, "a.jpg")
        assertTrue(ops().renameFolder(camera.path, "Cam").isFailure)
        assertTrue(a.exists())
        assertFalse(File(root, "DCIM/Cam").exists())
        assertEquals(setOf(a.path), allPaths())
    }

    @Test fun renameToATakenNameIsADuplicateAndChangesNothing() = runBlocking {
        val trip = dir(pictures, "Trip")
        dir(pictures, "Holiday")
        val a = indexed(trip, "a.jpg")
        val e = ops().renameFolder(trip.path, "holiday").exceptionOrNull()
        assertEquals(NameError.DUPLICATE, (e as FileOps.NameException).error)
        assertTrue(a.exists())
        assertEquals(setOf(a.path), allPaths())
    }

    @Test fun renameChangingOnlyTheCaseWorks() = runBlocking {
        val trip = dir(pictures, "Trip")
        val a = indexed(trip, "a.jpg")
        val renamed = ops().renameFolder(trip.path, "TRIP").getOrThrow()
        assertEquals("TRIP", renamed.name)
        assertEquals(listOf("TRIP"), pictures.list()!!.toList())
        assertEquals(setOf(File(renamed, "a.jpg").path), allPaths())
        assertTrue(File(renamed, "a.jpg").isFile)
        assertFalse(a.path == File(renamed, "a.jpg").path)
    }

    @Test fun renamingToADotNameHidesTheFolderAndItsSubfolders() = runBlocking {
        val trip = dir(pictures, "Trip")
        indexed(trip, "a.jpg", id = 1)
        indexed(File(trip, "Day 1"), "b.jpg", id = 2)
        val renamed = ops().renameFolder(trip.path, ".Trip").getOrThrow()
        assertTrue(mediaRow(File(renamed, "a.jpg").path)!!.hidden)
        assertTrue(mediaRow(File(renamed, "Day 1/b.jpg").path)!!.hidden)
    }

    // ---- Unicode (Review Focus 2)

    @Test fun unicodeNamesRoundTrip() = runBlocking {
        val name = "旅行 2026 🌸"
        val made = ops().createFolder(pictures, name).getOrThrow()
        assertEquals(name, made.name)
        assertEquals(NameError.DUPLICATE, (ops().createFolder(pictures, name).exceptionOrNull() as FileOps.NameException).error)
        val photo = indexed(camera, "写真 🌸.jpg", byteArrayOf(7, 7))
        favourite(photo.path)

        assertEquals(listOf(photo.path), ops().move(listOf(photo.path), made).done)
        val inside = File(made, "写真 🌸.jpg")
        assertArrayEquals(byteArrayOf(7, 7), inside.readBytes())
        assertEquals(made.path, mediaRow(inside.path)!!.folderPath)

        val tokyo = ops().renameFolder(made.path, "東京 🗼").getOrThrow()
        val last = File(tokyo, "写真 🌸.jpg")
        assertArrayEquals(byteArrayOf(7, 7), last.readBytes())
        assertEquals(setOf(last.path), allPaths())
        assertEquals(tokyo.path, mediaRow(last.path)!!.folderPath)
        assertEquals(listOf(last.path), strings("SELECT path FROM favorites"))
        assertEquals(listOf(tokyo.path), db.mediaDao().createdFolders().first())
    }

    // ---- The index afterwards: no row deleted, no duplicate

    private fun index(source: FakeMediaStoreSource) = MediaIndex(db, source, FsWalker(listOf(root)), scope)

    private fun movedStoreRow(): Triple<FakeMediaStoreSource, MediaIndex, File> = runBlocking {
        val a = file(camera, "a.jpg")
        val source = FakeMediaStoreSource().apply { put(FakeMediaStoreSource.storeRow(a, 5)) }
        val index = index(source)
        index.sync()
        assertEquals(5L, mediaRow(a.path)!!.mediaStoreId)
        favourite(a.path)
        val trip = dir(pictures, "Trip")
        assertEquals(listOf(a.path), ops().move(listOf(a.path), trip).done)
        Triple(source, index, File(trip, "a.jpg"))
    }

    @Test fun afterAMoveMediaStoresNewIdTakesOverTheSameRow() = runBlocking {
        val (source, index, moved) = movedStoreRow()
        source.remove(5)
        source.put(FakeMediaStoreSource.storeRow(moved, 9))
        index.sync()
        assertEquals(setOf(moved.path), allPaths())
        assertEquals(9L, mediaRow(moved.path)!!.mediaStoreId)
        assertEquals(listOf(moved.path), strings("SELECT path FROM favorites"))
    }

    @Test fun afterAMoveMediaStoreKeepingTheIdAtTheNewPathIsOneRow() = runBlocking {
        val (source, index, moved) = movedStoreRow()
        source.put(FakeMediaStoreSource.storeRow(moved, 5))
        index.sync()
        assertEquals(setOf(moved.path), allPaths())
        assertEquals(5L, mediaRow(moved.path)!!.mediaStoreId)
    }

    // Why moved rows drop their id: kept, the id-liveness pass would delete the moved photo here.
    @Test fun whenMediaStoreDropsTheOldIdFirstTheMovedRowStays() = runBlocking {
        val (source, index, moved) = movedStoreRow()
        source.remove(5)
        index.syncMediaStore()
        assertEquals(setOf(moved.path), allPaths())
        assertEquals(listOf(moved.path), strings("SELECT path FROM favorites"))
    }

    @Test fun beforeMediaStoreCatchesUpTheMovedRowSurvivesASyncAndAWalk() = runBlocking {
        val (source, index, moved) = movedStoreRow()
        // MediaStore still lists id 5 at the old path; the old id is live, the new path unknown to it.
        assertNotNull(source.allIds().single())
        index.sync()
        assertEquals(setOf(moved.path), allPaths())
        val r = mediaRow(moved.path)!!
        assertEquals("not rebuilt by the walk", 1_790_000_000_000L, r.dateTaken)
        assertEquals(listOf(moved.path), strings("SELECT path FROM favorites"))
    }
}
