package dev.kanorin.gallery.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import dev.kanorin.gallery.logic.GridAnchor
import dev.kanorin.gallery.logic.PinchStepper
import kotlin.math.roundToInt

/**
 * Two-finger pinch -> [stepper] steps. Each step reports the new column count and the fingers' midpoint in this
 * node's px. One finger passes straight through to the grid's own scroll.
 */
fun Modifier.pinchColumns(stepper: PinchStepper, onColumns: (newCols: Int, focus: Offset) -> Unit): Modifier =
    this then PinchColumnsElement(stepper, onColumns)

/** Keeps the grid entry under [focus] (grid px) at its current height through the next re-layout. */
fun LazyGridState.requestAnchorAt(focus: Offset) {
    val cell = cellAt(focus, GridAnchor::pick) ?: return
    val (index, offset) = GridAnchor.scrollFor(cell)
    // Not scrollToItem: that measures now, with the old column count; this waits for the new one.
    requestScrollToItem(index, offset)
}

/** The flat index of the entry exactly under [point] (grid px), or null over a gap. */
fun LazyGridState.entryAt(point: Offset): Int? = cellAt(point, GridAnchor::hit)?.index

private fun LazyGridState.cellAt(point: Offset, find: (List<GridAnchor.Cell>, Int, Int) -> GridAnchor.Cell?): GridAnchor.Cell? {
    val info = layoutInfo
    val cells = info.visibleItemsInfo.map { GridAnchor.Cell(it.index, it.offset.x, it.offset.y, it.size.width, it.size.height) }
    // Item offsets start below the top content padding; viewportStartOffset is minus that padding.
    return find(cells, point.x.roundToInt(), point.y.roundToInt() + info.viewportStartOffset)
}

private data class PinchColumnsElement(
    val stepper: PinchStepper,
    val onColumns: (Int, Offset) -> Unit,
) : ModifierNodeElement<PinchColumnsNode>() {
    override fun create() = PinchColumnsNode(stepper, onColumns)

    override fun update(node: PinchColumnsNode) {
        // A new callback each recomposition must not restart a pinch in progress.
        node.onColumns = onColumns
        if (node.stepper !== stepper) {
            node.stepper = stepper
            node.input.resetPointerInputHandler()
        }
    }
}

private class PinchColumnsNode(var stepper: PinchStepper, var onColumns: (Int, Offset) -> Unit) : DelegatingNode() {
    val input = delegate(SuspendingPointerInputModifierNode { detectPinch() })

    private suspend fun PointerInputScope.detectPinch() = awaitEachGesture {
        // Initial pass: the pinch sees the fingers before the grid's scroll does.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        stepper.reset()
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) stepper.onScale(zoom)?.let { onColumns(it, event.calculateCentroid()) }
                    // Two fingers belong to the pinch, not the scroll.
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            stepper.reset()
        }
    }
}
