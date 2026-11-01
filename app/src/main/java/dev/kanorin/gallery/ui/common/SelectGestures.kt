package dev.kanorin.gallery.ui.common

import android.util.Log
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.kanorin.gallery.logic.EdgeScroll
import dev.kanorin.gallery.logic.RangeSelection
import dev.kanorin.gallery.logic.TimelineLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What the hold gesture drives. Entries are flat grid indices. */
interface SelectTarget {
    /** A 350 ms hold on [flat]; true if it started a drag-select there. */
    fun onHold(flat: Int): Boolean
    fun onDragTo(flat: Int)
    fun onRelease()
    fun onTap(flat: Int)
}

private const val TAG = "SelectGestures"
private const val HOLD_MS = 350L
private val MoveCancel = 8.dp
private val ProbeInset = 4.dp

/**
 * Hold an entry 350 ms to select it, keep holding and drag to select the range, drag near the top or bottom edge
 * to auto-scroll (spec 5.2). Before the hold fires, more than 8 dp of movement (a scroll) or a second finger
 * (a pinch) cancels it. [bottomInset] px at the bottom sit under the nav bar, out of the finger's reach.
 */
fun Modifier.selectGestures(gridState: LazyGridState, bottomInset: Int, target: SelectTarget): Modifier =
    this then SelectGesturesElement(gridState, bottomInset, target)

private data class SelectGesturesElement(
    val gridState: LazyGridState,
    val bottomInset: Int,
    val target: SelectTarget,
) : ModifierNodeElement<SelectGesturesNode>() {
    override fun create() = SelectGesturesNode(gridState, bottomInset, target)

    override fun update(node: SelectGesturesNode) {
        node.bottomInset = bottomInset
        node.target = target
        if (node.gridState !== gridState) {
            node.gridState = gridState
            node.input.resetPointerInputHandler()
        }
    }
}

private enum class EarlyEnd { TAP, CANCEL }

private class SelectGesturesNode(
    var gridState: LazyGridState,
    var bottomInset: Int,
    var target: SelectTarget,
) : DelegatingNode() {
    val input = delegate(SuspendingPointerInputModifierNode { detect() })

    private suspend fun PointerInputScope.detect() = coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // null = the hold fired.
            val early = withTimeoutOrNull(HOLD_MS) { awaitEarlyEnd(down, MoveCancel.toPx()) }
            // The fast-scroll handle took this press.
            if (down.isConsumed) return@awaitEachGesture
            when (early) {
                EarlyEnd.TAP -> gridState.entryAt(down.position)?.let(target::onTap)
                EarlyEnd.CANCEL -> Unit
                null -> {
                    val flat = gridState.entryAt(down.position)
                    if (flat != null && target.onHold(flat)) drag(this@coroutineScope, down.id, down.position)
                }
            }
        }
    }

    private suspend fun AwaitPointerEventScope.awaitEarlyEnd(down: PointerInputChange, slop: Float): EarlyEnd {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) return EarlyEnd.CANCEL
            val change = event.changes.firstOrNull { it.id == down.id } ?: return EarlyEnd.CANCEL
            if (!change.pressed) return if (change.isConsumed) EarlyEnd.CANCEL else EarlyEnd.TAP
            // Consumed = the grid's scroll already took it (some phones' slop is under 8 dp).
            if (change.isConsumed || (change.position - down.position).getDistance() > slop) return EarlyEnd.CANCEL
        }
    }

    private suspend fun AwaitPointerEventScope.drag(scope: CoroutineScope, id: PointerId, start: Offset) {
        var finger = start
        var auto: Job? = null
        try {
            while (true) {
                // Initial pass: the grid's scroll never sees the drag, and a lift over a header circle doesn't click it.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == id } ?: break
                change.consume()
                if (!change.pressed) break
                if (change.position == finger || event.changes.count { it.pressed } > 1) continue
                finger = change.position
                gridState.entryAt(finger)?.let(target::onDragTo)
                if (auto?.isActive != true && edgeStep(finger.y, size.height, EdgeScroll.FRAME_MS) != 0f) {
                    auto = scope.launch { autoScroll(this@drag, size.height) { finger } }
                }
            }
        } finally {
            auto?.cancel()
            target.onRelease()
        }
    }

    /** Scrolls each frame while the finger sits in an edge zone; the next move restarts it after it stops. */
    private suspend fun autoScroll(density: Density, height: Int, finger: () -> Offset) {
        val lo = with(density) { ProbeInset.toPx() }
        val hi = maxOf(lo, height - bottomInset - lo)
        var last: Long? = null
        while (true) {
            if (with(density) { edgeStep(finger().y, height, EdgeScroll.FRAME_MS) } == 0f) return
            val now = withFrameNanos { it }
            val step = with(density) { edgeStep(finger().y, height, EdgeScroll.frameMs(last, now)) }
            last = now
            if (step == 0f) continue // no time passed: nothing to move, not the grid's end
            if (gridState.scrollBy(step) == 0f) return
            // The content moved under a still finger: probe just inside the grid, as the mockup does.
            val f = finger()
            gridState.entryAt(Offset(f.x, f.y.coerceIn(lo, hi)))?.let(target::onDragTo)
        }
    }

    /** The scroll in px for [frameMs] of time: the mockup's CSS px are dp here. */
    private fun Density.edgeStep(y: Float, height: Int, frameMs: Float): Float =
        EdgeScroll.step(y.toDp().value, 0f, (height - bottomInset).toDp().value, frameMs).dp.toPx()
}

