package dev.kanorin.gallery.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.ui.common.SelectionState
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException

/** Selection on the bare grid, where the scroll state and the paths source are in reach. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SelectionGridTest {
    @get:Rule val rule = createComposeRule()

    private val selection = SelectionState()
    private lateinit var state: LazyGridState
    private var shown by mutableStateOf(true)

    private fun path(i: Int) = "/nope/$i.jpg"

    private fun rows(n: Int) = (0 until n).map { i ->
        MediaEntity(
            path = path(i), folderPath = "/nope", name = "$i.jpg", mimeType = "image/jpeg", isVideo = false,
            dateTaken = 1, dateModified = 1, size = 1, width = 4, height = 3, durationMs = 0, orientation = 0,
            mediaStoreId = null, hidden = false, volume = "external_primary",
        )
    }

    /** [sections] may count more items than [loaded] holds: the rest stay placeholders. */
    private fun show(sections: List<Int>, loaded: Int, columns: Int, pathsInOrder: suspend () -> List<String>) {
        var first = 0
        val layout = TimelineLayout(sections.mapIndexed { i, n -> Section(i.toLong(), "s$i", n, first).also { first += n } })
        val data = rows(loaded)
        rule.setContent {
            state = rememberLazyGridState()
            val items = remember { flowOf(PagingData.from(data)) }.collectAsLazyPagingItems()
            GalleryTheme {
                Box(Modifier.fillMaxSize()) {
                    if (shown) TimelineGrid(layout, items, columns, state = state, selection = selection, pathsInOrder = pathsInOrder)
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tileTag(0))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun centre(r: DpRect) = with(rule.density) { Offset(((r.left + r.right) / 2).toPx(), ((r.top + r.bottom) / 2).toPx()) }
    private fun tileBounds(i: Int) = rule.onNodeWithTag(tileTag(i)).getUnclippedBoundsInRoot()
    private fun tile(i: Int) = centre(tileBounds(i))

    private fun hold(i: Int) {
        val p = tile(i)
        rule.onRoot().performTouchInput { down(p) }
        rule.mainClock.advanceTimeBy(400)
    }

    @Test fun anUpwardDragSelectFromAScrolledGridDoesNotScrollIt() {
        show(listOf(60, 60, 60), loaded = 180, columns = 4) { emptyList() }
        rule.runOnUiThread { runBlocking { state.scrollToItem(30) } }
        rule.waitForIdle()
        val index = state.firstVisibleItemIndex
        val offset = state.firstVisibleItemScrollOffset
        // Both ends stay clear of the 50 dp edge zones, so any scroll would be a leak.
        val upper = tileBounds(32)
        val lower = tileBounds(44)
        assertTrue("$upper", upper.top > 50.dp && lower.bottom < 590.dp)

        hold(44)
        val p = tile(32)
        rule.onRoot().performTouchInput { moveTo(p); up() }
        rule.waitForIdle()

        assertEquals(index to offset, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        assertEquals((32..44).map(::path).toSet(), selection.selected.toSet())
    }

    @Test fun leavingCompositionWhileThePathsAreStillLoadingEndsTheDrag() {
        val never = CompletableDeferred<List<String>>()
        // 30 items in the layout, 20 loaded: a drag past item 19 needs the ordered paths.
        show(listOf(30), loaded = 20, columns = 6) { never.await() }
        hold(5)
        val p = tile(25)
        rule.onRoot().performTouchInput { moveTo(p); up() }
        rule.waitForIdle()
        assertTrue("still waiting on the paths", selection.dragging)

        shown = false
        rule.waitForIdle()
        assertFalse(selection.dragging)
        assertEquals((5..19).map(::path).toSet(), selection.selected.toSet())
    }

    @Test fun aFailedPathsFetchEndsTheDragWithoutACrash() {
        show(listOf(30), loaded = 20, columns = 6) { throw IOException("database gone") }
        hold(5)
        val p = tile(25)
        rule.onRoot().performTouchInput { moveTo(p) }
        rule.waitForIdle()
        assertFalse(selection.dragging)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertFalse(selection.dragging)
        assertEquals((5..19).map(::path).toSet(), selection.selected.toSet())
    }
}
