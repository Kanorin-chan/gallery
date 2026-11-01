package dev.kanorin.gallery.logic

/** The built-in Favourites folder's path: a grid query for it joins `favorites` instead of matching a folder. */
const val FAVORITES_FOLDER = "favorites://"

enum class GroupBy { DAY, MONTH, YEAR, NONE }

/** DURATION = a video's length; photos have none and sort after every video. */
enum class SortField { DATE_TAKEN, DATE_MODIFIED, NAME, SIZE, DURATION }

/** The ⋮ sheet's Show chips. */
enum class ShowChip { PHOTOS, VIDEOS, GIFS, SCREENSHOTS, FAVOURITES }

/** What a grid shows. photos/videos/gifs include kinds; screenshotsOnly/favouritesOnly narrow the set. */
data class MediaFilter(
    val photos: Boolean = true,
    val videos: Boolean = true,
    val gifs: Boolean = true,
    val screenshotsOnly: Boolean = false,
    val favouritesOnly: Boolean = false,
    val sort: SortField = SortField.DATE_TAKEN,
    val descending: Boolean = true,
    val groupBy: GroupBy = GroupBy.DAY,
    /** The search pill's text and dates (All view only); null = no search. */
    val search: SearchQuery? = null,
) {
    /** Groups follow the sorted date, so they stay contiguous; a name, size or length sort has no date groups. */
    val effectiveGroupBy: GroupBy
        get() = if (sort == SortField.DATE_TAKEN || sort == SortField.DATE_MODIFIED) groupBy else GroupBy.NONE

    fun isOn(chip: ShowChip): Boolean = when (chip) {
        ShowChip.PHOTOS -> photos
        ShowChip.VIDEOS -> videos
        ShowChip.GIFS -> gifs
        ShowChip.SCREENSHOTS -> screenshotsOnly
        ShowChip.FAVOURITES -> favouritesOnly
    }

    /** Something hides part of the library: a search, a narrower or a kind turned off ("No matching items"). */
    val narrowed: Boolean get() = search != null || screenshotsOnly || favouritesOnly || !(photos && videos && gifs)

    /** [chip] is the only one of Photos / Videos / GIFs on: it can't be turned off (that grid could only be empty). */
    fun isLastKindOn(chip: ShowChip): Boolean = chip in KINDS && isOn(chip) && KINDS.count(::isOn) == 1

    /** Turning the last kind off is refused. */
    fun withChip(chip: ShowChip, on: Boolean): MediaFilter = if (!on && isLastKindOn(chip)) this else when (chip) {
        ShowChip.PHOTOS -> copy(photos = on)
        ShowChip.VIDEOS -> copy(videos = on)
        ShowChip.GIFS -> copy(gifs = on)
        ShowChip.SCREENSHOTS -> copy(screenshotsOnly = on)
        ShowChip.FAVOURITES -> copy(favouritesOnly = on)
    }

    private companion object {
        val KINDS = listOf(ShowChip.PHOTOS, ShowChip.VIDEOS, ShowChip.GIFS)
    }
}
