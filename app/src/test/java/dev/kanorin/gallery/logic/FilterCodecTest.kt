package dev.kanorin.gallery.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterCodecTest {
    private fun roundTrip(f: MediaFilter) = assertEquals(f, FilterCodec.decode(FilterCodec.encode(f)))

    @Test fun theDefaultFilterComesBack() = roundTrip(MediaFilter())

    @Test fun everyFieldComesBack() {
        roundTrip(
            MediaFilter(
                photos = false, videos = true, gifs = false, screenshotsOnly = true, favouritesOnly = true,
                sort = SortField.SIZE, descending = false, groupBy = GroupBy.NONE,
            ),
        )
        SortField.entries.forEach { roundTrip(MediaFilter(sort = it)) }
        GroupBy.entries.forEach { roundTrip(MediaFilter(groupBy = it)) }
    }

    @Test fun aSearchComesBackWhateverItsTextHolds() {
        val texts = listOf("trip", "a.b.c", "x|y", "100% _done_", "旅行 2026 🌸", " spaced ", "t", "")
        texts.forEach { roundTrip(MediaFilter(search = SearchQuery(it, null, null))) }
        roundTrip(MediaFilter(search = SearchQuery(null, 1_790_000_000_000L, 1_790_086_400_000L)))
        roundTrip(MediaFilter(search = SearchQuery("kl.", -86_400_000L, 0L)))
        roundTrip(MediaFilter(search = SearchQuery(null, 5L, null)))
    }

    @Test fun garbageIsNull() {
        assertNull(FilterCodec.decode(""))
        assertNull(FilterCodec.decode("nonsense"))
        assertNull(FilterCodec.decode("111001.BOGUS.DAY"))
        assertNull(FilterCodec.decode("1110x1.NAME.DAY"))
        assertNull(FilterCodec.decode("111001.NAME.DAY.abc..t"))
    }
}
