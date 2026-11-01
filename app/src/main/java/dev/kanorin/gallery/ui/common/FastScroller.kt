package dev.kanorin.gallery.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanorin.gallery.logic.FastScrollMath
import dev.kanorin.gallery.logic.ScrollerVisibility
import dev.kanorin.gallery.logic.TimelineLayout
import dev.kanorin.gallery.ui.theme.GalleryColors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val FAST_HANDLE_TAG = "fast-handle"
const val FAST_BUBBLE_TAG = "fast-bubble"

private val HandleWidth = 22.dp
private val HandleHeight = 48.dp
private val BarWidth = 6.dp
private val BarDragWidth = 10.dp
private val SlideIn = 10.dp
private const val FADE_MS = 250
private val BarShape = RoundedCornerShape(3.dp)
private val BubbleShape = RoundedCornerShape(16.dp)
private val BubbleStyle = TextStyle(color = GalleryColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

/**
 * Right-edge scrub handle (spec 5.2): shows only while [gridState] scrolls, hides 1.5 s after, and can't be pressed
 * while hidden. Dragging jumps the grid by flat index and shows [sectionLabel] of the section at the handle.
 * Fills the height it's given; that height is the handle's travel.
 */
@Composable
fun FastScroller(
    gridState: LazyGridState,
    layout: TimelineLayout,
    sectionLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    val visibility = remember { ScrollerVisibility() }
    val currentLayout by rememberUpdatedState(layout)
    val currentClock by rememberUpdatedState(clock)
    val scope = rememberCoroutineScope()
    // Scrolls and releases; a flow, not state, so scrolling never recomposes this.
    val activity = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    var shown by remember { mutableStateOf(false) }
    // Non-null while a finger holds the handle: the handle follows the finger, not the grid.
    val drag = remember { mutableStateOf<Float?>(null) }
    val dragging by remember { derivedStateOf { drag.value != null } }
    var target by remember { mutableIntStateOf(0) }
    var trackPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val handlePx = with(density) { HandleHeight.roundToPx() }
    val slidePx = with(density) { SlideIn.roundToPx() }

    LaunchedEffect(gridState) {
        // drop(1): arriving on screen is not a scroll.
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                visibility.onScroll(currentClock())
                activity.tryEmit(Unit)
            }
    }
    LaunchedEffect(Unit) {
        // collectLatest restarts the wait on every scroll or release, so it counts from the latest one.
        activity.collectLatest {
            while (true) {
                val now = currentClock()
                shown = visibility.visible(now)
                delay(visibility.msUntilHidden(now) ?: break)
            }
        }
    }

    val gridFraction by remember(gridState) {
        derivedStateOf {
            FastScrollMath.fraction(
                gridState.firstVisibleItemIndex,
                gridState.layoutInfo.visibleItemsInfo.size,
                currentLayout.totalEntries,
                atEnd = !gridState.canScrollForward,
            )
        }
    }
    val total = layout.totalEntries
    val label = if (total > 0) sectionLabel(layout.sectionAtFlat(target.coerceIn(0, total - 1))) else ""
    val barWidth by animateDpAsState(if (dragging) BarDragWidth else BarWidth, tween(120), label = "fast-bar")

    Box(modifier.fillMaxHeight().onSizeChanged { trackPx = it.height }) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(FADE_MS)) + slideInHorizontally(tween(FADE_MS)) { slidePx },
            exit = fadeOut(tween(FADE_MS)) + slideOutHorizontally(tween(FADE_MS)) { slidePx },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, ((drag.value ?: gridFraction) * (trackPx - handlePx).coerceAtLeast(0)).roundToInt()) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(visible = dragging && label.isNotEmpty(), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                    Text(
                        label,
                        style = BubbleStyle,
                        maxLines = 1,
                        modifier = Modifier
                            .testTag(FAST_BUBBLE_TAG)
                            .padding(end = 6.dp)
                            .shadow(8.dp, BubbleShape)
                            .background(GalleryColors.Surface3, BubbleShape)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Box(
                    Modifier
                        .size(HandleWidth, HandleHeight)
                        .testTag(FAST_HANDLE_TAG)
                        // No pointer node at all while hidden: the grid underneath gets the touch.
                        .then(
                            if (!shown) Modifier else Modifier.pointerInput(gridState) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    if (!visibility.pressable(currentClock())) return@awaitEachGesture
                                    down.consume()
                                    val start = gridFraction
                                    val visible = gridState.layoutInfo.visibleItemsInfo.size
                                    val travel = (trackPx - handlePx).coerceAtLeast(1)
                                    var dy = 0f
                                    visibility.onDragStart()
                                    drag.value = start
                                    target = FastScrollMath.target(start, visible, currentLayout.totalEntries)
                                    try {
                                        while (true) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            val d = change.positionChange().y
                                            if (d == 0f) continue
                                            change.consume()
                                            dy += d
                                            val f = (start + dy / travel).coerceIn(0f, 1f)
                                            drag.value = f
                                            val t = FastScrollMath.target(f, visible, currentLayout.totalEntries)
                                            if (t != target) {
                                                target = t
                                                // A jump, not an animation: Paging loads the new spot directly.
                                                scope.launch { gridState.scrollToItem(t) }
                                            }
                                        }
                                    } finally {
                                        visibility.onDragEnd(currentClock())
                                        drag.value = null
                                        activity.tryEmit(Unit)
                                    }
                                }
                            },
                        )
                        .padding(end = 3.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(barWidth)
                            .background(if (dragging) GalleryColors.AccentPressed else GalleryColors.Accent, BarShape),
                    )
                }
            }
        }
    }
}
