package dev.kanorin.gallery.ui.main

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.GalleryNavHost
import dev.kanorin.gallery.Routes
import dev.kanorin.gallery.data.BinEntity
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.ShowChip
import dev.kanorin.gallery.logic.SortField
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.PILL_MENU_TAG
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.timeline.headerCountTag
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** Task 11: the ⋮ sheet, the filter state behind it, and the search pill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class MenuSheetTest {
    @get:Rule val rule = createComposeRule()

    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 32, 0, 0, kl).toInstant().toEpochMilli()
    private lateinit var db: GalleryDb
    private lateinit var vm: MainViewModel
    private val saved = SavedStateHandle()
    private val routes = mutableListOf<String>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        vm = newVm(saved)
    }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    private fun newVm(handle: SavedStateHandle) = FileOps(db, {}).let { ops ->
        MainViewModel(
            db.mediaDao(), Settings(MemoryStore()), handle, ops, RecycleBin(db, ops),
            clock = { now }, zone = { kl }, binCounts = db.binDao().count(),
        )
    }

    private fun show() = rule.setContent { GalleryTheme { MainScreen(vm, onNavigate = { routes += it }) } }

    private fun row(name: String, hoursAgo: Long, folder: String = "/storage/emulated/0/DCIM/Camera", hidden: Boolean = false) = MediaEntity(
        path = "$folder/$name", folderPath = folder, name = name, mimeType = "image/jpeg", isVideo = false,
        dateTaken = now - hoursAgo * 3_600_000, dateModified = 1, size = 1, width = 4, height = 3,
        durationMs = 0, orientation = 0, mediaStoreId = null, hidden = hidden, volume = "external_primary",
    )

    private fun insert(vararg rows: MediaEntity) = runBlocking { db.mediaDao().upsert(rows.toList()) }

    private fun openSheet() {
        rule.onNodeWithTag(PILL_MENU_TAG).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SETTINGS_ROW_TAG)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sheetGone() = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SETTINGS_ROW_TAG)).fetchSemanticsNodes().isEmpty() }

    private fun field() = rule.onNode(hasSetTextAction())

    // The debounce waits on the main looper's clock, which only moves when told.
    private fun pause(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        rule.waitForIdle()
    }

    // ---- the sheet ----

    @Test fun theSheetShowsEverySectionOfTheMockup() {
        show()
        openSheet()
        listOf("SORT BY", "Date taken", "Date modified", "Name", "Size", "Length", "SHOW", "GROUP BY", "Day", "Month", "Year", "None", "Recycle bin", "Hidden folders", "Settings")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithTag(SORT_ORDER_TAG).assertTextEquals("newest first ↓")
        rule.onNodeWithTag(sortTag(SortField.DATE_TAKEN)).assertIsSelected()
        rule.onNodeWithTag(chipTag(ShowChip.PHOTOS)).assertIsOn().assert(hasText("Photos"))
        rule.onNodeWithTag(chipTag(ShowChip.SCREENSHOTS)).assertIsOff().assert(hasText("Screenshots"))
        rule.onNodeWithTag(groupTag(GroupBy.DAY)).assertIsSelected()
    }

    @Test fun theRowsShowTheBinAndHiddenFolderCounts() {
        runBlocking {
            db.binDao().insert(BinEntity("/bin/a", "/o/a", 1, 1, "image/jpeg", false, 0))
            db.binDao().insert(BinEntity("/bin/b", "/o/b", 2, 1, "image/jpeg", false, 0))
            db.binDao().insert(BinEntity("/bin/held", "/o/h", 3, 1, "image/jpeg", false, 0, pendingErase = true))
        }
        insert(row("h.jpg", 1, folder = "/storage/emulated/0/Pictures/Stickers", hidden = true))
        show()
        openSheet()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("2 items ›")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("1 ›")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun eachRowNavigatesAndClosesTheSheet() {
        show()
        listOf(BIN_ROW_TAG to "bin", HIDDEN_ROW_TAG to "hidden-folders", SETTINGS_ROW_TAG to "settings").forEach { (tag, route) ->
            routes.clear()
            openSheet()
            rule.onNodeWithTag(tag).performClick()
            assertEquals(listOf(route), routes)
            sheetGone()
        }
    }

    @Test fun theSettingsRouteIsATitledEmptyScreenWithABackArrow() {
        rule.setContent { GalleryTheme { GalleryNavHost(Routes.SETTINGS) } }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    // ---- sort / show / group ----

    // Kano 2026-10-31: the sort rows "keep shifting when i tap another"; a tick changed its chip's size.
    @Test fun pickingASortOrAChipMovesNothing() {
        show()
        openSheet()
        val rows = SortField.entries.map { rule.onNodeWithTag(sortTag(it)).getUnclippedBoundsInRoot() }
        val chips = ShowChip.entries.map { rule.onNodeWithTag(chipTag(it)).getUnclippedBoundsInRoot() }
        rule.onNodeWithTag(sortTag(SortField.SIZE)).performClick()
        rule.onNodeWithTag(chipTag(ShowChip.SCREENSHOTS)).performClick()
        rule.onNodeWithTag(chipTag(ShowChip.GIFS)).performClick()
        assertEquals(rows, SortField.entries.map { rule.onNodeWithTag(sortTag(it)).getUnclippedBoundsInRoot() })
        assertEquals(chips, ShowChip.entries.map { rule.onNodeWithTag(chipTag(it)).getUnclippedBoundsInRoot() })
    }

    @Test fun pickingLengthSortsLongestFirstWithNoGroups() {
        show()
        openSheet()
        rule.onNodeWithTag(sortTag(SortField.DURATION)).performClick()
        assertEquals(SortField.DURATION, vm.filter.value.sort)
        assertEquals(true, vm.filter.value.descending)
        rule.onNodeWithTag(SORT_ORDER_TAG).assertTextEquals("longest first ↓")
        rule.onNodeWithTag(groupTag(GroupBy.NONE)).assertIsSelected()
    }

    @Test fun pickingNameSortsAtoZAndFreezesGroupByAtNone() {
        insert(row("b.jpg", 1), row("a.jpg", 2))
        show()
        openSheet()
        rule.onNodeWithTag(sortTag(SortField.NAME)).performClick()
        assertEquals(SortField.NAME, vm.filter.value.sort)
        assertEquals(false, vm.filter.value.descending)
        rule.onNodeWithTag(SORT_ORDER_TAG).assertTextEquals("A to Z ↓")
        rule.onNodeWithTag(groupTag(GroupBy.NONE)).assertIsSelected()
        rule.onNodeWithTag(groupTag(GroupBy.DAY)).assertIsNotEnabled()
        rule.onNodeWithTag(groupTag(GroupBy.NONE)).assertIsNotEnabled()

        rule.onNodeWithTag(sortTag(SortField.DATE_MODIFIED)).performClick()
        rule.onNodeWithTag(groupTag(GroupBy.DAY)).assertIsEnabled().assertIsSelected()
    }

    @Test fun theOrderLabelFlipsTheSort() {
        show()
        openSheet()
        rule.onNodeWithTag(SORT_ORDER_TAG).performClick()
        assertEquals(false, vm.filter.value.descending)
        rule.onNodeWithTag(SORT_ORDER_TAG).assertTextEquals("oldest first ↑")
    }

    @Test fun showChipsTogglePhotosVideosGifsInclusionAndTheTwoNarrowers() {
        show()
        openSheet()
        rule.onNodeWithTag(chipTag(ShowChip.VIDEOS)).performClick()
        rule.onNodeWithTag(chipTag(ShowChip.FAVOURITES)).performClick()
        rule.onNodeWithTag(chipTag(ShowChip.SCREENSHOTS)).performClick()
        val f = vm.filter.value
        assertEquals(listOf(true, false, true, true, true), listOf(f.photos, f.videos, f.gifs, f.screenshotsOnly, f.favouritesOnly))
        rule.onNodeWithTag(chipTag(ShowChip.VIDEOS)).assertIsOff()
        rule.onNodeWithTag(chipTag(ShowChip.FAVOURITES)).assertIsOn()
    }

    // Final review I3: one of Photos / Videos / GIFs always stays on; its chip can't be turned off.
    @Test fun theLastKindChipOnIsDisabled() {
        show()
        openSheet()
        rule.onNodeWithTag(chipTag(ShowChip.VIDEOS)).performClick()
        rule.onNodeWithTag(chipTag(ShowChip.PHOTOS)).assertIsEnabled()
        rule.onNodeWithTag(chipTag(ShowChip.GIFS)).performClick()
        rule.onNodeWithTag(chipTag(ShowChip.PHOTOS)).assertIsOn().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue(vm.filter.value.photos)
        rule.onNodeWithTag(chipTag(ShowChip.GIFS)).assertIsOff().assertIsEnabled().performClick()
        rule.onNodeWithTag(chipTag(ShowChip.PHOTOS)).assertIsEnabled()
    }

    @Test fun monthGroupingChangesTheTimelineHeaders() {
        insert(row("a.jpg", 1), row("b.jpg", 24 * 3))
        show()
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.size == 2 }
        openSheet()
        rule.onNodeWithTag(groupTag(GroupBy.MONTH)).performClick()
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.size == 1 }
        assertEquals("Oct 2026", vm.sections.value!!.single().label)
    }

    @Test fun theChoicesSurviveARecreatedViewModel() {
        vm.setSort(SortField.SIZE)
        vm.flipOrder()
        vm.setGroupBy(GroupBy.YEAR)
        vm.setChip(ShowChip.GIFS, false)
        vm.setChip(ShowChip.FAVOURITES, true)
        val again = newVm(saved)
        try {
            val f = again.filter.value
            assertEquals(SortField.SIZE, f.sort)
            assertEquals(false, f.descending)
            assertEquals(GroupBy.YEAR, f.groupBy)
            assertEquals(false, f.gifs)
            assertEquals(true, f.favouritesOnly)
        } finally {
            again.viewModelScope.cancel()
        }
    }

    // ---- search ----

    @Test fun typingFiltersTheTimelineAfterAPauseAndClearingRestoresIt() {
        insert(row("beach1.jpg", 1), row("beach2.jpg", 2), row("city.jpg", 3))
        show()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(headerCountTag(0)) and hasText("3")).fetchSemanticsNodes().isNotEmpty() }

        field().performTextInput("beach")
        pause(300)
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.filter.value.search?.text == "beach" }
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.sumOf { it.count } == 2 }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(headerCountTag(0)) and hasText("2")).fetchSemanticsNodes().isNotEmpty() }

        vm.setQuery("")
        pause(1) // blank applies at once
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.filter.value.search == null }
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.sumOf { it.count } == 3 }
    }

    @Test fun searchWaitsOutTheDebounceAndOnlyTheLastKeystrokeCounts() {
        pause(1) // the collector is up
        vm.setQuery("be")
        pause(200)
        vm.setQuery("beach")
        pause(200)
        assertNull("250 ms of quiet not reached", vm.filter.value.search)
        pause(100)
        assertEquals("beach", vm.filter.value.search?.text)
    }

    @Test fun aTypedDateBecomesARangeNotText() {
        insert(row("a.jpg", 1), row("b.jpg", 24 * 40))
        show()
        vm.setQuery("2026-10-30")
        pause(300)
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.filter.value.search != null }
        val s = vm.filter.value.search!!
        assertNull(s.text)
        assertTrue(s.from != null && s.until != null)
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.sections.value?.sumOf { it.count } == 1 }
    }

    @Test fun foldersModeDoesNotRefilterTheAllView() {
        insert(row("a.jpg", 1))
        show()
        rule.onNodeWithText("All").performClick() // to Folders
        vm.setQuery("zzz")
        pause(600)
        assertNull(vm.filter.value.search)
        rule.onNodeWithText("Folders").performClick() // back to All
        pause(300)
        rule.waitUntil(10_000) { rule.waitForIdle(); vm.filter.value.search?.text == "zzz" }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun theKeyboardSearchActionHidesTheKeyboard() {
        var hidden = 0
        val fake = object : SoftwareKeyboardController {
            override fun hide() { hidden++ }
            override fun show() = Unit
        }
        rule.setContent { GalleryTheme { CompositionLocalProvider(LocalSoftwareKeyboardController provides fake) { MainScreen(vm) } } }
        field().performClick()
        field().assertIsFocused()
        field().performImeAction()
        // Robolectric hands focus straight back to the lone field, so the focus release itself is not asserted here.
        assertEquals("keyboard hidden once", 1, hidden)
    }
}
