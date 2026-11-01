package dev.kanorin.gallery.logic

/** Which grid entry a pinch holds in place, and the scroll that keeps it there. */
object GridAnchor {
    /** A laid-out entry; [top] is in the grid's scroll-offset space (0 = just below the top content padding). */
    data class Cell(val index: Int, val left: Int, val top: Int, val width: Int, val height: Int)

    /** The entry under ([x], [y]); off every entry, the nearest row, then the nearest entry in it. */
    fun pick(cells: List<Cell>, x: Int, y: Int): Cell? =
        cells.minWithOrNull(compareBy<Cell>({ gap(y, it.top, it.height) }, { gap(x, it.left, it.width) }))

    /** The entry exactly under ([x], [y]), or null over a gap: a drag-select doesn't snap to the nearest. */
    fun hit(cells: List<Cell>, x: Int, y: Int): Cell? =
        cells.firstOrNull { gap(y, it.top, it.height) == 0 && gap(x, it.left, it.width) == 0 }

    /** scrollToItem(index, offset) puts the entry's line at -offset, so this keeps [cell] at its current top. */
    fun scrollFor(cell: Cell): Pair<Int, Int> = cell.index to -cell.top

    private fun gap(p: Int, start: Int, size: Int): Int = when {
        p < start -> start - p
        p >= start + size -> p - (start + size - 1)
        else -> 0
    }
}
