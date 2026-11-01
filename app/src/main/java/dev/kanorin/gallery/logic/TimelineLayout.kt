package dev.kanorin.gallery.logic

/**
 * One timeline group. [firstItemIndex] is the index of its first item in the paged list.
 * [scrollLabel] is the fast-scroll bubble's text, the group's month ("Oct 2026") even when grouped by day.
 */
data class Section(val key: Long, val label: String, val count: Int, val firstItemIndex: Int, val scrollLabel: String = "")

/** Flattens sections into grid entries: each section's header (unless [headers] is off), then its items. */
class TimelineLayout(val sections: List<Section>, val headers: Boolean = true) {
    sealed interface Entry {
        data class Header(val section: Int) : Entry
        data class Item(val itemIndex: Int, val section: Int) : Entry
    }

    private val perHeader = if (headers) 1 else 0

    // Running item totals: firstItem[s] is section s's first item index, firstItem[size] the item count.
    private val firstItem = IntArray(sections.size + 1).also { acc ->
        sections.forEachIndexed { s, sec -> acc[s + 1] = acc[s] + sec.count }
    }

    /** Items across every section: the paged list's size. */
    val itemCount: Int = firstItem[sections.size]

    val totalEntries: Int = itemCount + sections.size * perHeader

    /** Flat index of section [s]'s first entry (its header, or its first item when headerless). */
    private fun startFlat(s: Int) = firstItem[s] + s * perHeader

    fun sectionAtFlat(flatIndex: Int): Int {
        require(flatIndex in 0 until totalEntries) { "flat index $flatIndex outside 0 until $totalEntries" }
        // Last section starting at or before flatIndex.
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startFlat(mid) <= flatIndex) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun entryAt(flatIndex: Int): Entry {
        val s = sectionAtFlat(flatIndex)
        val offset = flatIndex - startFlat(s) - perHeader
        return if (offset < 0) Entry.Header(s) else Entry.Item(firstItem[s] + offset, s)
    }

    fun flatIndexOfItem(itemIndex: Int): Int {
        require(itemIndex in 0 until firstItem[sections.size]) { "item $itemIndex outside 0 until ${firstItem[sections.size]}" }
        var lo = 0
        var hi = sections.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firstItem[mid] <= itemIndex) lo = mid else hi = mid - 1
        }
        return itemIndex + (lo + 1) * perHeader
    }
}
