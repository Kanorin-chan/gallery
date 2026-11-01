package dev.kanorin.gallery.ui.timeline

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.logic.PinchStepper
import dev.kanorin.gallery.logic.Section
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.logic.TimelineLayout.Entry
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.thumbs.Thumb
import dev.kanorin.gallery.ui.common.DragSelect
import dev.kanorin.gallery.ui.common.FastScroller
import dev.kanorin.gallery.ui.common.SelectableTile
import dev.kanorin.gallery.ui.common.SelectionState
import dev.kanorin.gallery.ui.common.VideoBadge
import dev.kanorin.gallery.ui.common.ZoomPill
import dev.kanorin.gallery.ui.common.pinchColumns
import dev.kanorin.gallery.ui.common.requestAnchorAt
import dev.kanorin.gallery.ui.common.selectGestures
import dev.kanorin.gallery.ui.theme.GalleryColors
import java.util.Locale

fun headerCountTag(section: Int) = "header-count-$section"
fun tileTag(itemIndex: Int) = "tile-$itemIndex"
fun headerSelectTag(section: Int) = "header-select-$section"
const val TIMELINE_GRID_TAG = "timeline-grid"

private val TileGap = 2.dp
private const val HEADER = 0
private const val TILE = 1
private val PillLift = 22.dp

