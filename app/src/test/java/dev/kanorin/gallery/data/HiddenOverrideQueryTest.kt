package dev.kanorin.gallery.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.logic.MediaFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ruling U: effective hidden = COALESCE(folder_prefs.hiddenOverride, media.hidden) in every All-view query. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenOverrideQueryTest {
    private lateinit var db: GalleryDb
    private val dao get() = db.mediaDao()
    private val trip = "/storage/emulated/0/Pictures/Trip"
    private val stickers = "/storage/emulated/0/WhatsApp/.Stickers"
    private val filter = MediaFilter()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { dao.upsert(listOf(media(trip, "a.jpg", 2, false), media(stickers, "s.webp", 1, true))) }
    }

    @After fun tearDown() = db.close()

    private fun media(folder: String, name: String, date: Long, hidden: Boolean) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = "image/jpeg", isVideo = false,
        dateTaken = date, dateModified = date, size = 1, width = 4, height = 3, durationMs = 0, orientation = 0,
        mediaStoreId = null, hidden = hidden, volume = "external_primary",
    )

    private fun allPaths() = runBlocking { dao.orderedPaths(filter, null) }
    private fun sectionTotal() = runBlocking { dao.sections(filter, null).first().sumOf { it.count } }
    private fun visibleCount() = db.visibleCount()

    @Test fun hidingAVisibleFolderRemovesItsPhotosFromAllViews() {
        assertEquals(listOf("$trip/a.jpg"), allPaths())
        runBlocking { dao.setHiddenOverride(trip, true) }
        assertEquals(emptyList<String>(), allPaths())
        assertEquals(0, sectionTotal())
        assertEquals(0, visibleCount())
    }

    @Test fun unhidingANomediaFolderBringsItsPhotosIntoAll() {
        runBlocking { dao.setHiddenOverride(stickers, false) }
        assertEquals(listOf("$trip/a.jpg", "$stickers/s.webp"), allPaths())
        assertEquals(2, sectionTotal())
        assertEquals(2, visibleCount())
    }

    @Test fun anOverrideOfNullChangesNothing() {
        runBlocking { dao.setPinned(trip, true) }
        assertEquals(listOf("$trip/a.jpg"), allPaths())
    }

    @Test fun aWriteToFolderPrefsInvalidatesTheGridsPagingSource() {
        val source = dao.pagingItems(filter, null)
        val first = runBlocking { source.load(PagingSource.LoadParams.Refresh(null, 50, true)) }
        assertTrue(first is PagingSource.LoadResult.Page)
        assertTrue(!source.invalid)
        runBlocking { dao.setHiddenOverride(trip, true) }
        val deadline = System.currentTimeMillis() + 10_000
        while (!source.invalid && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("folder_prefs write must invalidate the paging source", source.invalid)
    }

    @Test fun theFoldersListAppliesTheSameRule() {
        runBlocking { dao.setHiddenOverride(trip, true) }
        val flags = runBlocking { dao.folders().first() }.associate { it.path to it.hidden }
        // Rows hold the effective flag (override ?: rule), so folders() already reflects the Hide.
        assertEquals(mapOf(trip to true, stickers to true), flags)
    }

    @Test fun theOverrideAndTheRowFlagsChangeInOneTransaction() {
        runBlocking { dao.setHiddenOverride(trip, true) }
        val flags = runBlocking { dao.byPath("$trip/a.jpg") }!!.hidden
        assertTrue(flags)
        assertEquals(true, runBlocking { dao.folderPrefs().first() }.single { it.path == trip }.hiddenOverride)
    }
}
