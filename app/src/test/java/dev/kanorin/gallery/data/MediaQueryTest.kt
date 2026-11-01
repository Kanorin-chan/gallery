package dev.kanorin.gallery.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.logic.TimelineGrouping
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaQueryTest {
    private lateinit var db: GalleryDb
    private val dao get() = db.mediaDao()
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val klFixed = ZoneOffset.ofHours(8)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, s: Int = 0, ms: Int = 0, zone: ZoneId = kl) =
        ZonedDateTime.of(y, m, d, h, min, s, ms * 1_000_000, zone).toInstant().toEpochMilli()

    private fun media(
        name: String,
        date: Long,
        folder: String = "/storage/emulated/0/DCIM/Camera",
        video: Boolean = false,
        mime: String = if (video) "video/mp4" else "image/jpeg",
        hidden: Boolean = false,
        modified: Long = date,
        size: Long = 1,
        durationMs: Long = if (video) 221_000 else 0,
    ) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = mime, isVideo = video,
        dateTaken = date, dateModified = modified, size = size, width = 4, height = 3,
        durationMs = durationMs, orientation = 0, mediaStoreId = null, hidden = hidden,
        volume = "external_primary",
    )

    private fun insert(vararg rows: MediaEntity) = runBlocking { dao.upsert(rows.toList()) }

    private fun favourite(path: String) =
        db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES (?, 1)", arrayOf(path))

    private fun sections(filter: MediaFilter = MediaFilter(), folder: String? = null, zone: ZoneOffset = klFixed) =
        runBlocking { dao.sections(filter, folder, zone).first() }

    private fun names(filter: MediaFilter = MediaFilter(), folder: String? = null): List<String> = runBlocking {
        val page = dao.pagingItems(filter, folder)
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false))
        (page as PagingSource.LoadResult.Page).data.map { it.name }
    }

    // Timestamps straddling local midnight, month ends and the new year.
    private val edges get() = listOf(
        at(2026, 10, 30, 0, 0),
        at(2026, 10, 29, 23, 59, 59, 999),
        at(2026, 10, 29, 12, 0),
        at(2026, 10, 1, 0, 0),
        at(2026, 9, 30, 23, 59, 59, 999),
        at(2026, 1, 1, 0, 0),
        at(2025, 12, 31, 23, 59, 59, 999),
        at(2025, 12, 31, 16, 0), // 08:00 UTC
    )

    private fun expected(stamps: List<Long>, by: GroupBy, zone: ZoneId): List<Pair<Long, Int>> =
        stamps.groupingBy { TimelineGrouping.groupKey(it, by, zone) }.eachCount().toList().sortedByDescending { it.first }

    private fun assertSqlMatchesKotlin(zone: ZoneOffset, kotlinZone: ZoneId = zone) {
        db.openHelper.writableDatabase.execSQL("DELETE FROM media")
        insert(*edges.mapIndexed { i, t -> media("p$i.jpg", t) }.toTypedArray())
        for (by in listOf(GroupBy.DAY, GroupBy.MONTH, GroupBy.YEAR)) {
            val got = sections(MediaFilter(groupBy = by), zone = zone).map { it.key to it.count }
            assertEquals("$by in $zone", expected(edges, by, kotlinZone), got)
        }
    }

    @Test fun sqlDayMonthYearKeysMatchKotlinInKualaLumpur() = assertSqlMatchesKotlin(klFixed, kotlinZone = kl)

    @Test fun sqlKeysMatchKotlinInOtherOffsets() {
        assertSqlMatchesKotlin(ZoneOffset.ofHours(-5))
        assertSqlMatchesKotlin(ZoneOffset.ofHoursMinutes(5, 45))
        assertSqlMatchesKotlin(ZoneOffset.UTC)
    }

    @Test fun sectionsCarryRunningFirstItemIndex() {
        insert(
            media("a.jpg", at(2026, 10, 30, 9)), media("b.jpg", at(2026, 10, 30, 8)),
            media("c.jpg", at(2026, 10, 29, 9)),
            media("d.jpg", at(2026, 10, 28, 9)), media("e.jpg", at(2026, 10, 28, 8)), media("f.jpg", at(2026, 10, 28, 7)),
        )
        val s = sections()
        assertEquals(listOf(2, 1, 3), s.map(Section::count))
        assertEquals(listOf(0, 2, 3), s.map(Section::firstItemIndex))
    }

    @Test fun pagingOrderIsContiguousWithSections() {
        val stamps = edges.shuffled(java.util.Random(7))
        insert(*stamps.mapIndexed { i, t -> media("p$i.jpg", t) }.toTypedArray())
        val items = runBlocking {
            (dao.pagingItems(MediaFilter(), null).load(PagingSource.LoadParams.Refresh(null, 500, false))
                as PagingSource.LoadResult.Page).data
        }
        val keysInPagingOrder = items.map { TimelineGrouping.groupKey(it.dateTaken, GroupBy.DAY, klFixed) }
        val s = sections()
        assertEquals(s.flatMap { sec -> List(sec.count) { sec.key } }, keysInPagingOrder)
        assertEquals(items.map { it.dateTaken }.sortedDescending(), items.map { it.dateTaken })
    }

    @Test fun hiddenRowsAreExcludedFromTheTimeline() {
        insert(media("seen.jpg", at(2026, 10, 30)), media("sticker.webp", at(2026, 10, 30), folder = "/s/.stickers", hidden = true))
        assertEquals(listOf("seen.jpg"), names())
        assertEquals(listOf(1), sections().map(Section::count))
    }

    @Test fun aHiddenFolderShowsItsOwnRows() {
        insert(media("sticker.webp", at(2026, 10, 30), folder = "/s/.stickers", hidden = true), media("seen.jpg", at(2026, 10, 30)))
        assertEquals(listOf("sticker.webp"), names(folder = "/s/.stickers"))
        assertEquals(listOf(1), sections(folder = "/s/.stickers").map(Section::count))
    }

    @Test fun typeTogglesIncludeEachKind() {
        insert(
            media("photo.jpg", at(2026, 10, 30, 9)),
            media("clip.mp4", at(2026, 10, 30, 8), video = true),
            media("anim.gif", at(2026, 10, 30, 7), mime = "image/gif"),
        )
        assertEquals(listOf("photo.jpg", "clip.mp4", "anim.gif"), names())
        assertEquals(listOf("photo.jpg"), names(MediaFilter(videos = false, gifs = false)))
        assertEquals(listOf("clip.mp4"), names(MediaFilter(photos = false, gifs = false)))
        assertEquals(listOf("anim.gif"), names(MediaFilter(photos = false, videos = false)))
        assertEquals(listOf("photo.jpg", "anim.gif"), names(MediaFilter(videos = false)))
        assertEquals(emptyList<String>(), names(MediaFilter(photos = false, videos = false, gifs = false)))
        assertEquals(emptyList<Section>(), sections(MediaFilter(photos = false, videos = false, gifs = false)))
    }

    @Test fun screenshotsOnlyMatchesTheFolderOrTheFileName() {
        insert(
            media("a.png", at(2026, 10, 30, 9), folder = "/storage/emulated/0/Pictures/Screenshots"),
            media("Screenshot_20261030.png", at(2026, 10, 30, 8), folder = "/storage/emulated/0/DCIM"),
            media("photo.jpg", at(2026, 10, 30, 7)),
            media("b.png", at(2026, 10, 30, 6), folder = "/storage/emulated/0/Screenshots old"),
        )
        assertEquals(listOf("a.png", "Screenshot_20261030.png"), names(MediaFilter(screenshotsOnly = true)))
        assertEquals(listOf(2), sections(MediaFilter(screenshotsOnly = true)).map(Section::count))
    }

    @Test fun favouritesOnlyJoinsFavourites() {
        val loved = media("loved.jpg", at(2026, 10, 30, 9))
        insert(loved, media("meh.jpg", at(2026, 10, 30, 8)))
        favourite(loved.path)
        assertEquals(listOf("loved.jpg"), names(MediaFilter(favouritesOnly = true)))
        assertEquals(listOf("loved.jpg"), names(folder = FAVORITES_FOLDER))
        assertEquals(listOf(1), sections(folder = FAVORITES_FOLDER).map(Section::count))
    }

    @Test fun folderPathNarrowsToThatFolder() {
        insert(media("a.jpg", at(2026, 10, 30)), media("b.jpg", at(2026, 10, 30), folder = "/storage/emulated/0/Download"))
        assertEquals(listOf("b.jpg"), names(folder = "/storage/emulated/0/Download"))
    }

    @Test fun sortFieldsAndDirection() {
        insert(
            media("b.jpg", at(2026, 10, 28), size = 30, modified = at(2026, 10, 1)),
            media("C.jpg", at(2026, 10, 30), size = 10, modified = at(2026, 10, 2)),
            media("a.jpg", at(2026, 10, 29), size = 20, modified = at(2026, 10, 3)),
        )
        assertEquals(listOf("C.jpg", "a.jpg", "b.jpg"), names())
        assertEquals(listOf("b.jpg", "a.jpg", "C.jpg"), names(MediaFilter(descending = false)))
        assertEquals(listOf("a.jpg", "C.jpg", "b.jpg"), names(MediaFilter(sort = SortField.DATE_MODIFIED)))
        assertEquals(listOf("a.jpg", "b.jpg", "C.jpg"), names(MediaFilter(sort = SortField.NAME, descending = false)))
        assertEquals(listOf("b.jpg", "a.jpg", "C.jpg"), names(MediaFilter(sort = SortField.SIZE)))
    }

    // Kano 2026-10-31: "sort by: length (video)". Photos have no length: after every video both ways, newest first.
    @Test fun lengthSortsVideosByDurationAndPhotosLast() {
        insert(
            media("p.jpg", at(2026, 10, 30)),
            media("p-old.jpg", at(2026, 10, 1)),
            media("short.mp4", at(2026, 10, 28), video = true, durationMs = 5_000),
            media("long.mp4", at(2026, 10, 29), video = true, durationMs = 3_900_000),
            media("mid.mp4", at(2026, 10, 27), video = true, durationMs = 61_000),
        )
        assertEquals(listOf("long.mp4", "mid.mp4", "short.mp4", "p.jpg", "p-old.jpg"), names(MediaFilter(sort = SortField.DURATION)))
        assertEquals(
            listOf("short.mp4", "mid.mp4", "long.mp4", "p.jpg", "p-old.jpg"),
            names(MediaFilter(sort = SortField.DURATION, descending = false)),
        )
    }

    @Test fun dateModifiedSortGroupsByDateModified() {
        insert(
            media("x.jpg", at(2026, 10, 30), modified = at(2026, 10, 2)),
            media("y.jpg", at(2026, 10, 30), modified = at(2026, 10, 1)),
        )
        val s = sections(MediaFilter(sort = SortField.DATE_MODIFIED))
        assertEquals(listOf(at(2026, 10, 2, 0), at(2026, 10, 1, 0)), s.map(Section::key))
    }

    @Test fun nameAndSizeSortsAndGroupNoneGiveOneSection() {
        insert(media("a.jpg", at(2026, 10, 30)), media("b.jpg", at(2026, 9, 1)))
        for (f in listOf(MediaFilter(groupBy = GroupBy.NONE), MediaFilter(sort = SortField.NAME), MediaFilter(sort = SortField.SIZE))) {
            assertEquals("$f", listOf(Section(0, "", 2, 0)), sections(f))
        }
    }

    @Test fun ascendingSectionsRunOldestFirst() {
        insert(media("new.jpg", at(2026, 10, 30)), media("old.jpg", at(2026, 9, 1)))
        assertEquals(listOf(at(2026, 9, 1, 0), at(2026, 10, 30, 0)), sections(MediaFilter(descending = false)).map(Section::key))
    }

    private fun pagingPaths(filter: MediaFilter, folder: String? = null): List<String> = runBlocking {
        (dao.pagingItems(filter, folder).load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page)
            .data.map { it.path }
    }

    @Test fun orderedPathsFollowTheGridOrderForEveryFilter() {
        val stamps = edges.shuffled(java.util.Random(3))
        insert(*stamps.mapIndexed { i, t -> media("p$i.jpg", t, size = (i * 7 % 5).toLong(), video = i % 3 == 0) }.toTypedArray())
        // Same dateTaken twice, so the rowid tie-break decides.
        insert(media("tie1.jpg", stamps[0]), media("tie2.jpg", stamps[0]))
        favourite("/storage/emulated/0/DCIM/Camera/p2.jpg")
        val filters = listOf(
            MediaFilter(), MediaFilter(descending = false), MediaFilter(sort = SortField.NAME),
            MediaFilter(sort = SortField.SIZE), MediaFilter(videos = false), MediaFilter(favouritesOnly = true),
        )
        for (f in filters) assertEquals("$f", pagingPaths(f), runBlocking { dao.orderedPaths(f, null) })
        assertEquals(pagingPaths(MediaFilter(), FAVORITES_FOLDER), runBlocking { dao.orderedPaths(MediaFilter(), FAVORITES_FOLDER) })
    }

    // The viewer opened from a file: its start page is where the grid would put that file.
    @Test fun indexOfIsThePathsPlaceInGridOrderForEveryFilter() {
        val stamps = edges.shuffled(java.util.Random(5))
        insert(*stamps.mapIndexed { i, t -> media("p$i.jpg", t, size = (i * 7 % 5).toLong(), video = i % 3 == 0) }.toTypedArray())
        insert(media("tie1.jpg", stamps[0]), media("tie2.jpg", stamps[0]), media("other.jpg", stamps[1], folder = "/storage/emulated/0/Other"))
        favourite("/storage/emulated/0/DCIM/Camera/p2.jpg")
        favourite("/storage/emulated/0/DCIM/Camera/p4.jpg")
        val filters = listOf(
            MediaFilter(), MediaFilter(descending = false), MediaFilter(sort = SortField.NAME),
            MediaFilter(sort = SortField.SIZE, descending = false), MediaFilter(videos = false), MediaFilter(favouritesOnly = true),
        )
        for (folder in listOf(null, "/storage/emulated/0/DCIM/Camera", FAVORITES_FOLDER)) {
            for (f in filters) {
                val order = runBlocking { dao.orderedPaths(f, folder) }
                order.forEachIndexed { i, p -> assertEquals("$f $folder $p", i, runBlocking { dao.indexOf(f, folder, p) }) }
            }
        }
        // Not in that grid at all: no place.
        assertEquals(null, runBlocking { dao.indexOf(MediaFilter(), "/storage/emulated/0/DCIM/Camera", "/storage/emulated/0/Other/other.jpg") })
        assertEquals(null, runBlocking { dao.indexOf(MediaFilter(videos = false), null, "/storage/emulated/0/DCIM/Camera/p0.jpg") })
    }

    @Test fun aFavouriteIsAddedOnceAndRemoved() {
        val path = "/storage/emulated/0/DCIM/Camera/a.jpg"
        insert(media("a.jpg", at(2026, 10, 30)))
        assertEquals(false, runBlocking { dao.isFavorite(path).first() })
        runBlocking { dao.addFavorite(FavoriteEntity(path, 10)) }
        runBlocking { dao.addFavorite(FavoriteEntity(path, 20)) }
        assertEquals(true, runBlocking { dao.isFavorite(path).first() })
        assertEquals(listOf(path), runBlocking { dao.orderedPaths(MediaFilter(), FAVORITES_FOLDER) })
        runBlocking { db.pathDao().deleteFavorite(path) }
        assertEquals(false, runBlocking { dao.isFavorite(path).first() })
    }

    @Test fun anOffsetAndLimitGiveExactlyOneSectionsPaths() {
        insert(
            media("a.jpg", at(2026, 10, 30, 9)), media("b.jpg", at(2026, 10, 30, 8)),
            media("c.jpg", at(2026, 10, 29, 9)), media("d.jpg", at(2026, 10, 29, 8)), media("e.jpg", at(2026, 10, 29, 7)),
            media("f.jpg", at(2026, 10, 28, 9)),
        )
        val s = sections()[1]
        val got = runBlocking { dao.orderedPaths(MediaFilter(), null, offset = s.firstItemIndex, limit = s.count) }
        assertEquals(listOf("c.jpg", "d.jpg", "e.jpg").map { "/storage/emulated/0/DCIM/Camera/$it" }, got)
    }

    @Test fun sizesAndRowsByPathWorkPastTheBindLimit() {
        val rows = (0 until 1_200).map { media("n$it.jpg", at(2026, 10, 30), size = it.toLong()) }
        insert(*rows.toTypedArray())
        val paths = rows.map { it.path } + "/not/indexed.jpg"
        assertEquals((0 until 1_200).sumOf { it.toLong() }, runBlocking { dao.sumSizes(paths) })
        assertEquals(0L, runBlocking { dao.sumSizes(emptyList()) })
        assertEquals(rows.map { it.path }.toSet(), runBlocking { dao.byPaths(paths) }.map { it.path }.toSet())
    }

    @Test fun defaultTimelineOrderComesFromTheIndexWithoutASort() {
        val q = MediaQuery.items(MediaFilter(), null)
        val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + q.sql)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
        }
        assertTrue("$plan", plan.any { "index_media_hidden_dateTaken" in it })
        assertTrue("$plan", plan.none { "TEMP B-TREE" in it })
        assertTrue("$plan", plan.none { "folder_prefs" in it })
    }
}