/**
 * Turns hold-drags on a [TimelineLayout] grid into [selection] changes. Keys come from loaded tiles first; on the
 * first miss the whole ordered path list ([pathsInOrder]) is fetched and kept for that drag. Unknown keys are skipped.
 */
class DragSelect(private val selection: SelectionState, private val scope: CoroutineScope) : SelectTarget {
    var layout = TimelineLayout(emptyList())

    /** A loaded tile's path by item index; null for a placeholder. */
    var loadedKey: (Int) -> String? = { null }
    var pathsInOrder: suspend () -> List<String> = { emptyList() }

    /** A tap while not selecting, by item index: the host opens it (the viewer). */
    var onOpen: (Int) -> Unit = {}

    private var drag: Drag? = null

    private inner class Drag(val range: RangeSelection, var current: Int) {
        val known = HashMap<Int, String>()
        var paths: List<String>? = null
        var fetch: Job? = null
        var lifted = false

        fun keyAt(item: Int): String? {
            known[item]?.let { return it }
            val key = loadedKey(item) ?: paths?.getOrNull(item)
            // Remembered for the drag, so a page dropped mid-drag doesn't un-select its tiles.
            if (key == null) fetchPaths() else known[item] = key
            return key
        }

        fun apply() {
            // ✕ or back while the finger was still down.
            if (!selection.dragging) return end()
            selection.apply(range.step(current, ::keyAt))
        }

        private fun fetchPaths() {
            if (paths != null || fetch != null) return
            fetch = scope.launch {
                val loaded = try {
                    pathsInOrder()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tiles already selected stay; the drag just can't reach unloaded ones.
                    Log.w(TAG, "drag-select: ordered paths failed", e)
                    end()
                    return@launch
                }
                if (drag !== this@Drag) return@launch
                paths = loaded
                apply()
                if (lifted) end()
            }
        }

        fun end() {
            fetch?.cancel()
            if (drag === this) {
                drag = null
                selection.endDrag()
            }
        }
    }

    override fun onHold(flat: Int): Boolean {
        val item = itemAt(flat) ?: return false
        val key = loadedKey(item) ?: return false
        drag?.end()
        val d = Drag(RangeSelection(item, selection.selected.toSet(), select = key !in selection.selected), item)
        drag = d
        selection.beginDrag()
        d.apply()
        return true
    }

    override fun onDragTo(flat: Int) {
        val d = drag?.takeUnless { it.lifted } ?: return
        // Header rows are skipped.
        val item = itemAt(flat) ?: return
        if (item == d.current) return
        d.current = item
        d.apply()
    }

    override fun onRelease() {
        val d = drag ?: return
        d.lifted = true
        // Keys still unknown are filled in when the paths arrive.
        if (d.fetch?.isActive != true) d.end()
    }

    override fun onTap(flat: Int) {
        val item = itemAt(flat) ?: return
        if (!selection.active) return onOpen(item)
        loadedKey(item)?.let(selection::toggle)
    }

    /** The grid left composition: its scope is gone, so a drag waiting on paths would never end. */
    fun dispose() {
        drag?.end()
    }

    private fun itemAt(flat: Int): Int? =
        if (flat in 0 until layout.totalEntries) (layout.entryAt(flat) as? TimelineLayout.Entry.Item)?.itemIndex else null
}