private val LabelStyle = TextStyle(color = GalleryColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
private val CountStyle = TextStyle(color = GalleryColors.Muted, fontSize = 12.sp)

/**
 * Day headers span the row; tiles come from [items] by item index. Pinch changes the column count within
 * [columnRange] (the entry under the fingers stays put) and reports it to [onColumns]; null = no pinch.
 * With a [selection]: hold / hold-drag / tap select tiles, and a header's circle calls [onToggleSection].
 * [pathsInOrder] gives every item's path in grid order, for a drag that reaches tiles never loaded.
 * No item keys: path is the PK so keys couldn't collide, but keyed items anchor to the old first item, so a new
 * photo arriving at the top would land above the viewport (spec 7) unless a stick-to-top rule were added.
 */
@Composable
fun TimelineGrid(
    layout: TimelineLayout,
    items: LazyPagingItems<MediaEntity>,
    columns: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    onColumns: ((Int) -> Unit)? = null,
    columnRange: IntRange = Settings.GRID_ALL_RANGE,
    clock: () -> Long = { SystemClock.uptimeMillis() },
    selection: SelectionState? = null,
    onToggleSection: (Section) -> Unit = {},
    pathsInOrder: suspend () -> List<String> = { emptyList() },
    /** Extra room below the last row, for something floating over the grid's bottom. */
    bottomClearance: Dp = 0.dp,
    /** true = the grid pill is always shown and its numbers are tappable (inside a folder); false = it flashes on a pinch. */
    tappablePill: Boolean = false,
    /** Draws the pill with an accent ring, to point at it. */
    pillHighlight: Boolean = false,
    /** A tap on a loaded tile while not selecting, with its item index. It rides the hold gesture's tap (a clickable would eat the hold). */
    onOpen: (index: Int, item: MediaEntity) -> Unit = { _, _ -> },
) {
    // Drawn count. A pinch sets it in the same frame as its anchor; [columns] catches up once saved.
    var cols by remember { mutableIntStateOf(columns) }
    // The pinch's latest ask until [columns] reports it back; older asks echoing meanwhile are ignored.
    var asked by remember { mutableStateOf<Int?>(null) }
    val stepper = remember(columnRange) { PinchStepper(columnRange.first, columnRange.last, cols) }
    val scope = rememberCoroutineScope()
    val drag = remember(selection) { selection?.let { DragSelect(it, scope) } }
    DisposableEffect(drag) { onDispose { drag?.dispose() } }
    SideEffect {
        drag?.layout = layout
        drag?.loadedKey = { i -> if (i < items.itemCount) items.peek(i)?.path else null }
        drag?.pathsInOrder = pathsInOrder
        drag?.onOpen = { i -> if (i < items.itemCount) items.peek(i)?.let { onOpen(i, it) } }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val gapPx = with(LocalDensity.current) { TileGap.roundToPx() }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Widest cell GridCells.Fixed hands out (it spreads the remainder px).
        val tilePx = remember(width, cols, gapPx) { (width - (cols - 1) * gapPx + cols - 1) / cols }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The drag-select edge zone sits above whatever floats over the grid's bottom too.
        val bottomPx = with(LocalDensity.current) { (bottom + bottomClearance).roundToPx() }

        SideEffect { if (asked == columns) asked = null }
        LaunchedEffect(columns) {
            if (asked == null && columns != cols) {
                // Changed elsewhere (e.g. a tapped grid pill): hold the entry a third of the way down.
                state.requestAnchorAt(Offset(width / 2f, height / 3f))
                stepper.columns = columns
                cols = columns
            }
        }

        val pinch = if (onColumns == null) Modifier else Modifier.pinchColumns(stepper) { n, focus ->
            state.requestAnchorAt(focus)
            cols = n
            asked = n
            onColumns(n)
        }
        // Inside the pinch: the pinch sees two fingers first, the hold sees one.
        val select = if (drag == null) Modifier else Modifier.selectGestures(state, bottomPx, drag)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            // With the header's 12/6 dp padding and the 2 dp line gap this gives the mockup's 14 dp above, 8 dp below.
            contentPadding = PaddingValues(top = if (layout.headers) TileGap else 0.dp, bottom = bottom + bottomClearance),
            modifier = Modifier.fillMaxSize().testTag(TIMELINE_GRID_TAG).then(pinch).then(select),
        ) {
            items(
                count = layout.totalEntries,
                span = { i -> if (layout.entryAt(i) is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { i -> if (layout.entryAt(i) is Entry.Header) HEADER else TILE },
            ) { i ->
                when (val e = layout.entryAt(i)) {
                    is Entry.Header -> {
                        val section = layout.sections[e.section]
                        val on by remember(selection, section) {
                            derivedStateOf { selection != null && allSelected(items, section, selection) }
                        }
                        DayHeader(section, e.section, on, if (selection == null) null else { { onToggleSection(section) } })
                    }
                    is Entry.Item -> {
                        // Sections and pages come from two queries; tolerate one being a step ahead.
                        val item = if (e.itemIndex < items.itemCount) items[e.itemIndex] else null
                        val path = item?.path
                        // Per tile, so a selection change recomposes only the tiles it flips.
                        val selected by remember(selection, path) {
                            derivedStateOf { path != null && selection != null && path in selection.selected }
                        }
                        Tile(item, tilePx, selection?.active == true, selected, Modifier.testTag(tileTag(e.itemIndex)))
                    }
                }
            }
        }
        FastScroller(
            state,
            layout,
            sectionLabel = { layout.sections[it].scrollLabel },
            modifier = Modifier.align(Alignment.TopEnd).padding(bottom = bottom),
            clock = clock,
        )
        if (onColumns != null) {
            ZoomPill(
                columnRange.first, columnRange.last, cols, visible = true, onPick = if (tappablePill) onColumns else null,
                highlight = pillHighlight,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + PillLift),
            )
        }
    }
}

/** Lit only when every item is known selected; an item on an unloaded page counts as not. */
private fun allSelected(items: LazyPagingItems<MediaEntity>, section: Section, selection: SelectionState): Boolean {
    val end = section.firstItemIndex + section.count
    if (section.count == 0 || end > items.itemCount) return false
    for (i in section.firstItemIndex until end) {
        val path = items.peek(i)?.path ?: return false
        if (path !in selection.selected) return false
    }
    return true
}

@Composable
private fun DayHeader(section: Section, index: Int, on: Boolean, onSelect: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(section.label, style = LabelStyle, maxLines = 1, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(8.dp))
        Text(
            String.format(Locale.ENGLISH, "%,d", section.count),
            style = CountStyle,
            maxLines = 1,
            modifier = Modifier.alignByBaseline().testTag(headerCountTag(index)),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .then(if (onSelect == null) Modifier else Modifier.selectable(on, role = Role.Checkbox, onClick = onSelect))
                .testTag(headerSelectTag(index))
                .background(if (on) GalleryColors.Accent else Color.Transparent)
                .border(1.5.dp, if (on) GalleryColors.Accent else GalleryColors.SelectRing, CircleShape),
        )
    }
}

@Composable
private fun Tile(item: MediaEntity?, sizePx: Int, selecting: Boolean, selected: Boolean, modifier: Modifier) {
    SelectableTile(selecting, selected, modifier) {
        if (item == null) {
            Box(Modifier.fillMaxSize().background(GalleryColors.Surface2))
        } else {
            Thumb(item, sizePx, Modifier.fillMaxSize())
            if (item.isVideo) VideoBadge(item.durationMs, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 3.dp))
        }
    }
}
