package dev.kanorin.gallery.logic

import dev.kanorin.gallery.logic.TimelineLayout.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineLayoutTest {
    private fun sections(vararg counts: Int): List<Section> {
        var first = 0
        return counts.mapIndexed { i, c -> Section(i.toLong(), "s$i", c, first).also { first += c } }
    }

    private val layout = TimelineLayout(sections(7, 11, 4))

    @Test fun totalCountsHeadersAndItems() = assertEquals(25, layout.totalEntries)

    @Test fun itemCountLeavesHeadersOut() {
        assertEquals(22, layout.itemCount)
        assertEquals(22, TimelineLayout(sections(7, 11, 4), headers = false).itemCount)
        assertEquals(0, TimelineLayout(emptyList()).itemCount)
    }

    @Test fun firstSectionStartsWithItsHeader() {
        assertEquals(Entry.Header(0), layout.entryAt(0))
        assertEquals(Entry.Item(0, 0), layout.entryAt(1))
    }

    @Test fun secondHeaderFollowsTheFirstSection() {
        assertEquals(Entry.Header(1), layout.entryAt(8))
        assertEquals(9, layout.flatIndexOfItem(7))
    }

    @Test fun lastEntryIsInTheLastSection() = assertEquals(2, layout.sectionAtFlat(24))

    @Test fun everyItemRoundTrips() {
        var expectedItem = 0
        for (flat in 0 until layout.totalEntries) {
            val e = layout.entryAt(flat)
            assertEquals(layout.sectionAtFlat(flat), if (e is Entry.Item) e.section else (e as Entry.Header).section)
            if (e is Entry.Item) {
                assertEquals(expectedItem++, e.itemIndex)
                assertEquals(flat, layout.flatIndexOfItem(e.itemIndex))
            }
        }
        assertEquals(22, expectedItem)
    }

    @Test fun withoutHeadersOnlyItems() {
        val flat = TimelineLayout(sections(7, 11, 4), headers = false)
        assertEquals(22, flat.totalEntries)
        assertEquals(Entry.Item(0, 0), flat.entryAt(0))
        assertEquals(Entry.Item(7, 1), flat.entryAt(7))
        assertEquals(7, flat.flatIndexOfItem(7))
        assertEquals(2, flat.sectionAtFlat(21))
    }

    @Test fun emptyHasNoEntries() = assertEquals(0, TimelineLayout(emptyList()).totalEntries)
}
