package dev.kanorin.gallery.data

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.MediaFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderQueryTest {
    private lateinit var db: GalleryDb
    private val dao get() = db.mediaDao()
    private val cam = "/storage/emulated/0/DCIM/Camera"
    private val trip = "/storage/emulated/0/Pictures/Trip KL"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun media(name: String, folder: String, date: Long, size: Long = 1, hidden: Boolean = false) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = "image/jpeg", isVideo = false,
        dateTaken = date, dateModified = date, size = size, width = 4, height = 3, durationMs = 0, orientation = 0,
        mediaStoreId = null, hidden = hidden, volume = "external_primary",
    )

    @Test fun countsSizeLatestAndNewestCoverPerFolder() = runBlocking {
        dao.upsert(
            listOf(
                media("old.jpg", cam, 100, size = 10), media("new.jpg", cam, 300, size = 20), media("mid.jpg", cam, 200, size = 30),
                media("t.jpg", trip, 50, size = 5),
            ),
        )
        val folders = dao.folders().first().associateBy { it.path }
        assertEquals(setOf(cam, trip), folders.keys)
        with(folders.getValue(cam)) {
            assertEquals("Camera", name)
            assertEquals(3, count)
            assertEquals(60L, totalSize)
            assertEquals(300L, latestDate)
            assertEquals("$cam/new.jpg", coverPath)
        }
        with(folders.getValue(trip)) {
            assertEquals("Trip KL", name)
            assertEquals(1, count)
            assertEquals("$trip/t.jpg", coverPath)
        }
    }

    @Test fun hiddenFoldersAreReturnedFlaggedNotExcluded() = runBlocking {
        val stickers = "/storage/emulated/0/WhatsApp/.Stickers"
        dao.upsert(listOf(media("a.jpg", cam, 1), media("s.webp", stickers, 2, hidden = true)))
        val folders = dao.folders().first().associateBy { it.path }
        assertEquals(true, folders.getValue(stickers).hidden)
        assertEquals(false, folders.getValue(cam).hidden)
        assertEquals(".Stickers", folders.getValue(stickers).name)
    }

    @Test fun aFolderAtTheRootGetsItsOwnNameToo() = runBlocking {
        dao.upsert(listOf(media("a.jpg", "/storage/ABCD-1234", 1)))
        assertEquals("ABCD-1234", dao.folders().first().single().name)
    }

    @Test fun twoItemsWithTheSameDateTakenCoverWithTheHigherRowid() = runBlocking {
        dao.upsert(listOf(media("first.jpg", cam, 100)))
        dao.upsert(listOf(media("second.jpg", cam, 100)))
        assertEquals("$cam/second.jpg", dao.folders().first().single().coverPath)
    }

    @Test fun emojiAndCjkFolderNamesComeBackWhole() = runBlocking {
        val name = "旅行 2026 🌸"
        dao.upsert(listOf(media("a.jpg", "/storage/emulated/0/Pictures/$name", 1)))
        assertEquals(name, dao.folders().first().single().name)
    }

    @Test fun favouritesSummaryCountsOnlyIndexedFavouritesAndCoversTheLatestAdded() = runBlocking {
        dao.upsert(listOf(media("a.jpg", cam, 1), media("b.jpg", cam, 2)))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO favorites(path, addedAt) VALUES ('$cam/a.jpg', 10), ('$cam/b.jpg', 20), ('/gone.jpg', 30)",
        )
        val fav = dao.favouritesSummary().first()
        assertEquals(2, fav.count)
        assertEquals("$cam/b.jpg", fav.coverPath)
    }

    @Test fun aFavouriteInAHiddenFolderIsLeftOutOfTheCardAndTheHeader() = runBlocking {
        val hiddenDir = "/storage/emulated/0/WhatsApp/.Stickers"
        dao.upsert(listOf(media("a.jpg", cam, 1, size = 10), media("h.jpg", hiddenDir, 2, size = 99, hidden = true)))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO favorites(path, addedAt) VALUES ('$cam/a.jpg', 10), ('$hiddenDir/h.jpg', 20)",
        )
        val card = dao.favouritesSummary().first()
        assertEquals(1, card.count)
        assertEquals("$cam/a.jpg", card.coverPath) // the newest favourite is hidden, so not the cover
        val header = dao.favouritesStats().first()
        assertEquals(1, header.count)
        assertEquals(10L, header.totalSize)
        // The grid agrees.
        assertEquals(listOf("$cam/a.jpg"), dao.orderedPaths(MediaFilter(), FAVORITES_FOLDER))
    }

    @Test fun favouritesSummaryWithNoFavouritesIsZeroWithNoCover() = runBlocking {
        val fav = dao.favouritesSummary().first()
        assertEquals(0, fav.count)
        assertNull(fav.coverPath)
    }

    @Test fun prefsAreUpsertedWithoutLosingOtherFields() = runBlocking {
        dao.setPinned(cam, true)
        dao.setHiddenOverride(cam, true)
        dao.setPinned(cam, false)
        val p = dao.folderPrefs().first().single()
        assertEquals(false, p.pinned)
        assertEquals(true, p.hiddenOverride)
    }

    @Test fun theFolderPathIndexServesTheGroupByAndTheCoverSubqueryPaysOneTempSortPerFolder() {
        val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $FOLDERS_SQL")).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnCount() - 1)) }
        }
        val text = plan.joinToString("\n")
        assertTrue(text, plan.any { it.startsWith("SCAN") && "index_media_folderPath" in it })
        assertTrue(text, plan.any { it.startsWith("SEARCH") && "index_media_folderPath (folderPath=?)" in it })
        // The cover subquery sorts that folder's rows (dateTaken, rowid) in a temp tree; the GROUP BY itself does not.
        assertTrue(text, plan.any { it == "USE TEMP B-TREE FOR ORDER BY" })
        assertTrue(text, plan.none { "GROUP BY" in it })
    }

    @Test fun oneHundredThousandRowsGroupInWellUnderASecond() = runBlocking {
        val rows = (0 until 100_000).map { i -> media("p$i.jpg", "/storage/emulated/0/Pictures/F${i % 400}", date = i.toLong(), size = 3) }
        rows.chunked(5_000).forEach { dao.upsert(it) }
        dao.folders().first() // warm
        val start = System.nanoTime()
        val folders = dao.folders().first()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(400, folders.size)
        assertEquals(250, folders.first().count)
        assertTrue("took $ms ms", ms < 1_000)
    }
}
