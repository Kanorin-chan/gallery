package dev.kanorin.gallery.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.TimelineGrouping
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.ui.common.FAST_BUBBLE_TAG
import dev.kanorin.gallery.ui.common.FAST_HANDLE_TAG
import dev.kanorin.gallery.ui.common.ZOOM_PILL_TAG
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class TimelineGridGestureTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.ofHours(8)
    private val now = ZonedDateTime.of(2026, 10, 30, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val months = listOf(
        ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, zone),
        ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, zone),
        ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, zone),
    ).map { it.toInstant().toEpochMilli() }
    private val perSection = 60
    private val layout = TimelineLayout(
        months.mapIndexed { i, key ->
            Section(key, "s$i", perSection, i * perSection, TimelineGrouping.label(key, GroupBy.MONTH, now, zone))
        },
    )
    private val rows = (0 until perSection * months.size).map { i ->
        MediaEntity(
            path = "/nope/$i.jpg", folderPath = "/nope", name = "$i.jpg", mimeType = "image/jpeg", isVideo = false,
            dateTaken = months[i / perSection], dateModified = 1, size = 1, width = 4, height = 3, durationMs = 0,
            orientation = 0, mediaStoreId = null, hidden = false, volume = "external_primary",
        )
    }

    private var columns by mutableIntStateOf(4)
    private val asked = mutableListOf<Int>()
    private lateinit var state: LazyGridState

    private fun show() {
        rule.setContent {
            state = rememberLazyGridState()
            val items = remember { flowOf(PagingData.from(rows)) }.collectAsLazyPagingItems()
            GalleryTheme {
                Box(Modifier.fillMaxSize()) {
                    TimelineGrid(
                        layout, items, columns, state = state,
                        onColumns = { asked += it; columns = it },
                        clock = { rule.mainClock.currentTime },
                    )
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tileTag(20))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun centrePx(r: DpRect): Pair<Float, Float> =
        with(rule.density) { ((r.left + r.right) / 2).toPx() to ((r.top + r.bottom) / 2).toPx() }

    private fun scrollTo(index: Int) = rule.runOnUiThread { runBlocking { state.scrollToItem(index) } }

    @Test fun pinchOutKeepsTheItemUnderTheFingersInPlace() {
        show()
        val before = rule.onNodeWithTag(tileTag(20)).getUnclippedBoundsInRoot()
        val (cx, cy) = centrePx(before)

        // 80 px apart -> 110 px: a 1.375x spread, one step.
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performTouchInput {
            pinch(Offset(cx - 40f, cy), Offset(cx - 55f, cy), Offset(cx + 40f, cy), Offset(cx + 55f, cy), durationMillis = 300)
        }
        rule.waitForIdle()

        assertEquals(listOf(3), asked)
        val after = rule.onNodeWithTag(tileTag(20)).getUnclippedBoundsInRoot()
        assertTrue("tiles should be 3 across, got ${after.width}", after.width > 110.dp)
        val rowHeight = after.height + 2.dp
        assertTrue(
            "item 20 moved from ${before.top} to ${after.top} (row $rowHeight)",
            abs((after.top - before.top).value) <= rowHeight.value,
        )
        // Without the anchor, 3 columns would put item 20 on row 6, below the viewport.
        assertTrue(after.top > 300.dp)
    }

    @Test fun pinchFlashesTheGridPill() {
        show()
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertDoesNotExist()
        val b = rule.onNodeWithTag(tileTag(10)).getUnclippedBoundsInRoot()
        val (cx, cy) = centrePx(b)
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performTouchInput {
            pinch(Offset(cx - 60f, cy), Offset(cx - 40f, cy), Offset(cx + 60f, cy), Offset(cx + 40f, cy), durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals(listOf(5), asked)
        rule.onNodeWithTag(ZOOM_PILL_TAG).assert(hasText("Grid", substring = true))
        rule.mainClock.advanceTimeBy(1_600)
        rule.onNodeWithTag(ZOOM_PILL_TAG).assertDoesNotExist()
    }

    @Test fun handleHiddenAtStartShowsOnScrollAndFades1500MsLater() {
        show()
        rule.onNodeWithTag(FAST_HANDLE_TAG).assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        scrollTo(8)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(FAST_HANDLE_TAG).assertExists()
        rule.mainClock.advanceTimeBy(1_300)
        rule.onNodeWithTag(FAST_HANDLE_TAG).assertExists()
        // 1.5 s wait + 0.25 s fade.
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag(FAST_HANDLE_TAG).assertDoesNotExist()
    }

    @Test fun aHiddenHandleLetsTheGridHaveTheTouch() {
        show()
        val w = with(rule.density) { 360.dp.toPx() }
        val edge = w - with(rule.density) { 11.dp.toPx() }
        // Dragging the handle down would scroll forward; the grid itself can't scroll above the top.
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performTouchInput { swipe(Offset(edge, 20f), Offset(edge, 600f), 300) }
        rule.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        // And an upward swipe on the same strip scrolls the grid.
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performTouchInput { swipe(Offset(edge, 900f), Offset(edge, 300f), 300) }
        rule.waitForIdle()
        assertTrue(state.firstVisibleItemIndex > 0)
    }

    @Test fun aFadingHandleCantBePressed() {
        show()
        rule.mainClock.autoAdvance = false
        scrollTo(12)
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(FAST_HANDLE_TAG).assertExists()
        // Hidden by the 1.5 s rule but still on screen, mid-fade.
        rule.mainClock.advanceTimeBy(1_560)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val handle = rule.onNodeWithTag(FAST_HANDLE_TAG).getUnclippedBoundsInRoot()
        val (hx, hy) = centrePx(handle)
        // A handle drag down would scroll forward; the grid takes a downward swipe as scrolling back.
        rule.onNodeWithTag(TIMELINE_GRID_TAG).performTouchInput { swipe(Offset(hx, hy), Offset(hx, hy + 400f), 200) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("grid at ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex < 12)
    }

    @Test fun draggingTheHandleShowsTheMonthAndTheBottomShowsTheLastOne() {
        show()
        scrollTo(1)
        rule.waitForIdle()
        val track = with(rule.density) { (640.dp - 48.dp).toPx() }
        rule.onNodeWithTag(FAST_HANDLE_TAG).performTouchInput { down(center) }
        rule.onNodeWithTag(FAST_BUBBLE_TAG).assert(hasText("Oct 2026"))

        rule.onNodeWithTag(FAST_HANDLE_TAG).performTouchInput { moveBy(Offset(0f, track / 2)) }
        rule.waitForIdle()
        rule.onNodeWithTag(FAST_BUBBLE_TAG).assert(hasText("Sep 2026"))
        assertTrue(state.firstVisibleItemIndex in 61..121)

        rule.onNodeWithTag(FAST_HANDLE_TAG).performTouchInput { moveBy(Offset(0f, track)) }
        rule.waitForIdle()
        rule.onNodeWithTag(FAST_BUBBLE_TAG).assert(hasText("Jan 2025"))
        assertFalse(state.canScrollForward)

        rule.onNodeWithTag(FAST_HANDLE_TAG).performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag(FAST_BUBBLE_TAG).assertDoesNotExist()
    }
}
