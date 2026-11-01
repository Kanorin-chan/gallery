package dev.kanorin.gallery.ui.timeline

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.FAST_HANDLE_TAG
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.main.MainScreen
import dev.kanorin.gallery.ui.main.MainViewModel
import dev.kanorin.gallery.ui.theme.GalleryTheme
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
import org.robolectric.shadows.ShadowToast
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec 5.2 selection on the real main screen: Room in memory, real paging, real gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SelectionUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 32, 0, 0, kl).toInstant().toEpochMilli()
    private lateinit var db: GalleryDb
    private lateinit var vm: MainViewModel
    private val sizes = 1_000L
    private val toasts = ToastHostState()

    // Today 12, Yesterday 10, Tue 28 Oct 40: item i is the i-th newest.
    private val counts = listOf(12, 10, 40)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDb::class.java)
            .allowMainThreadQueries()
            .build()
        vm = FileOps(db, {}).let { ops -> MainViewModel(db.mediaDao(), Settings(MemoryStore()), SavedStateHandle(), ops, RecycleBin(db, ops), clock = { now }, zone = { kl }, toasts = toasts) }
        var n = 0
        val rows = counts.flatMapIndexed { day, count ->
            List(count) { i -> row(n++, now - day * 86_400_000L - i * 60_000L) }
        }
        runBlocking { db.mediaDao().upsert(rows) }
        rule.setContent { GalleryTheme { ToastHost(toasts) { MainScreen(vm) } } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tileTag(11))).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() {
        cancelAndDrain(vm.viewModelScope)
        db.close()
    }

    // MediaStore rows: FileProvider can't resolve a Windows host path, so share needs MediaStore URIs here.
    private fun row(i: Int, taken: Long) = MediaEntity(
        path = "/storage/emulated/0/DCIM/Camera/$i.jpg", folderPath = "/storage/emulated/0/DCIM/Camera", name = "$i.jpg",
        mimeType = "image/jpeg", isVideo = false, dateTaken = taken, dateModified = taken, size = sizes, width = 4, height = 3,
        durationMs = 0, orientation = 0, mediaStoreId = i + 1L, hidden = false, volume = "external_primary",
    )

    private fun centre(r: DpRect): Offset = with(rule.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }
    private fun tile(i: Int): Offset = centre(rule.onNodeWithTag(tileTag(i)).getUnclippedBoundsInRoot())
    private fun gridTop(): Dp = rule.onNodeWithTag(TIMELINE_GRID_TAG).getUnclippedBoundsInRoot().top

    private fun hold(i: Int) {
        rule.onRoot().performTouchInput { down(tile(i)) }
        rule.mainClock.advanceTimeBy(400)
    }

    private fun moveTo(i: Int) {
        val p = tile(i)
        rule.onRoot().performTouchInput { moveTo(p) }
    }

    private fun lift() = rule.onRoot().performTouchInput { up() }

    private fun tap(i: Int) {
        val p = tile(i)
        rule.onRoot().performTouchInput { click(p) }
    }

    private fun selectedText(): String = rule.onNode(hasText("selected", substring = true))
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun size(n: Int) = Formatter.formatShortFileSize(rule.activity, n * sizes)

    @Test fun holdingATile350MsSelectsIt() {
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { down(tile(0)) }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("1 selected").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(100)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("1 selected").assertExists()
        rule.onNodeWithText("Search photos").assertDoesNotExist()
        rule.onNodeWithTag(tileTag(0)).assertIsSelected()
        lift()
        rule.onNodeWithText("1 selected").assertExists()
    }

    @Test fun holdAndDragSelectsTheRangeThenATapAddsOne() {
        hold(0)
        moveTo(5)
        rule.onNodeWithText("6 selected").assertExists()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(size(6))).fetchSemanticsNodes().isNotEmpty() }
        (0..5).forEach { rule.onNodeWithTag(tileTag(it)).assertIsSelected() }
        rule.onNodeWithTag(tileTag(6)).assertIsNotSelected()
        lift()
        tap(7)
        rule.onNodeWithText("7 selected").assertExists()
        rule.onNodeWithTag(tileTag(7)).assertIsSelected()
        rule.onNodeWithTag(tileTag(6)).assertIsNotSelected()
    }

    @Test fun movingBackUnselectsAndHoldingASelectedTileDeselectsItsRange() {
        tap(9) // not selecting yet: a tap does nothing
        rule.onNodeWithText("Search photos").assertExists()
        hold(0)
        moveTo(5)
        moveTo(2)
        rule.onNodeWithText("3 selected").assertExists()
        rule.onNodeWithTag(tileTag(4)).assertIsNotSelected()
        lift()
        hold(1)
        moveTo(3)
        lift()
        rule.onNodeWithText("1 selected").assertExists()
        rule.onNodeWithTag(tileTag(0)).assertIsSelected()
        rule.onNodeWithTag(tileTag(1)).assertIsNotSelected()
        // Deselecting the last one by tap leaves selection.
        tap(0)
        rule.onNodeWithText("Search photos").assertExists()
    }

    @Test fun holdingTheOnlySelectedTileEmptiesSelectionWhenTheFingerLifts() {
        hold(4)
        lift()
        hold(4)
        rule.onNodeWithText("0 selected").assertExists()
        lift()
        rule.onNodeWithText("Search photos").assertExists()
    }

    @Test fun theHeaderCircleSelectsTheWholeSectionAndClearsItWhenFull() {
        rule.onNodeWithTag(headerSelectTag(0)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("12 selected")).fetchSemanticsNodes().isNotEmpty() }
        (0..11).forEach { rule.onNodeWithTag(tileTag(it)).assertIsSelected() }
        rule.onNodeWithTag(tileTag(12)).assertIsNotSelected()
        rule.onNodeWithTag(headerSelectTag(0)).assertIsSelected()
        rule.onNodeWithTag(headerSelectTag(0)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Search photos")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun closeClearsThePillReturnsAndTheGridNeverMoves() {
        val top = gridTop()
        hold(0)
        lift()
        rule.onNodeWithText("1 selected").assertExists()
        assertEquals(top, gridTop())
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Search photos").assertExists()
        rule.onNodeWithText("1 selected").assertDoesNotExist()
        assertEquals(top, gridTop())
        rule.onNodeWithTag(tileTag(0)).assertIsNotSelected()
    }

    @Test fun selectAllTakesEveryItemIncludingUnloadedOnesAndTogglesOff() {
        hold(0)
        lift()
        rule.onNodeWithContentDescription("Select all").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("62 selected")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Select all").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Search photos")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun shareOpensTheChooserWithTheSelection() {
        hold(0)
        moveTo(5)
        lift()
        rule.onNodeWithContentDescription("Share").performClick()
        rule.waitUntil(5_000) { shadowOf(rule.activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        assertEquals(6, send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!.size)
    }

    @Test fun sharingMoreThan500ShowsAToastAndSendsNothing() {
        hold(0)
        lift()
        // Older than everything shown, so the visible tiles don't move.
        runBlocking { db.mediaDao().upsert(List(501 - counts.sum()) { row(1_000 + it, now - 10 * 86_400_000L - it * 60_000L) }) }
        rule.onNodeWithContentDescription("Select all").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("501 selected")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Share").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("You can share up to 500 items at once")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(rule.activity).nextStartedActivity)
    }

    @Test fun backLeavesSelectionFirst() {
        hold(0)
        lift()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Search photos").assertExists()
        rule.onNodeWithText("1 selected").assertDoesNotExist()
    }

    @Test fun movingBeforeTheHoldFiresIsAScroll() {
        val before = rule.onNodeWithTag(tileTag(4)).getUnclippedBoundsInRoot().top
        val p = tile(4)
        rule.onRoot().performTouchInput { down(p); moveBy(Offset(0f, -40f)) }
        rule.mainClock.advanceTimeBy(400)
        lift()
        rule.onNodeWithText("Search photos").assertExists()
        // The grid took it: past the touch slop the content moved up with the finger.
        val after = rule.onNodeWithTag(tileTag(4)).getUnclippedBoundsInRoot().top
        assertTrue("tile 4 top $before -> $after", after < before)
    }

    @Test fun twoFingersNeverStartAHold() {
        val a = tile(0)
        val b = tile(1)
        rule.onRoot().performTouchInput { down(0, a); down(1, b) }
        rule.mainClock.advanceTimeBy(500)
        rule.onRoot().performTouchInput { up(0); up(1) }
        rule.onNodeWithText("Search photos").assertExists()
    }

    @Test fun holdingTheVisibleFastScrollHandleDoesNotSelect() {
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 200f, durationMillis = 200) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(FAST_HANDLE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        val h = centre(rule.onNodeWithTag(FAST_HANDLE_TAG).getUnclippedBoundsInRoot())
        rule.onRoot().performTouchInput { down(h) }
        rule.mainClock.advanceTimeBy(400)
        lift()
        rule.onNodeWithText("Search photos").assertExists()
    }

    @Test fun draggingIntoTheBottomEdgeAutoScrollsAndKeepsSelecting() {
        hold(0)
        val bottom = with(rule.density) { rule.onNodeWithTag(TIMELINE_GRID_TAG).getUnclippedBoundsInRoot().bottom.toPx() }
        val x = tile(0).x
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { moveTo(Offset(x, bottom - with(rule.density) { 10.dp.toPx() })) }
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        val n = selectedText().substringBefore(" selected").replace(",", "").toInt()
        // About 24 tiles fit on screen; auto-scroll carried the range well past them.
        assertTrue("selected $n", n > 30)
    }
}
