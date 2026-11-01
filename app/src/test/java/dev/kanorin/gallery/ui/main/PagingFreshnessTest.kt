package dev.kanorin.gallery.ui.main

import android.content.Context
import android.util.Log
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.RefreshWhenStale
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.timeline.TimelineGrid
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task 7b: a write that lands while the grid's first page is loading must still reach the tiles.
 * Writes go through the real MediaDao the way MediaIndex writes them: MediaStore rows, one transaction per 500.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class PagingFreshnessTest {
    @get:Rule val rule = createComposeRule()

    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 32, 0, 0, kl).toInstant().toEpochMilli()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hold = FirstPageHold()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notes = AtomicInteger()
    private lateinit var db: GalleryDb
    private var vm: MainViewModel? = null
    private lateinit var items: LazyPagingItems<MediaEntity>

    @After fun tearDown() {
        hold.release.countDown()
        // An in-flight query (MainViewModel.folders' byPaths) must end before the pool closes, or it fails this test.
        vm?.let { cancelAndDrain(it.viewModelScope) }
        cancelAndDrain(bg)
        // Main-looper work left queued poisons later suites in this JVM (see SelectionStateTest).
        shadowOf(Looper.getMainLooper()).idle()
        if (::db.isInitialized) db.close()
    }

    // ---- MediaDao.pagingItems alone: no UI and no main looper at all ----

    @Test fun aBatchCommittedWhileTheFirstPageReadsInvalidatesTheSource() = runBlocking {
        fileDb()
        watchMedia()
        val source = db.mediaDao().pagingItems(MediaFilter(), null)
        val first = bg.async { source.load(PagingSource.LoadParams.Refresh(null, 600, true)) }
        assertTrue("first page read started", hold.reached.await(5, TimeUnit.SECONDS))

        // WAL: the write commits beside the open read transaction, as on the phone.
        withTimeout(5_000) { db.mediaDao().replaceStoreRows(batch(0 until 500)) }
        awaitNotes(1)
        Thread.sleep(SETTLE_MS) // Room's own collector has the same notification by now; the load is still in flight
        hold.release.countDown()

        val page = first.await() as PagingSource.LoadResult.Page
        assertEquals("the first page read its pre-write snapshot", 0, page.data.size)
        assertTrue("a source that missed a write must invalidate", waitFor(2_000) { source.invalid })
    }

    // ---- The grid, composed the way MainScreen wires it ----

    @Test fun aFirstBatchCommittedWhileTheGridsFirstPageReadsShowsItsTiles() {
        fileDb()
        watchMedia()
        show()
        assertTrue("first page read started", pumpUntil(5_000) { hold.reached.count == 0L })

        val write = bg.launch { db.mediaDao().replaceStoreRows(batch(0 until 500)) }
        // The main looper keeps running the whole time, as on a phone.
        assertTrue("write committed and Room notified", pumpUntil(5_000) { write.isCompleted && notes.get() >= 1 })
        pumpUntil(SETTLE_MS) { false }
        hold.release.countDown()

        assertTilesArrive(500)
        assertEquals("the stale page, then one fresh generation", 2, hold.firstLoads.get())
    }

    @Test fun aSecondBatchCommittedBeforeTheFirstPageReachesTheMainThreadShowsItsTiles() {
        memoryDb()
        watchMedia()
        runBlocking { db.mediaDao().replaceStoreRows(batch(0 until 500)) }
        awaitNotes(1)
        show()
        assertTrue("first page read started", pumpUntil(5_000) { hold.reached.count == 0L })

        // One connection: the write waits for the page read, then commits before the main thread takes the page.
        bg.launch { db.mediaDao().replaceStoreRows(batch(500 until 1_000)) }
        hold.release.countDown()
        awaitNotes(2) // the main thread is busy here, as on a cold start
        Thread.sleep(SETTLE_MS)

        assertTilesArrive(1_000)
        assertEquals("the stale page, then one fresh generation", 2, hold.firstLoads.get())
    }

    // ---- Safety net: counts that still disagree 500 ms after both have loaded get one refresh ----

    @Test fun aPagedCountStillShortAfter500MsRefreshesOnce() {
        ShadowLog.clear()
        val made = showCounts(expected = 3, 2, 3)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertEquals("not before 500 ms", 1, made.get())
        rule.mainClock.advanceTimeBy(101)
        assertTrue("refreshed to the sections total", pumpUntil(2_000) { counted.itemCount == 3 })
        assertEquals(2, made.get())
        val warned = ShadowLog.getLogsForTag("RefreshWhenStale").filter { it.type == Log.WARN }
        assertEquals("the safety net says when it fires", 1, warned.size)
        assertTrue(warned.single().msg, "2 != sections total 3" in warned.single().msg)
    }

    @Test fun aMismatchThatSurvivesItsRefreshIsLeftAloneUntilTheTotalChanges() {
        val made = showCounts(expected = 3, 2)
        rule.mainClock.advanceTimeBy(501)
        assertTrue("one refresh", pumpUntil(2_000) { made.get() == 2 })
        tick(10)
        assertEquals("no loop", 2, made.get())

        expected = 4
        rule.waitForIdle() // applies the write; the effect sees it on the next frame
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(501)
        assertTrue("a new sections total re-arms it once", pumpUntil(2_000) { made.get() == 3 })
        tick(5)
        assertEquals(3, made.get())
    }

    @Test fun agreeingCountsOrUnansweredSectionsNeverRefresh() {
        val made = showCounts(expected = null, 2)
        tick(3)
        assertEquals("sections not answered yet", 1, made.get())
        expected = 2
        tick(3)
        assertEquals("counts agree", 1, made.get())
    }

    // Headers make the grid's entries outnumber its items; only items may be compared.
    @Test fun mainScreenLeavesAGridThatAgreesAlone() {
        memoryDb()
        hold.release.countDown()
        runBlocking { db.mediaDao().replaceStoreRows(batch(0 until 1_000)) }
        val model = FileOps(db, {}).let { ops -> MainViewModel(db.mediaDao(), Settings(MemoryStore()), SavedStateHandle(), ops, RecycleBin(db, ops), clock = { now }, zone = { kl }) }
        vm = model
        rule.setContent { GalleryTheme { MainScreen(model) } }
        assertTrue("grid shown", pumpUntil(5_000) {
            model.sections.value?.size == 2 && rule.onAllNodes(hasTestTag(tileTag(0))).fetchSemanticsNodes().isNotEmpty()
        })
        val loads = hold.firstLoads.get()
        rule.mainClock.autoAdvance = false
        repeat(4) { rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
        assertEquals("no refresh", loads, hold.firstLoads.get())
    }

    // ---- helpers ----

    private var expected by mutableStateOf<Int?>(null)
    private lateinit var counted: LazyPagingItems<Int>

    /** A grid's paging alone, each generation [counts] long in turn (the last repeats); returns generations made. */
    private fun showCounts(expected: Int?, vararg counts: Int): AtomicInteger {
        val made = AtomicInteger()
        this.expected = expected
        val pager = Pager(PagingConfig(pageSize = 10, enablePlaceholders = true)) {
            CountSource(counts[minOf(made.getAndIncrement(), counts.lastIndex)])
        }.flow
        rule.setContent {
            counted = pager.collectAsLazyPagingItems()
            RefreshWhenStale(this.expected, counted)
        }
        assertTrue("first page", pumpUntil(2_000) { counted.itemCount == counts[0] && counted.loadState.refresh is LoadState.NotLoading })
        rule.mainClock.autoAdvance = false
        return made
    }

    // A second of test clock, then real time for any load it started, so the grid sees Loading -> NotLoading.
    private fun tick(seconds: Int) = repeat(seconds) {
        rule.mainClock.advanceTimeBy(1_000)
        pumpUntil(LOAD_MS * 4) { false }
    }

    // Takes real time like a Room read, so a refresh shows as Loading before it settles.
    private class CountSource(private val n: Int) : PagingSource<Int, Int>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            withContext(Dispatchers.IO) { Thread.sleep(LOAD_MS) }
            return LoadResult.Page((0 until n).toList(), null, null, 0, 0)
        }

        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
    }

    // File-backed like GalleryDb.build (WAL), so a write can commit while the first page reads its snapshot.
    private fun fileDb() {
        context.deleteDatabase(FILE_DB)
        db = Room.databaseBuilder(context, GalleryDb::class.java, FILE_DB).setQueryCallback(Dispatchers.Unconfined, hold).build()
    }

    private fun memoryDb() {
        db = Room.inMemoryDatabaseBuilder(context, GalleryDb::class.java).setQueryCallback(Dispatchers.Unconfined, hold).build()
    }

    // MainScreen's wiring without its stale-count safety net, so only the paging fix can make these pass.
    private fun show() {
        val model = FileOps(db, {}).let { ops -> MainViewModel(db.mediaDao(), Settings(MemoryStore()), SavedStateHandle(), ops, RecycleBin(db, ops), clock = { now }, zone = { kl }) }
        vm = model
        rule.setContent {
            GalleryTheme {
                val sections by model.sections.collectAsState()
                items = model.items.collectAsLazyPagingItems()
                TimelineGrid(TimelineLayout(sections.orEmpty()), items, columns = 4)
            }
        }
    }

    private fun assertTilesArrive(total: Int) {
        val model = checkNotNull(vm)
        val sections = { model.sections.value?.sumOf { it.count } }
        pumpUntil(FRESH_MS) { sections() == total && items.itemCount == total && items.peek(0) != null }
        assertEquals("sections total", total, sections())
        assertEquals("paged itemCount", total, items.itemCount)
        assertNotNull("first tile", items.peek(0))
    }

    // A second observer of `media`, subscribed before any write, so the test knows when Room has sent each invalidation.
    private fun watchMedia() {
        val subscribed = CountDownLatch(1)
        bg.launch {
            db.invalidationTracker.createFlow("media").collect {
                if (subscribed.count > 0) subscribed.countDown() else notes.incrementAndGet()
            }
        }
        assertTrue("watcher subscribed", subscribed.await(5, TimeUnit.SECONDS))
    }

    private fun awaitNotes(n: Int) = assertTrue("Room sent invalidation $n", waitFor(5_000) { notes.get() >= n })

    // Robolectric runs the main looper only on waitForIdle; a waitUntil on plain fields never would.
    private fun pumpUntil(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (true) {
            rule.waitForIdle()
            if (done()) return true
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(10)
        }
    }

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!done()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(10)
        }
        return true
    }

    // As MediaIndex.readMediaStore writes them: MediaStore rows, newest first.
    private fun batch(range: IntRange) = range.map { i ->
        MediaEntity(
            path = "/storage/emulated/0/DCIM/Camera/$i.jpg", folderPath = "/storage/emulated/0/DCIM/Camera", name = "$i.jpg",
            mimeType = "image/jpeg", isVideo = false, dateTaken = now - i * 60_000L, dateModified = now - i * 60_000L,
            size = 1_000, width = 4, height = 3, durationMs = 0, orientation = 0, mediaStoreId = i + 1L, hidden = false,
            volume = "external_primary",
        )
    }

    /** Holds Room's first LIMIT/OFFSET page read: after its COUNT (snapshot taken), inside its read transaction. */
    private class FirstPageHold : RoomDatabase.QueryCallback {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstLoads = AtomicInteger() // Room counts only in a generation's first load
        private val armed = AtomicBoolean(true)

        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            if (sqlQuery.startsWith("SELECT COUNT(*) FROM ( SELECT media.*")) firstLoads.incrementAndGet()
            if (sqlQuery.startsWith("SELECT * FROM (") && " LIMIT " in sqlQuery && armed.compareAndSet(true, false)) {
                reached.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        const val FILE_DB = "fresh.db"
        const val SETTLE_MS = 300L
        const val FRESH_MS = 3_000L
        const val LOAD_MS = 30L
    }
}
