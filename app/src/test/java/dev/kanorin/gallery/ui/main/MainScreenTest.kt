package dev.kanorin.gallery.ui.main

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.ShowChip
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.PILL_MENU_TAG
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.timeline.headerCountTag
import dev.kanorin.gallery.ui.timeline.tileTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenTest {
    @get:Rule val rule = createComposeRule()

    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 32, 0, 0, kl).toInstant().toEpochMilli()
    private lateinit var db: GalleryDb
    private lateinit var vm: MainViewModel
    private val settings = Settings(MemoryStore())

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        vm = newVm()
    }

    private fun newVm(firstSync: StateFlow<Boolean> = MutableStateFlow(false), toasts: ToastHostState = ToastHostState()) =
        FileOps(db, {}).let { ops ->
            MainViewModel(
                db.mediaDao(), settings, SavedStateHandle(), ops, RecycleBin(db, ops), clock = { now }, zone = { kl },
                toasts = toasts, firstSync = firstSync,
            )
        }

    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }
    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun emptyText(text: String) = until { rule.onAllNodes(hasTestTag(MAIN_EMPTY_TAG) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun show() = rule.setContent { GalleryTheme { MainScreen(vm) } }

    private fun row(name: String, hoursAgo: Long, video: Boolean = false, hidden: Boolean = false) = MediaEntity(
        path = "/storage/emulated/0/DCIM/Camera/$name", folderPath = "/storage/emulated/0/DCIM/Camera", name = name,
        mimeType = if (video) "video/mp4" else "image/jpeg", isVideo = video,
        dateTaken = now - hoursAgo * 3_600_000, dateModified = 1, size = 1, width = 4, height = 3,
        durationMs = if (video) 221_000 else 0, orientation = 0, mediaStoreId = null, hidden = hidden,
        volume = "external_primary",
    )

    @Test fun pillShowsAllModeAndSwitchesToFoldersWithoutMovingTheMenu() {
        show()
        rule.onNodeWithText("Search photos").assertIsDisplayed()
        rule.onNodeWithText("All").assertIsDisplayed()
        val menuBefore = rule.onNodeWithTag(PILL_MENU_TAG).getUnclippedBoundsInRoot()

        rule.onNodeWithText("All").performClick()

        rule.onNodeWithText("Folders").assertIsDisplayed()
        rule.onNodeWithText("Search folders").assertIsDisplayed()
        rule.onNodeWithText("Search photos").assertDoesNotExist()
        val menuAfter = rule.onNodeWithTag(PILL_MENU_TAG).getUnclippedBoundsInRoot()
        assertEquals(menuBefore.left, menuAfter.left)
        assertEquals(menuBefore.right, menuAfter.right)
    }

    // Final review I3: an empty grid says why, but not while the index is still being filled.
    @Test fun anEmptyIndexSaysNoPhotosYetOnceTheFirstSyncIsOver() {
        val syncing = MutableStateFlow(true)
        cancelAndDrain(vm.viewModelScope)
        vm = newVm(firstSync = syncing)
        show()
        until { vm.sections.value != null }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(false, exists(MAIN_EMPTY_TAG))
        syncing.value = false
        emptyText("No photos yet")
        rule.onNodeWithTag(MAIN_EMPTY_TAG).assertIsDisplayed()
    }

    // Round 2 (B): no results are said as soon as Room answers; only "No photos yet" waits for the first pass.
    @Test fun noMatchingItemsDoesNotWaitForTheFirstSync() {
        runBlocking { db.mediaDao().upsert(listOf(row("a.jpg", hoursAgo = 1))) }
        cancelAndDrain(vm.viewModelScope)
        vm = newVm(firstSync = MutableStateFlow(true))
        show()
        until { exists(headerCountTag(0)) }
        vm.setQuery("zzz")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        emptyText("No matching items")
    }

    // Round 2 (C): while Room answers a new filter, the grid keeps drawing the last layout instead of blanking.
    @Test fun aFilterChangeKeepsTheLastLayoutWhileTheNewOneLoads() {
        runBlocking { db.mediaDao().upsert(listOf(row("a.jpg", hoursAgo = 1), row("b.jpg", hoursAgo = 24))) }
        show()
        until { exists(headerCountTag(1)) }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        // The in-memory db has one connection: holding it keeps the new filter's sections unanswered.
        val holder = thread { db.runInTransaction { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            vm.setChip(ShowChip.SCREENSHOTS, true)
            until { vm.sections.value == null }
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
            assertTrue("the last layout is still drawn", exists(headerCountTag(0)) && exists(headerCountTag(1)))
            assertEquals("no verdict before Room answers", false, exists(MAIN_EMPTY_TAG))
        } finally {
            release.countDown()
            holder.join()
        }
        emptyText("No matching items")
    }

    @Test fun aSearchOrANarrowingChipMatchingNothingSaysNoMatchingItems() {
        runBlocking { db.mediaDao().upsert(listOf(row("a.jpg", hoursAgo = 1))) }
        show()
        until { exists(headerCountTag(0)) }
        assertEquals(false, exists(MAIN_EMPTY_TAG))
        for (chip in listOf(ShowChip.SCREENSHOTS, ShowChip.FAVOURITES, ShowChip.PHOTOS)) {
            vm.setChip(chip, chip != ShowChip.PHOTOS)
            emptyText("No matching items")
            vm.setChip(chip, chip == ShowChip.PHOTOS)
            until { !exists(MAIN_EMPTY_TAG) }
        }
        vm.setQuery("zzz")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        emptyText("No matching items")
    }

    // Final review M3: one value, so "loaded" and the list can never come from different filters.
    @Test fun sectionsAreNullUntilRoomAnswersForTheCurrentFilter() {
        runBlocking { db.mediaDao().upsert(listOf(row("a.jpg", hoursAgo = 1), row("b.jpg", hoursAgo = 2), row("Screenshot_1.png", hoursAgo = 3))) }
        val seen = Collections.synchronizedList(mutableListOf<Int?>())
        val watch = CoroutineScope(Dispatchers.Unconfined).launch { vm.sections.collect { seen += it?.sumOf { s -> s.count } } }
        try {
            until { seen.lastOrNull() == 3 }
            assertEquals(null, seen.first())
            seen.clear()
            vm.setChip(ShowChip.SCREENSHOTS, true)
            until { seen.lastOrNull() == 1 }
            assertEquals(listOf(null, 1), seen.toList())
        } finally {
            watch.cancel()
        }
    }

    // Final review (Task 5 minor): a photo taken while the All grid is open is already there, no refresh needed.
    @Test fun aRowInsertedWhileTheGridShowsAppearsOnIt() {
        runBlocking { db.mediaDao().upsert(listOf(row("old.jpg", hoursAgo = 5))) }
        show()
        until { exists(tileTag(0)) && rule.onAllNodes(hasTestTag(headerCountTag(0)) and hasText("1")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(false, exists(tileTag(1)))
        runBlocking { db.mediaDao().upsert(listOf(row("new.jpg", hoursAgo = 0))) }
        until { exists(tileTag(1)) && rule.onAllNodes(hasTestTag(headerCountTag(0)) and hasText("2")).fetchSemanticsNodes().isNotEmpty() }
    }

    // Round 2 (F): a header circle whose lookup fails is a toast, never a crash.
    @Test fun aSectionToggleThatCantReadThePathsSaysSo() {
        val toasts = ToastHostState()
        cancelAndDrain(vm.viewModelScope)
        vm = newVm(toasts = toasts)
        db.openHelper.writableDatabase.execSQL("DROP TABLE media")
        vm.toggleSection(Section(0, "", 1, 0))
        until { toasts.toast.value?.message == "Couldn't select the group" }
        assertTrue(vm.selection.selected.isEmpty())
    }

    @Test fun gridShowsDayHeadersWithCountsAndTheVideoDuration() {
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    row("a.jpg", hoursAgo = 1),
                    row("clip.mp4", hoursAgo = 2, video = true),
                    row("hidden.jpg", hoursAgo = 3, hidden = true),
                    row("b.jpg", hoursAgo = 24),
                ),
            )
        }
        show()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Yesterday")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithTag(headerCountTag(0)).assertIsDisplayed().assertTextIs("2")
        rule.onNodeWithTag(headerCountTag(1)).assertTextIs("1")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("03:41")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("03:41").assertIsDisplayed()
    }

    @Test fun foldersModeHidesTheTimeline() {
        runBlocking { db.mediaDao().upsert(listOf(row("a.jpg", hoursAgo = 1))) }
        show()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Today")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("All").performClick()
        rule.onNodeWithText("Today").assertDoesNotExist()
    }

    @Test fun sectionsCarryTheirMonthForTheFastScroller() {
        runBlocking { db.mediaDao().upsert(listOf(row("a.jpg", hoursAgo = 1), row("b.jpg", hoursAgo = 24 * 40))) }
        // The screen subscribes; blocking the main thread on the flow would starve viewModelScope.
        show()
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.size == 2 }
        val sections = vm.sections.value!!
        assertEquals(listOf("Today", "Sun, 20 Sep"), sections.map { it.label })
        assertEquals(listOf("Oct 2026", "Sep 2026"), sections.map { it.scrollLabel })
    }

    @Test fun aPinchedGridSizeIsSavedOnlyInsideTheAllRange() {
        vm.setColumns(3)
        assertEquals(3, runBlocking { withTimeout(5_000) { settings.gridAll.first { it == 3 } } })
        vm.setColumns(7)
        vm.setColumns(1)
        assertEquals(3, runBlocking { settings.gridAll.first() })
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextIs(text: String) =
        this.assert(hasText(text))
}
