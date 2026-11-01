package dev.kanorin.gallery.logic

import dev.kanorin.gallery.logic.GridAnchor.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridAnchorTest {
    // A header (index 0, 40 px) then 4-column rows of 88 px tiles with 2 px gaps.
    private val header = Cell(index = 0, left = 0, top = 0, width = 358, height = 40)
    private val tiles = (0 until 12).map { i ->
        Cell(index = 1 + i, left = (i % 4) * 90, top = 42 + (i / 4) * 90, width = 88, height = 88)
    }
    private val cells = listOf(header) + tiles

    @Test fun picksTheTileUnderTheFingers() = assertEquals(tiles[6], GridAnchor.pick(cells, x = 200, y = 160))

    @Test fun picksTheHeaderWhenTheFingersAreOnIt() = assertEquals(header, GridAnchor.pick(cells, x = 300, y = 20))

    @Test fun aGapBetweenColumnsGoesToTheNearestTile() = assertEquals(tiles[5], GridAnchor.pick(cells, x = 178, y = 160))

    @Test fun aGapBetweenRowsGoesToTheNearestRow() = assertEquals(tiles[4], GridAnchor.pick(cells, x = 10, y = 131))

    @Test fun belowTheLastRowGoesToTheLastRow() = assertEquals(tiles[9], GridAnchor.pick(cells, x = 100, y = 400))

    @Test fun nothingVisibleMeansNoAnchor() = assertNull(GridAnchor.pick(emptyList(), 10, 10))

    @Test fun hitFindsOnlyTheEntryUnderThePoint() {
        assertEquals(tiles[6], GridAnchor.hit(cells, x = 200, y = 160))
        assertEquals(header, GridAnchor.hit(cells, x = 300, y = 20))
        // Gaps and the space below the last row hit nothing (pick would fall back to the nearest).
        assertNull(GridAnchor.hit(cells, x = 178, y = 160))
        assertNull(GridAnchor.hit(cells, x = 10, y = 131))
        assertNull(GridAnchor.hit(cells, x = 100, y = 400))
    }

    @Test fun scrollKeepsTheAnchorsTop() {
        // scrollToItem(index, offset) puts the item's line at -offset; a tile 132 px down needs offset -132.
        assertEquals(7 to -132, GridAnchor.scrollFor(tiles[6]))
        assertEquals(7 to 30, GridAnchor.scrollFor(tiles[6].copy(top = -30)))
    }
}
