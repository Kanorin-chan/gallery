package dev.kanorin.gallery.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.SearchParser
import dev.kanorin.gallery.logic.SearchQuery
import dev.kanorin.gallery.logic.SortField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Task 11: what the ⋮ sheet's filters and the search pill ask of Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterQueryTest {
    private lateinit var db: GalleryDb
    private val dao get() = db.mediaDao()
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, kl).toInstant().toEpochMilli()

    private fun media(
        name: String,
        date: Long,
        folder: String = "/storage/emulated/0/DCIM/Camera",
        video: Boolean = false,
        mime: String = if (video) "video/mp4" else "image/jpeg",
        hidden: Boolean = false,
        size: Long = 1,
    ) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = mime, isVideo = video,
        dateTaken = date, dateModified = date, size = size, width = 4, height = 3,
        durationMs = if (video) 221_000 else 0, orientation = 0, mediaStoreId = null, hidden = hidden,
        volume = "external_primary",
    )

    private fun insert(vararg rows: MediaEntity) = runBlocking { dao.upsert(rows.toList()) }

    private fun names(filter: MediaFilter = MediaFilter(), folder: String? = null): List<String> = runBlocking {
        val page = dao.pagingItems(filter, folder)
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false))
        (page as PagingSource.LoadResult.Page).data.map { it.name }
    }

    private fun search(raw: String, now: Long = at(2026, 10, 30)) =
        MediaFilter(search = SearchParser.parse(raw, now, kl).takeUnless { it.isEmpty })

    // ---- the sheet's filters ----

    @Test fun videosOffLeavesNoVideos() {
        insert(media("a.jpg", 3), media("v.mp4", 2, video = true), media("g.gif", 1, mime = "image/gif"))
        assertEquals(listOf("a.jpg", "g.gif"), names(MediaFilter(videos = false)))
        assertEquals(listOf("v.mp4"), names(MediaFilter(photos = false, gifs = false)))
        assertEquals(listOf("g.gif"), names(MediaFilter(photos = false, videos = false)))
        assertEquals(emptyList<String>(), names(MediaFilter(photos = false, videos = false, gifs = false)))
    }

    @Test fun screenshotsOnlyKeepsScreenshotFoldersAndNames() {
        insert(
            media("a.png", 4, folder = "/storage/emulated/0/Pictures/Screenshots"),
            media("Screenshot_1.png", 3, folder = "/storage/emulated/0/DCIM"),
            media("c.jpg", 2),
            media("d.png", 1, folder = "/storage/emulated/0/Screenshots old"),
        )
        assertEquals(listOf("a.png", "Screenshot_1.png"), names(MediaFilter(screenshotsOnly = true)))
    }

    @Test fun favouritesOnlyJoinsFavourites() {
        insert(media("a.jpg", 3), media("b.jpg", 2), media("c.jpg", 1))
        db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES ('/storage/emulated/0/DCIM/Camera/b.jpg', 1)")
        assertEquals(listOf("b.jpg"), names(MediaFilter(favouritesOnly = true)))
    }

    @Test fun nameSortAscendingIsAtoZIgnoringCase() {
        insert(media("b.jpg", 3), media("C.jpg", 2), media("a.jpg", 1))
        assertEquals(listOf("a.jpg", "b.jpg", "C.jpg"), names(MediaFilter(sort = SortField.NAME, descending = false)))
        assertEquals(listOf("C.jpg", "b.jpg", "a.jpg"), names(MediaFilter(sort = SortField.NAME, descending = true)))
    }

    // ---- search ----

    @Test fun textMatchesTheFileNameOrTheFoldersOwnName() {
        insert(
            media("beach_1.jpg", 4),
            media("x.jpg", 3, folder = "/storage/emulated/0/Pictures/Beach Trip"),
            media("y.jpg", 2, folder = "/storage/emulated/0/Pictures/Beach Trip/Day2"),
            media("z.jpg", 1),
        )
        assertEquals(listOf("beach_1.jpg", "x.jpg"), names(search("beach")))
    }

    @Test fun aParentFoldersNameDoesNotMatch() {
        insert(media("a.jpg", 2), media("b.jpg", 1, folder = "/storage/emulated/0/Pictures/Trips/Beach"))
        // "emulated" and "Pictures" sit above the folder; only "Camera" / "Beach" are folder names.
        assertEquals(emptyList<String>(), names(search("emulated")))
        assertEquals(emptyList<String>(), names(search("pictures")))
        assertEquals(listOf("a.jpg"), names(search("camera")))
        assertEquals(listOf("b.jpg"), names(MediaFilter(search = SearchQuery("Beach", null, null))))
    }

    @Test fun wildcardsInTheTextMatchThemselves() {
        insert(media("50%_off.jpg", 4), media("500x.jpg", 3), media("a_b.jpg", 2), media("axb.jpg", 1), media("back\\slash.jpg", 0))
        assertEquals(listOf("50%_off.jpg"), names(search("50%")))
        assertEquals(listOf("a_b.jpg"), names(search("a_b")))
        assertEquals(listOf("back\\slash.jpg"), names(search("k\\s")))
    }

    @Test fun theDateRangeIsFromInclusiveToExclusive() {
        val from = at(2026, 10, 28, 0)
        insert(
            media("before.jpg", from - 1), media("start.jpg", from), media("inside.jpg", at(2026, 10, 28, 23)),
            media("end.jpg", from + 86_400_000),
        )
        assertEquals(listOf("inside.jpg", "start.jpg"), names(search("2026-10-28")))
    }

    @Test fun textAndDateNarrowTogether() {
        insert(
            media("beach.jpg", at(2026, 10, 5)), media("beach_old.jpg", at(2026, 9, 5)),
            media("city.jpg", at(2026, 10, 6)),
        )
        assertEquals(listOf("beach.jpg"), names(search("beach oct")))
    }

    @Test fun searchKeepsHiddenFoldersOutAndFiltersCombine() {
        insert(
            media("beach.jpg", 3), media("beach_h.jpg", 2, hidden = true), media("beach.mp4", 1, video = true),
        )
        assertEquals(listOf("beach.jpg", "beach.mp4"), names(search("beach")))
        assertEquals(listOf("beach.jpg"), names(search("beach").copy(videos = false)))
    }

    @Test fun searchWorksInsideAFolderToo() {
        insert(media("beach.jpg", 2), media("city.jpg", 1))
        assertEquals(listOf("beach.jpg"), names(search("beach"), folder = "/storage/emulated/0/DCIM/Camera"))
    }

    @Test fun sectionsPathsAndPagingAgreeUnderASearch() = runBlocking {
        insert(
            media("beach1.jpg", at(2026, 10, 30)), media("beach2.jpg", at(2026, 10, 29)), media("beach3.jpg", at(2026, 10, 29, 8)),
            media("city.jpg", at(2026, 10, 29, 9)),
        )
        val f = search("beach")
        val sections = dao.sections(f, null, ZoneOffset.ofHours(8)).first()
        assertEquals(listOf(1, 2), sections.map { it.count })
        assertEquals(names(f), dao.orderedPaths(f, null).map { it.substringAfterLast('/') })
        assertEquals(listOf("beach2.jpg", "beach3.jpg"), dao.orderedPaths(f, null, offset = 1, limit = 2).map { it.substringAfterLast('/') }.sorted())
    }

    // ---- the observed tables ----

    private fun freshDb() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
        .allowMainThreadQueries().build()

    // Room puts a trigger per observed table (and write kind) into the database; nothing else creates them.
    private fun observedTables(of: GalleryDb): Set<String> {
        val prefix = "room_table_modification_trigger_"
        fun now() = of.openHelper.writableDatabase.query("SELECT name FROM sqlite_temp_master WHERE type = 'trigger' AND name LIKE '$prefix%'")
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        // Polled until the set holds still for three reads: the triggers come in one sync, not always in one read.
        var seen = emptySet<String>()
        var still = 0
        val end = System.currentTimeMillis() + 3_000
        while (still < 3 && System.currentTimeMillis() < end) {
            val next = now().toSet()
            still = if (next.isNotEmpty() && next == seen) still + 1 else 0
            seen = next
            Thread.sleep(20)
        }
        return seen.map { it.removePrefix(prefix).substringBeforeLast('_') }.toSet()
    }

    @Test fun pagedTablesAreExactlyWhatTheRawQueriesObserve() {
        // The raw paging query, bypassing the FreshPagingSource wrapper.
        val pagingDb = freshDb()
        val pagingSeen = try {
            val m = pagingDb.mediaDao().javaClass.getDeclaredMethod("pagingRaw", SupportSQLiteQuery::class.java).also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val source = m.invoke(pagingDb.mediaDao(), MediaQuery.items(MediaFilter(), null)) as PagingSource<Int, MediaEntity>
            source.registerInvalidatedCallback { } // Room subscribes to its tables when Paging does
            runBlocking { source.load(PagingSource.LoadParams.Refresh(null, 10, false)) }
            observedTables(pagingDb).also { source.invalidate() }
        } finally { pagingDb.close() }

        // The raw sections query.
        val sectionsDb = freshDb()
        val sectionsSeen = try {
            val scope = CoroutineScope(Dispatchers.Default)
            sectionsDb.mediaDao().sections(MediaFilter(), null, ZoneOffset.UTC).launchIn(scope)
            observedTables(sectionsDb).also { scope.cancel() }
        } finally { sectionsDb.close() }

        // The wired source: Room's query plus the FreshPagingSource watcher.
        val wiredDb = freshDb()
        val wiredSeen = try {
            val source = wiredDb.mediaDao().pagingItems(MediaFilter(), null)
            runBlocking { source.load(PagingSource.LoadParams.Refresh(null, 10, false)) }
            observedTables(wiredDb).also { source.invalidate() }
        } finally { wiredDb.close() }

        assertEquals("pagingRaw observedEntities", PAGED_TABLES.toSet(), pagingSeen)
        assertEquals("sectionCountsRaw observedEntities", PAGED_TABLES.toSet(), sectionsSeen)
        assertEquals("pagingItems (Room + watcher)", PAGED_TABLES.toSet(), wiredSeen)
    }
}
